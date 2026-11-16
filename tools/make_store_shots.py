"""
플레이 스토어 스크린샷·그래픽 이미지를 합성한다.

입력
  tools/store-captures/NN-<이름>.png   앱 화면 목업 (상태바까지 포함한 "전체 화면". 1236×2676 = 412×892dp @3x)
  tools/store-captures/captions.txt    한 줄에 "NN|제목|부제"
  store-listing/ko-KR/icon.png         그래픽 이미지에 쓰는 앱 아이콘
출력
  store-listing/ko-KR/screenshots/NN.png          1080×1920 (휴대전화)
  store-listing/ko-KR/screenshots-tablet/NN.png   1200×1920 (7·10인치 칸에 같은 파일)
  store-listing/ko-KR/feature-graphic.png         1024×500  (그래픽 이미지)

사용: python tools/make_store_shots.py      (저장소 루트에서. Pillow 필요)
      폰트는 tools/fonts/Pretendard-*.otf 를 찾고, 없으면 DN_FONT_DIR 환경변수를 본다.
      (Pretendard 는 저장소에 넣지 않는다 — 스크린샷 PNG 만 커밋한다)

규칙 (2026-11-16 리디자인 기획서 4.2)
- 장마다 제목 1줄 + 부제 1줄, 폰 화면은 잘리지 않고 통째로 보인다 (글자·행이 반쯤 잘리는 일이 없다)
- 배경은 딥틸과 밝은 민트를 번갈아 쓴다. 배경 그라데이션 + 아이콘 링을 흐리게 깐 결
- Play 규격: 2~8장, 16:9~9:16, 각 변 320~3840px, 32비트 PNG
"""
import os
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
CAPTURES = ROOT / "tools/store-captures"
OUT_PHONE = ROOT / "store-listing/ko-KR/screenshots"
OUT_TABLET = ROOT / "store-listing/ko-KR/screenshots-tablet"
ICON = ROOT / "store-listing/ko-KR/icon.png"

INK = (0x06, 0x30, 0x3F)
DEEP = (0x03, 0x1E, 0x29)
TEAL = (0x0B, 0x3E, 0x50)
MINT = (0x5F, 0xE1, 0xB0)
MINT_SOFT = (0xA9, 0xF0, 0xD6)
CORAL = (0xFF, 0x4D, 0x2E)
WHITE = (0xFF, 0xFF, 0xFF)
CREAM = (0xF2, 0xFB, 0xF6)
PALE = (0xDA, 0xF2, 0xE7)

WEIGHTS = {"r": "Regular", "m": "Medium", "sb": "SemiBold", "b": "Bold", "eb": "ExtraBold"}
_face_cache = {}


def _font_dir() -> Path:
    env = os.environ.get("DN_FONT_DIR")
    if env:
        return Path(env)
    return ROOT / "tools/fonts"


def font(size: int, weight: str = "r") -> ImageFont.FreeTypeFont:
    key = (size, weight)
    if key not in _face_cache:
        path = _font_dir() / f"Pretendard-{WEIGHTS[weight]}.otf"
        if not path.exists():
            raise SystemExit(
                f"{path} 가 없다. Pretendard OTF 를 tools/fonts/ 에 두거나 DN_FONT_DIR 로 폴더를 지정한다.")
        _face_cache[key] = ImageFont.truetype(str(path), size)
    return _face_cache[key]


def text_w(s: str, size: int, weight: str = "r") -> float:
    return font(size, weight).getlength(s)


def draw_text(im: Image.Image, xy, s, size, weight="r", fill=WHITE, anchor="lm"):
    ImageDraw.Draw(im).text(xy, s, font=font(size, weight), fill=fill, anchor=anchor)


def fit_size(s: str, weight: str, max_w: int, start: int, minimum: int = 40) -> int:
    size = start
    while size > minimum and text_w(s, size, weight) > max_w:
        size -= 2
    return size


# ---------------------------------------------------------------- 배경
def vgrad(w: int, h: int, top, bottom) -> Image.Image:
    ramp = Image.new("RGB", (1, h))
    px = ramp.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
    return ramp.resize((w, h), Image.BILINEAR)


def radial_glow(base: Image.Image, cx: float, cy: float, r: float, color, alpha: float):
    w, h = base.size
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    a = np.clip(1 - d, 0, 1) ** 2 * alpha
    layer = Image.new("RGB", (w, h), color)
    base.paste(layer, (0, 0), Image.fromarray((a * 255).astype(np.uint8)))


