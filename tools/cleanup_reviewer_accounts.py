"""
Play 심사관·사전 출시 보고서 로봇이 남긴 계정 정리.

심사 때마다 Google 심사관이 새 계정으로 로그인해 Firebase Auth와 Firestore에
빈 계정이 쌓인다. 심사관 계정은 전부 `이름성.숫자5자리@gmail.com` 꼴이고,
사전 출시 보고서 로봇은 `@cloudtestlabaccounts.com` 이다. 둘 다 가입한 날
한 번 로그인하고 다시는 안 들어온다.

판정 조건:
  - 로봇(cloudtestlabaccounts.com): 무조건 지운다
  - 심사관 패턴 gmail: 가입일 == 마지막 로그인일 이고 settlements 기록이 1개 이하일 때만
    (하루만 쓴 계정. 심사 중 하나 저장해 보는 경우까지 허용)

지우는 것: Firestore users/{uid} 문서와 하위 컬렉션 전부, Firebase Auth 계정.

사용법 (gcloud 로그인 + firebase CLI 로그인 상태에서):
  python tools/cleanup_reviewer_accounts.py          # 미리보기만
  python tools/cleanup_reviewer_accounts.py --apply  # 실제 삭제
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

PROJECT = "daily-net-95d28"
FS = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents"
SUBCOLLECTIONS = ["settlements", "inbody", "passes", "evaluations", "favorites"]
REVIEWER = re.compile(r"^[a-z]+\.\d{5}@gmail\.com$")
TESTLAB = re.compile(r"@cloudtestlabaccounts\.com$")


def token():
    return subprocess.check_output(["gcloud", "auth", "print-access-token"], shell=True, text=True).strip()


def call(tok, url, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + tok,
        "x-goog-user-project": PROJECT,
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r) if r.length != 0 else {}
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return {}
        raise RuntimeError(f"{method} {url}: {e.code} {e.read().decode()[:200]}")


def export_users():
    path = os.path.join(tempfile.gettempdir(), "_dailynet_auth_export.json")
    subprocess.run(["firebase", "auth:export", "--project", PROJECT, "--format=json", path],
                   shell=True, check=True, capture_output=True)
    try:
        return json.load(open(path, encoding="utf-8"))["users"]
    finally:
        os.remove(path)


def day(ms):
    return time.strftime("%Y-%m-%d", time.gmtime(int(ms) / 1000)) if ms else "-"


def list_docs(tok, path):
    names, page = [], None
    while True:
        url = f"{FS}/{path}?pageSize=300&mask.fieldPaths=__name__" + (f"&pageToken={page}" if page else "")
        res = call(tok, url)
        names += [d["name"] for d in res.get("documents", [])]
        page = res.get("nextPageToken")
        if not page:
            return names


def delete_user_data(tok, uid):
    n = 0
    for sub in SUBCOLLECTIONS:
        for name in list_docs(tok, f"users/{uid}/{sub}"):
            call(tok, f"https://firestore.googleapis.com/v1/{name}", "DELETE")
            n += 1
    call(tok, f"{FS}/users/{uid}", "DELETE")
    return n


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    apply = "--apply" in sys.argv
    tok = token()
    targets = []
    for u in export_users():
        email = u.get("email", "")
        if not (REVIEWER.match(email) or TESTLAB.search(email)):
            continue
        created, last = day(u.get("createdAt")), day(u.get("lastSignedInAt"))
        settlements = len(list_docs(tok, f"users/{u['localId']}/settlements"))
        # 로봇 계정은 사람이 아니니 날짜 조건 없이 지운다 (사전 출시 보고서가 두 번 돌면 로그인 날짜가 갈린다)
        if REVIEWER.match(email) and (created != last or settlements > 1):
            print(f"건너뜀 (조건 불일치): {email} 가입 {created} 마지막 {last} 기록 {settlements}")
            continue
        targets.append((u["localId"], email, created, settlements))

    targets.sort(key=lambda t: t[2])
    print(f"\n삭제 대상 {len(targets)}개")
    for uid, email, created, s in targets:
        print(f"  {created}  {email}  기록 {s}")
    if not targets:
        return
    if not apply:
        print("\n미리보기입니다. 실제로 지우려면 --apply 를 붙이세요.")
        return

    docs = 0
    for uid, email, _, _ in targets:
        docs += delete_user_data(tok, uid)
    ids = [t[0] for t in targets]
    for i in range(0, len(ids), 100):
        res = call(tok, f"https://identitytoolkit.googleapis.com/v1/projects/{PROJECT}/accounts:batchDelete",
                   "POST", {"localIds": ids[i:i + 100], "force": True})
        for err in res.get("errors", []):
            print("Auth 삭제 실패:", err)
    print(f"\n완료: Auth 계정 {len(ids)}개, Firestore 문서 {docs + len(ids)}개 삭제")


if __name__ == "__main__":
    main()
