"""
store-listing/<언어>/ 의 문안을 Play Console 스토어 등록정보에 올린다.

deploy.yml 의 listing 잡이 store-listing/ 이 바뀐 커밋에서 실행한다.
콘솔에서 손으로 붙여 넣던 것을 대신하며, 저장소의 파일이 곧 등록정보가 된다.

사용: python play_listing.py <서비스계정.json> <store-listing 폴더> [--check]
  --check 를 주면 현재 등록정보를 읽어 보여주기만 하고 바꾸지 않는다 (preflight 용).

서비스 계정에 Play Console "스토어 등록정보 관리" 권한이 있어야 한다.
"출시 관리"만 있으면 읽기는 되고 쓰기에서 403 이 난다.
"""
import sys
from pathlib import Path

from google.oauth2 import service_account
from google.auth.transport.requests import AuthorizedSession

PACKAGE = "com.youngs.dailynet"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/edits"
LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
FILES = {"title": "title.txt", "shortDescription": "short-description.txt", "fullDescription": "full-description.txt"}


def read_listing(folder: Path) -> dict:
    """폴더의 세 파일을 읽어 API 본문으로 만든다. 길이 제한을 넘으면 바로 실패한다."""
    body = {}
    for field, name in FILES.items():
        path = folder / name
        if not path.exists():
            sys.exit(f"::error::{path} 가 없다")
        text = path.read_text(encoding="utf-8").strip()
        if len(text) > LIMITS[field]:
            sys.exit(f"::error::{path} 가 {len(text)}자다. 제한 {LIMITS[field]}자")
        if not text:
            sys.exit(f"::error::{path} 가 비어 있다")
        body[field] = text
    return body


def upload_screenshots(s: AuthorizedSession, edit_id: str, lang: str, shots: list, image_type: str) -> int:
    """
    *.png 를 파일명 순서대로 image_type(phoneScreenshots / sevenInchScreenshots / tenInchScreenshots) 칸에 올린다.
    기존 것은 전부 지우고 다시 올린다.
    (Play 는 순서를 바꾸는 API 가 없어서, 순서를 보장하려면 지우고 차례로 올리는 수밖에 없다)
    바뀐 것이 있으면 1 을 돌려준다.
    """
    if len(shots) > 8:
        sys.exit(f"::error::{lang} 스크린샷이 {len(shots)}장이다. Play 는 8장까지만 받는다")
    r = s.delete(f"{BASE}/{edit_id}/listings/{lang}/{image_type}")
    if r.status_code not in (200, 204):
        sys.exit(f"::error::{lang} {image_type} 기존 스크린샷 삭제 실패 {r.status_code}: {r.text[:300]}")
    upload = (f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
              f"/edits/{edit_id}/listings/{lang}/{image_type}?uploadType=media")
    for path in shots:
        r = s.post(upload, data=path.read_bytes(), headers={"Content-Type": "image/png"})
        if r.status_code != 200:
            sys.exit(f"::error::{path.name} 업로드 실패 {r.status_code}: {r.text[:300]}")
        print(f"  올림 [{image_type}]: {path.name} ({path.stat().st_size // 1024}KB)")
    return 1


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    sa_path, root = sys.argv[1], Path(sys.argv[2])
    check_only = "--check" in sys.argv

    creds = service_account.Credentials.from_service_account_file(
        sa_path, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    s = AuthorizedSession(creds)

    r = s.post(BASE)
    if r.status_code != 200:
        sys.exit(f"::error::편집 세션 열기 실패 {r.status_code}: {r.text[:300]}")
    edit_id = r.json()["id"]

    try:
        # 지금 올라가 있는 등록정보. 무엇이 바뀌는지 로그에 남긴다.
        cur = s.get(f"{BASE}/{edit_id}/listings")
        current = {l["language"]: l for l in cur.json().get("listings", [])} if cur.status_code == 200 else {}
        for lang, l in current.items():
            print(f"[현재 {lang}] 제목: {l.get('title')}")
            print(f"[현재 {lang}] 간단한 설명: {l.get('shortDescription')}")

        langs = [p for p in root.iterdir() if p.is_dir()]
        if not langs:
            sys.exit(f"::error::{root} 아래에 언어 폴더가 없다")

        changed = 0
        for folder in langs:
            lang = folder.name  # ko-KR 처럼 Play 언어 코드와 같은 폴더명
            body = read_listing(folder)
            before = current.get(lang, {})
            same = all(before.get(k) == v for k, v in body.items())
            print(f"[{lang}] 제목 {len(body['title'])}자 / 간단한 설명 {len(body['shortDescription'])}자 / "
                  f"자세한 설명 {len(body['fullDescription'])}자 {'(변경 없음)' if same else '(변경됨)'}")
            shots = sorted((folder / "screenshots").glob("*.png")) if (folder / "screenshots").is_dir() else []
            tablet = sorted((folder / "screenshots-tablet").glob("*.png")) if (folder / "screenshots-tablet").is_dir() else []
            print(f"[{lang}] 휴대전화 스크린샷 {len(shots)}장 / 태블릿 {len(tablet)}장")
            if check_only:
                continue
            if not same:
                body["language"] = lang
                r = s.put(f"{BASE}/{edit_id}/listings/{lang}", json=body)
                if r.status_code != 200:
                    if r.status_code == 403:
                        print("::error::등록정보 쓰기 권한이 없다. Play Console → 사용자 및 권한에서 "
                              "이 서비스 계정에 '스토어 등록정보 관리' 권한을 추가해야 한다")
                    sys.exit(f"::error::{lang} 등록정보 쓰기 실패 {r.status_code}: {r.text[:300]}")
                changed += 1
            if shots:
                changed += upload_screenshots(s, edit_id, lang, shots, "phoneScreenshots")
            if tablet:
                # 7인치·10인치 칸에 같은 이미지를 올린다. 따로 만들 만큼 레이아웃이 다르지 않다.
                changed += upload_screenshots(s, edit_id, lang, tablet, "sevenInchScreenshots")
                changed += upload_screenshots(s, edit_id, lang, tablet, "tenInchScreenshots")

        if check_only:
            print("확인만 했다. 바꾸지 않았다.")
            return
        if changed == 0:
            print("바뀐 등록정보가 없어 커밋하지 않는다.")
            return

        r = s.post(f"{BASE}/{edit_id}:commit")
        if r.status_code != 200:
            if r.status_code == 403:
                # 2026-10-02 실제로 겪었다: 쓰기(PUT)는 통과하고 커밋에서 PERMISSION_DENIED.
                # 권한 검사는 커밋 시점에 한다. "출시 관리"만으로는 등록정보를 바꾸지 못한다.
                print("::error::등록정보 커밋 권한이 없다. Play Console → 사용자 및 권한 → 이 서비스 계정 → "
                      "앱 권한에서 '스토어 등록정보 관리'(Manage store presence)를 켜야 한다")
            sys.exit(f"::error::커밋 실패 {r.status_code}: {r.text[:500]}")
        print(f"등록정보 {changed}개 언어 반영 완료. Google 검토 후 스토어에 보인다.")
        edit_id = None  # 커밋됐으니 지우지 않는다
    finally:
        if edit_id:
            s.delete(f"{BASE}/{edit_id}")


if __name__ == "__main__":
    main()