def ring_texture(base: Image.Image, cx, cy, radii, color, alpha, width):
    """아이콘의 링 모티프를 배경에 아주 옅게 깐다"""
    w, h = base.size
    layer = Image.new("L", (w * 2, h * 2), 0)
    d = ImageDraw.Draw(layer)
    for r in radii:
        d.arc([cx * 2 - r * 2, cy * 2 - r * 2, cx * 2 + r * 2, cy * 2 + r * 2],
              start=110, end=250, fill=int(alpha * 255), width=width * 2)
    layer = layer.resize((w, h), Image.LANCZOS)
    base.paste(Image.new("RGB", (w, h), color), (0, 0), layer)


# ---------------------------------------------------------------- 도형
def rounded_mask(size, radius) -> Image.Image:
    k = 4
    m = Image.new("L", (size[0] * k, size[1] * k), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] * k - 1, size[1] * k - 1], radius=radius * k, fill=255)
    return m.resize(size, Image.LANCZOS)


def circle_mask(size) -> Image.Image:
    k = 4
    m = Image.new("L", (size[0] * k, size[1] * k), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size[0] * k - 1, size[1] * k - 1], fill=255)
    return m.resize(size, Image.LANCZOS)


def shadow(base: Image.Image, box, radius, blur=40, alpha=110, dy=24, color=(0, 0, 0)):
    """box=(x,y,w,h) 자리에 번진 그림자를 깐다"""
    x, y, w, h = box
    pad = blur * 3
    m = Image.new("L", (w + pad * 2, h + pad * 2), 0)
    ImageDraw.Draw(m).rounded_rectangle([pad, pad + dy, pad + w, pad + h + dy], radius=radius, fill=255)
    m = m.filter(ImageFilter.GaussianBlur(blur / 1.6))
    m = m.point(lambda v: int(v * alpha / 255))
    base.paste(Image.new("RGB", m.size, color), (x - pad, y - pad), m)


def phone(screen: Image.Image, target_h: int, body_color=(0x0A, 0x2A, 0x34)) -> Image.Image:
    """화면 캡처를 폰 본체에 넣는다. 모서리 밖은 투명."""
    w = round(screen.width * target_h / screen.height)
    scr = screen.convert("RGB").resize((w, target_h), Image.LANCZOS)
    bezel = max(8, round(w * 0.016))
    body_w, body_h = w + bezel * 2, target_h + bezel * 2
    out = Image.new("RGBA", (body_w, body_h), (0, 0, 0, 0))
    body = Image.new("RGB", (body_w, body_h), body_color)
    out.paste(body, (0, 0), rounded_mask((body_w, body_h), round(body_w * 0.098)))
    out.paste(scr, (bezel, bezel), rounded_mask((w, target_h), round(w * 0.082)))
    # 본체 가장자리 하이라이트
    edge = Image.new("RGBA", (body_w, body_h), (0, 0, 0, 0))
    ImageDraw.Draw(edge).rounded_rectangle(
        [0, 0, body_w - 1, body_h - 1], radius=round(body_w * 0.098), outline=(255, 255, 255, 46), width=2)
    return Image.alpha_composite(out, edge)


# ---------------------------------------------------------------- 슬라이드
THEMES = {
    "dark": dict(top=(0x05, 0x2A, 0x38), bottom=(0x02, 0x1B, 0x26), title=WHITE, sub=MINT_SOFT,
                 glow=MINT, body=(0x08, 0x26, 0x30), shadow_alpha=150),
    "light": dict(top=(0xF7, 0xFD, 0xFA), bottom=(0xD9, 0xF1, 0xE6), title=INK, sub=(0x1F, 0x6B, 0x5A),
                  glow=WHITE, body=(0x0A, 0x2A, 0x34), shadow_alpha=70),
}


def slide(num: str, title: str, subtitle: str, screen_path: Path, theme: str, size=(1080, 1920)) -> Image.Image:
    W, H = size
    t = THEMES[theme]
    im = vgrad(W, H, t["top"], t["bottom"])
    radial_glow(im, W * 0.5, H * 0.60, W * 0.85, t["glow"], 0.10 if theme == "dark" else 0.55)
    ring_texture(im, W * 0.5, H * 0.62, (W * 0.62, W * 0.52), t["glow"], 0.05 if theme == "dark" else 0.16, 3)

    # 제목
    ts = fit_size(title, "eb", int(W * 0.88), int(W * 0.064))
    draw_text(im, (W / 2, int(H * 0.088)), title, ts, "eb", t["title"], "mm")
    ss = min(int(W * 0.032), fit_size(subtitle, "m", int(W * 0.86), int(W * 0.032)))
    draw_text(im, (W / 2, int(H * 0.088) + int(ts * 1.05)), subtitle, ss, "m", t["sub"], "mm")

    # 폰: 아래 여백을 남기고 통째로 보이게 (위아래가 잘리지 않는다)
    phone_h = int(H * 0.775)
    scr = Image.open(screen_path).convert("RGB")
    ph = phone(scr, phone_h, t["body"])
    x = (W - ph.width) // 2
    y = int(H * 0.175)
    shadow(im, (x, y + int(phone_h * 0.02), ph.width, ph.height), round(ph.width * 0.098),
           blur=46, alpha=t["shadow_alpha"], dy=26, color=(0, 0, 0))
    im.paste(ph, (x, y), ph)
    return im


