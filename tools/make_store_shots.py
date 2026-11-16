"""
플레이 스토어 스크린샷·그래픽 이미지를 합성한다.

입력: tools/store-captures/NN-<이름>.png  (폰 원본 캡처. 상태바 포함. 어떤 해상도든 됨)
      tools/store-captures/captions.txt     (한 줄에 "NN|제목|부제". NN 은 파일 접두어)
출력: store-listing/ko-KR/screenshots/NN.png          1080×1920 (휴대전화)
      store-listing/ko-KR/screenshots-tablet/NN.png   1200×1920 (7·10인치 칸에 같은 파일)
      store-listing/ko-KR/feature-graphic.png         1024×500  (그래픽 이미지)

사용: python tools/make_store_shots.py   (저장소 루트에서. Pillow 필요. 한글 폰트는 Windows 의 malgun.ttf)

규칙 (2026-11-16 리디자인 기획서 4.2)
- 배경은 민트와 딥틸을 번갈아 쓴다
- 장마다 제목 1줄 + 부제 1줄, 폰 화면이 세로의 60% 이상, 상태바 제거, 기능 1개씩
- Play 규격: 2~8장, 16:9~9:16, 각 변 320~3840px

캡처 원본은 저장소에 넣지 않는다 (개인 기록이 들어 있다). 이 스크립트와 captions.txt 만 남긴다.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
CAPTURES = ROOT / "tools/store-captures"
OUT_PHONE = ROOT / "store-listing/ko-KR/screenshots"
OUT_TABLET = ROOT / "store-listing/ko-KR/screenshots-tablet"

INK = (0x06, 0x30, 0x3F)
MINT = (0x5F, 0xE1, 0xB0)
CORAL = (0xFF, 0x4D, 0x2E)
WHITE = (255, 255, 255)
SUB_ON_MINT = (0x24, 0x5C, 0x6C)
SUB_ON_INK = (0xBF, 0xE9, 0xDA)

FONT_BOLD = "C:/Windows/Fonts/malgunbd.ttf"
FONT_REG = "C:/Windows/Fonts/malgun.ttf"

# 상태바 높이 비율. 캡처 폭 1440 기준 상단 약 110px 가 상태바다 (폰마다 다르면 여기만 고친다)
STATUS_BAR_RATIO = 110 / 1440


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def compose(capture: Image.Image, title: str, subtitle: str, dark: bool, size=(1080, 1920)) -> Image.Image:
    W, H = size
    bg = INK if dark else MINT
    fg = WHITE if dark else INK
    sub = SUB_ON_INK if dark else SUB_ON_MINT
    im = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(im)

    # 글
    t_font = font(FONT_BOLD, int(W * 0.062))
    s_font = font(FONT_REG, int(W * 0.034))
    top = int(H * 0.07)
    tw = d.textlength(title, font=t_font)
    d.text(((W - tw) / 2, top), title, font=t_font, fill=fg)
    sw = d.textlength(subtitle, font=s_font)
    d.text(((W - sw) / 2, top + int(W * 0.085)), subtitle, font=s_font, fill=sub)

    # 폰 화면: 상태바 잘라내고, 세로의 72% 를 차지하게 맞춘다. 아래로 흘러 나가게 두어 "계속된다"는 느낌을 준다
    cw, ch = capture.size
    cut = int(ch * STATUS_BAR_RATIO) if cw > 0 else 0
    shot = capture.crop((0, cut, cw, ch))
    target_h = int(H * 0.80)
    scale = target_h / shot.height
    shot = shot.resize((int(shot.width * scale), target_h), Image.LANCZOS)
    # 폰 테두리
    pad = int(W * 0.02)
    frame_w, frame_h = shot.width + pad * 2, shot.height + pad * 2
    frame = Image.new("RGB", (frame_w, frame_h), (0x0B, 0x2A, 0x33) if not dark else (0x1C, 0x4A, 0x56))
    frame.paste(shot, (pad, pad))
    radius = int(W * 0.07)
    mask = rounded_mask((frame_w, frame_h), radius)
    x = (W - frame_w) // 2
    y = int(H * 0.215)
    im.paste(frame, (x, y), mask)
    # 아래쪽은 캔버스 밖으로 나간다 (crop 은 paste 가 알아서 한다)
    return im


def feature_graphic(icon: Image.Image) -> Image.Image:
    """1024×500. 왼쪽에 아이콘, 오른쪽에 앱 이름과 한 줄 설명"""
    W, H = 1024, 500
    im = Image.new("RGB", (W, H), INK)
    d = ImageDraw.Draw(im)
    # 오른쪽 아래 민트 원 장식
    d.ellipse([W - 420, H - 160, W + 200, H + 460], fill=(0x0F, 0x3D, 0x4C))
    ic = icon.resize((260, 260), Image.LANCZOS)
    mask = rounded_mask((260, 260), 58)
    im.paste(ic, (96, (H - 260) // 2), mask)
    name_font = font(FONT_BOLD, 92)
    tag_font = font(FONT_REG, 36)
    d.text((420, 150), "DailyNet", font=name_font, fill=WHITE)
    d.text((424, 262), "먹고 움직인 만큼, 순칼로리로 정산", font=tag_font, fill=MINT)
    d.text((424, 316), "사진 한 장으로 칼로리 · AI 리포트 · 월 정산", font=font(FONT_REG, 26), fill=SUB_ON_INK)
    return im


def main() -> None:
    OUT_PHONE.mkdir(parents=True, exist_ok=True)
    OUT_TABLET.mkdir(parents=True, exist_ok=True)

    icon = Image.open(ROOT / "store-listing/ko-KR/icon.png").convert("RGB")
    feature_graphic(icon).save(ROOT / "store-listing/ko-KR/feature-graphic.png", optimize=True)
    print("feature-graphic.png")

    captions_path = CAPTURES / "captions.txt"
    if not captions_path.exists():
        print(f"{captions_path} 가 없어 스크린샷은 건너뛴다")
        return
    rows = [l.split("|") for l in captions_path.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]

    # 기존 것을 전부 지우고 다시 만든다 (장수가 줄어도 옛 파일이 남지 않게)
    for p in list(OUT_PHONE.glob("*.png")) + list(OUT_TABLET.glob("*.png")):
        p.unlink()

    for i, (num, title, subtitle) in enumerate(rows):
        src = next(CAPTURES.glob(f"{num}-*.png"), None)
        if src is None:
            raise SystemExit(f"{num}-*.png 캡처가 없다")
        cap = Image.open(src).convert("RGB")
        dark = i % 2 == 1
        compose(cap, title, subtitle, dark, (1080, 1920)).save(OUT_PHONE / f"{num}.png", optimize=True)
        compose(cap, title, subtitle, dark, (1200, 1920)).save(OUT_TABLET / f"{num}.png", optimize=True)
        print(f"{num}.png  {title}")


if __name__ == "__main__":
    main()
