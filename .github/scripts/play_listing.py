"""
store-listing/<언어>/ 의 문안과 이미지를 Play Console 스토어 등록정보에 올린다.

deploy.yml 의 listing 잡이 store-listing/ 이 바뀐 커밋에서 실행한다.
콘솔에서 손으로 붙여 넣던 것을 대신하며, 저장소의 파일이 곧 등록정보가 된다.

이미지는 실제로 바뀐 칸만 다시 올린다. 문안(full-description.txt 등)만 고친 커밋에서
아이콘·그래픽·스크린샷을 지웠다 다시 올리면, 올리는 시간만 드는 게 아니라
그 사이 등록정보가 이미지 없는 상태로 검토에 들어갈 수 있다.
바뀌었는지는 두 가지로 판단한다.

  --changed 목록이 있으면  git 이 "이번 커밋에서 바뀌었다" 고 한 파일이 있는 칸만 올린다.
  목록이 없으면            Play 에 지금 올라가 있는 이미지의 sha1 과 로컬 파일을 비교해,
                           같은 것이 이미 있으면 건너뛴다.

목록이 없을 때 해시 비교를 하는 이유는 손으로 돌릴 때(--check 포함)도 쓸 수 있게 하기 위해서고,
목록이 있을 때도 목록에 없는데 Play 에 그 이미지가 비어 있으면 복구 차원에서 올린다.

사용: python play_listing.py <서비스계정.json> <store-listing 폴더> [--check] [--changed <바뀐 파일 목록>]
  --check    현재 등록정보를 읽어 무엇을 올릴지만 보여주고 바꾸지 않는다 (preflight 용).
  --changed  이번 커밋에서 바뀐 파일 경로 목록을 담은 파일 (git diff --name-only 출력 그대로).
             저장소 기준 경로(store-listing/ko-KR/icon.png)와 폴더 기준 경로(ko-KR/icon.png) 둘 다 받는다.

서비스 계정에 Play Console "스토어 등록정보 관리" 권한이 있어야 한다.
"출시 관리"만 있으면 읽기는 되고 쓰기에서 403 이 난다.
"""
import hashlib
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