def feature_graphic() -> Image.Image:
    W, H = 1024, 500
    im = vgrad(W, H, (0x07, 0x35, 0x46), (0x02, 0x1B, 0x26))
    radial_glow(im, W * 0.18, H * 0.42, W * 0.55, MINT, 0.16)
    ring_texture(im, W * 0.20, H * 0.48, (300, 240), MINT, 0.10, 3)

    # 왼쪽: 아이콘 + 이름 + 한 줄 소개
    icon = Image.open(ICON).convert("RGB").resize((112, 112), Image.LANCZOS)
    shadow(im, (60, 84, 112, 112), 30, blur=26, alpha=120, dy=12)
    im.paste(icon, (60, 84), rounded_mask((112, 112), 30))
    draw_text(im, (60, 232), "DailyNet", 46, "eb", WHITE, "ls")
    draw_text(im, (60, 288), "먹고 움직인 만큼, 순칼로리로 정산", 27, "b", MINT, "ls")
    draw_text(im, (60, 330), "사진 한 장이면 끝, AI 칼로리 계산기", 19, "m", (0xB6, 0xE8, 0xD6), "ls")
    # 특징 알약 3개
    labels = ["사진으로 기록", "AI 식단 리포트", "월 정산 · 체중 추이"]
    px = 60
    for s in labels:
        w = text_w(s, 15, "sb") + 26
        d = ImageDraw.Draw(im)
        d.rounded_rectangle([px, 364, px + w, 400], radius=18, outline=(0x33, 0x6E, 0x66), width=1)
        draw_text(im, (px + w / 2, 383), s, 15, "sb", (0x8E, 0xC8, 0xB6), "mm")
        px += w + 10

    # 오른쪽: 앱 카드 2장 (홈 히어로 + 최근 추이). 잘리지 않게 통째로 앉힌다
    main = Image.open(CAPTURES / "01-home.png").convert("RGB")
    S = main.width / 412  # dp → px

    def crop_dp(x, y, w, h):
        return main.crop((int(x * S), int(y * S), int((x + w) * S), int((y + h) * S)))

    hero = crop_dp(20, 100, 372, 202)
    trend = crop_dp(20, 316, 372, 214)
    card_w = 372
    cx, cy = W - card_w - 56, 34
    for card, oy in ((hero, 0), (trend, 232)):
        h = round(card.height * card_w / card.width)
        c = card.resize((card_w, h), Image.LANCZOS)
        shadow(im, (cx, cy + oy, card_w, h), 26, blur=26, alpha=130, dy=14)
        im.paste(c, (cx, cy + oy), rounded_mask((card_w, h), 26))
    return im


def main() -> None:
    OUT_PHONE.mkdir(parents=True, exist_ok=True)
    OUT_TABLET.mkdir(parents=True, exist_ok=True)

    feature_graphic().save(ROOT / "store-listing/ko-KR/feature-graphic.png", optimize=True)
    print("feature-graphic.png")

    rows = []
    for line in (CAPTURES / "captions.txt").read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        rows.append(line.split("|"))

    for p in list(OUT_PHONE.glob("*.png")) + list(OUT_TABLET.glob("*.png")):
        p.unlink()

    for i, (num, title, subtitle) in enumerate(rows):
        src = next(CAPTURES.glob(f"{num}-*.png"), None)
        if src is None:
            raise SystemExit(f"{num}-*.png 목업이 없다")
        theme = "light" if i % 2 == 1 else "dark"
        slide(num, title, subtitle, src, theme, (1080, 1920)).save(OUT_PHONE / f"{num}.png", optimize=True)
        slide(num, title, subtitle, src, theme, (1200, 1920)).save(OUT_TABLET / f"{num}.png", optimize=True)
        print(f"{num}.png  [{theme}] {title}")


if __name__ == "__main__":
    main()
