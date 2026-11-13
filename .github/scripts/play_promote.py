"""내부 테스트 트랙의 번들을 프로덕션 트랙으로 승격한다. promote.yml 이 부른다.

환경변수: RUNNER_TEMP(play.json, notes.txt 가 있는 곳), CODE(versionCode), NAME(v1.2.3 같은 버전 이름)
"""
import os
import sys

from google.oauth2 import service_account
from google.auth.transport.requests import AuthorizedSession

PKG = "com.youngs.dailynet"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"

tmp = os.environ["RUNNER_TEMP"]
code = os.environ["CODE"]
name = os.environ["NAME"]

creds = service_account.Credentials.from_service_account_file(
    os.path.join(tmp, "play.json"), scopes=[SCOPE])
s = AuthorizedSession(creds)
base = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"


def check(r, what):
    if not r.ok:
        print(f"{what} 실패: HTTP {r.status_code}\n{r.text}")
        sys.exit(1)
    return r


edit = check(s.post(f"{base}/edits"), "edit 생성").json()["id"]

# 내부 테스트 트랙에 그 versionCode 가 정말 있는지 확인한다. 없는 번들을 적으면 커밋에서 400 이 난다.
internal = check(s.get(f"{base}/edits/{edit}/tracks/internal"), "internal 트랙 조회").json()
codes = {c for rel in internal.get("releases", []) for c in rel.get("versionCodes", [])}
if code not in codes:
    print(f"내부 테스트 트랙에 b{code} 가 없다. 있는 것: {sorted(codes) or '없음'}")
    sys.exit(1)

notes = open(os.path.join(tmp, "notes.txt"), encoding="utf-8").read().strip()
body = {"releases": [{
    "name": f"{name} (b{code})",
    "versionCodes": [code],
    "status": "completed",
    "releaseNotes": [{"language": "ko-KR", "text": notes}],
}]}
check(s.put(f"{base}/edits/{edit}/tracks/production", json=body), "production 트랙 갱신")
check(s.post(f"{base}/edits/{edit}:commit"), "edit 커밋")
print(f"b{code} 를 프로덕션 트랙으로 승격했다")