def sha1_of(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def same_on_play(s: AuthorizedSession, edit_id: str, lang: str, image_type: str, files: list) -> bool:
    """
    Play 에 지금 올라가 있는 것이 로컬 파일과 같은가. 장수와 순서까지 본다.

    Play 는 올린 이미지의 sha1 을 Image.sha1 로 돌려준다. 비교가 어긋나면(Play 가 다시 인코딩하는 등)
    '같지 않다' 로 흘러 예전처럼 다시 올릴 뿐이라, 틀려도 등록정보가 깨지지는 않는다.
    목록을 못 읽어도 같은 이유로 '같지 않다' 로 본다.
    """
    r = s.get(f"{BASE}/{edit_id}/listings/{lang}/{image_type}")
    if r.status_code != 200:
        return False
    images = r.json().get("images", [])
    if len(images) != len(files):
        return False
    shas = [img.get("sha1", "") for img in images]
    if not all(shas):
        return False
    return shas == [sha1_of(p) for p in files]


def listed_as_changed(changed: set, folder: Path, path: Path) -> bool:
    """--changed 목록에 이 파일이 있나. 저장소 기준(store-listing/ko-KR/…)과 폴더 기준(ko-KR/…) 둘 다 받는다."""
    rel = path.relative_to(folder).as_posix()
    return any(entry == rel or entry.endswith("/" + rel) for entry in changed)


def needs_upload(s: AuthorizedSession, edit_id: str, lang: str, image_type: str,
                 files: list, folder: Path, changed: set) -> tuple:
    """이 이미지 칸을 다시 올려야 하나, 그리고 그 이유. (올릴까, 이유)"""
    if changed is not None and any(listed_as_changed(changed, folder, f) for f in files):
        return True, "이번 커밋에서 바뀜"
    if same_on_play(s, edit_id, lang, image_type, files):
        return False, "올라가 있는 것과 같음"
    if changed is None:
        return True, "Play 에 없거나 다름"
    return True, "이번 커밋에는 안 바뀌었지만 Play 와 다름 (맞춤)"


def upload_single_image(s: AuthorizedSession, edit_id: str, lang: str, path: Path, image_type: str) -> int:
    """
    한 장짜리 이미지 칸(icon / featureGraphic)을 바꾼다. 기존 것을 지우고 새 파일을 올린다.
    icon 은 512×512 PNG, featureGraphic 은 1024×500 PNG 여야 한다 (크기가 다르면 API 가 400 으로 거절한다).
    """
    r = s.delete(f"{BASE}/{edit_id}/listings/{lang}/{image_type}")
    if r.status_code not in (200, 204):
        sys.exit(f"::error::{lang} {image_type} 기존 이미지 삭제 실패 {r.status_code}: {r.text[:300]}")
    upload = (f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
              f"/edits/{edit_id}/listings/{lang}/{image_type}?uploadType=media")
    r = s.post(upload, data=path.read_bytes(), headers={"Content-Type": "image/png"})
    if r.status_code != 200:
        sys.exit(f"::error::{path.name} 업로드 실패 {r.status_code}: {r.text[:300]}")
    print(f"  올림 [{image_type}]: {path.name} ({path.stat().st_size // 1024}KB)")
    return 1


def upload_screenshots(s: AuthorizedSession, edit_id: str, lang: str, shots: list, image_type: str) -> int:
    """
    *.png 를 파일명 순서대로 image_type(phoneScreenshots / sevenInchScreenshots / tenInchScreenshots) 칸에 올린다.
    기존 것은 전부 지우고 다시 올린다.
    (Play 는 순서를 바꾸는 API 가 없어서, 순서를 보장하려면 지우고 차례로 올리는 수밖에 없다.
     그래서 한 장만 바뀌어도 그 칸은 전부 다시 올린다 — 다른 칸은 건드리지 않는다)
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


def load_changed(path_arg: str) -> set:
    """
    --changed 로 받은 파일 목록. 주지 않았으면 None(= 모르니 Play 의 해시와 비교해 판단한다).
    비어 있는 목록이면 '아무것도 안 바뀌었다' 고 단정하지 않고 None 으로 둔다.
    """
    if not path_arg:
        return None
    path = Path(path_arg)
    if not path.exists():
        sys.exit(f"::error::--changed 로 준 {path} 가 없다")
    lines = {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()}
    return lines or None


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    sa_path, root = sys.argv[1], Path(sys.argv[2])
    check_only = "--check" in sys.argv
    changed_path = None
    if "--changed" in sys.argv:
        i = sys.argv.index("--changed")
        if i + 1 >= len(sys.argv):
            sys.exit("::error::--changed 뒤에 파일 경로가 없다")
        changed_path = sys.argv[i + 1]
    changed_files = load_changed(changed_path)

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
        if changed_files is not None:
            print(f"이번 커밋에서 바뀐 store-listing 파일 {len(changed_files)}개로 이미지를 판단한다.")

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
            icon = folder / "icon.png"
            feature = folder / "feature-graphic.png"
            print(f"[{lang}] 휴대전화 스크린샷 {len(shots)}장 / 태블릿 {len(tablet)}장 / "
                  f"아이콘 {'있음' if icon.exists() else '없음'} / 그래픽 {'있음' if feature.exists() else '없음'}")

            # 앱 아이콘(512)·그래픽 이미지(1024×500)·스크린샷. 파일이 있고, 실제로 바뀐 칸만 올린다.
            # 아이콘은 tools/make_icons.py 가 앱 안의 벡터와 같은 원본에서 뽑아 둔다.
            assets = []
            if icon.exists():
                assets.append(("icon", [icon], "아이콘"))
            if feature.exists():
                assets.append(("featureGraphic", [feature], "그래픽 이미지"))
            if shots:
                assets.append(("phoneScreenshots", shots, "휴대전화 스크린샷"))
            if tablet:
                # 7인치·10인치 칸에 같은 이미지를 올린다. 따로 만들 만큼 레이아웃이 다르지 않다.
                assets.append(("sevenInchScreenshots", tablet, "태블릿 스크린샷(7인치)"))
                assets.append(("tenInchScreenshots", tablet, "태블릿 스크린샷(10인치)"))
            for image_type, files, label in assets:
                upload_needed, why = needs_upload(s, edit_id, lang, image_type, files, root, changed_files)
                if not upload_needed:
                    print(f"[{lang}] {label} 그대로 둔다 — {why}")
                    continue
                if check_only:
                    print(f"[{lang}] {label} 올릴 것 — {why}")
                    continue
                if len(files) == 1 and image_type in ("icon", "featureGraphic"):
                    changed += upload_single_image(s, edit_id, lang, files[0], image_type)
                else:
                    changed += upload_screenshots(s, edit_id, lang, files, image_type)

            if not same:
                if check_only:
                    print(f"[{lang}] 문안 올릴 것 — 변경됨")
                    continue
                body["language"] = lang
                r = s.put(f"{BASE}/{edit_id}/listings/{lang}", json=body)
                if r.status_code != 200:
                    if r.status_code == 403:
                        print("::error::등록정보 쓰기 권한이 없다. Play Console → 사용자 및 권한에서 "
                              "이 서비스 계정에 '스토어 등록정보 관리' 권한을 추가해야 한다")
                    sys.exit(f"::error::{lang} 등록정보 쓰기 실패 {r.status_code}: {r.text[:300]}")
                changed += 1
                print(f"[{lang}] 문안 올림")

        if check_only:
            print("확인만 했다. 바꾸지 않았다.")
            return
        if changed == 0:
            print("바뀐 등록정보가 없어 커밋하지 않는다. (이미지는 올라가 있는 것과 같아 다시 올리지 않았다)")
            return

        r = s.post(f"{BASE}/{edit_id}:commit")
        if r.status_code != 200:
            if r.status_code == 403:
                # 2026-10-02 실제로 겪었다: 쓰기(PUT)는 통과하고 커밋에서 PERMISSION_DENIED.
                # 권한 검사는 커밋 시점에 한다. "출시 관리"만으로는 등록정보를 바꾸지 못한다.
                print("::error::등록정보 커밋 권한이 없다. Play Console → 사용자 및 권한 → 이 서비스 계정 → "
                      "앱 권한에서 '스토어 등록정보 관리'(Manage store presence)를 켜야 한다")
            sys.exit(f"::error::커밋 실패 {r.status_code}: {r.text[:500]}")
        print(f"등록정보 {changed}건 반영 완료. Google 검토 후 스토어에 보인다.")
        edit_id = None  # 커밋됐으니 지우지 않는다
    finally:
        if edit_id:
            s.delete(f"{BASE}/{edit_id}")


if __name__ == "__main__":
    main()
