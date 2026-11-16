"""
앱 아이콘 래스터를 한 원본에서 뽑는다.

벡터(app/src/main/res/drawable/ic_launcher_foreground.xml)와 같은 좌표로 그려서
  - app/src/main/ic_launcher-playstore.png            512  플레이 콘솔 "앱 아이콘" (모서리는 Play 가 깎는다)
  - store-listing/ko-KR/icon.png                      512  deploy.yml 의 listing 잡이 Play 에 올리는 사본
  - hosting/img/icon.png                              192  웹 파비콘 (모서리 둥글게)
  - app/src/main/res/mipmap-*/ic_launcher.webp         48~192  어댑티브 이전 런처용 (minSdk 26 이라 실제로는 안 쓰이지만 네 곳을 같게 둔다)
  - app/src/main/res/mipmap-*/ic_launcher_round.webp   48~192

사용: python tools/make_icons.py   (저장소 루트에서. Pillow 필요)

좌표계는 어댑티브 108dp 캔버스다. 런처·플레이·웹에는 그중 중앙 72dp(마스크가 보여주는 영역)만 쓴다.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
INK = (0x06, 0x30, 0x3F, 255)
MINT = (0x5F, 0xE1, 0xB0, 255)
CORAL = (0xFF, 0x4D, 0x2E, 255)
WHITE = (255, 255, 255, 255)

SS = 8  # 슈퍼샘플 배율


def bezier(p0, p1, p2, p3, n=40):
    pts = []
    for i in range(n + 1):
        t = i / n
        x = (1 - t) ** 3 * p0[0] + 3 * (1 - t) ** 2 * t * p1[0] + 3 * (1 - t) * t ** 2 * p2[0] + t ** 3 * p3[0]
        y = (1 - t) ** 3 * p0[1] + 3 * (1 - t) ** 2 * t * p1[1] + 3 * (1 - t) * t ** 2 * p2[1] + t ** 3 * p3[1]
        pts.append((x, y))
    return pts


# ic_launcher_foreground.xml 의 불꽃 경로와 같은 점들
FLAME_OUTER = (
    bezier((54, 34), (58, 42), (67, 47), (67, 58))
    + bezier((67, 58), (67, 68), (61, 74), (54, 76))
    + bezier((54, 76), (47, 74), (41, 68), (41, 58))
    + bezier((41, 58), (41, 47), (50, 42), (54, 34))
)
FLAME_INNER = (
    bezier((55.5, 46), (59.5, 52), (61.5, 56.5), (60.5, 62.5))
    + bezier((60.5, 62.5), (59.5, 67.5), (56.5, 70.5), (54, 72.5))
    + bezier((54, 72.5), (55, 68), (56, 63.5), (54.2, 58.5))
    + bezier((54.2, 58.5), (52.8, 54.5), (54, 50), (55.5, 46))
)


def draw_mark(size_px: int, background: bool = True) -> Image.Image:
    """108dp 캔버스 전체를 size_px 로 그린다 (슈퍼샘플 후 축소)."""
    s = size_px * SS / 108.0
    im = Image.new("RGBA", (size_px * SS, size_px * SS), INK if background else (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def P(x, y):
        return (x * s, y * s)

    # 링: 중심 (54,54) 반지름 26 굵기 4.5, 345° → 285° (시계방향 300°)
    r = 26 * s
    w = 4.5 * s
    cx, cy = P(54, 54)
    # PIL 의 arc 는 bbox 바깥선에서 안쪽으로 width 만큼 칠한다. 중심선이 r 이 되도록 bbox 를 w/2 키운다
    ro = r + w / 2
    d.arc([cx - ro, cy - ro, cx + ro, cy + ro], start=345, end=285, fill=MINT, width=int(round(w)))
    # 둥근 끝. 양 끝점에 원을 찍는다
    import math
    for ang in (345, 285):
        ex = cx + r * math.cos(math.radians(ang))
        ey = cy + r * math.sin(math.radians(ang))
        d.ellipse([ex - w / 2, ey - w / 2, ex + w / 2, ey + w / 2], fill=MINT)

    # 코랄 점
    dx, dy = P(73.4, 34.6)
    dr = 3.6 * s
    d.ellipse([dx - dr, dy - dr, dx + dr, dy + dr], fill=CORAL)

    # 불꽃 (안쪽 결은 배경색으로 파낸다)
    d.polygon([P(x, y) for x, y in FLAME_OUTER], fill=WHITE)
    d.polygon([P(x, y) for x, y in FLAME_INNER], fill=INK if background else (0, 0, 0, 0))

    return im.resize((size_px, size_px), Image.LANCZOS)


def center_crop_72(full: Image.Image) -> Image.Image:
    """108 캔버스에서 마스크가 보여주는 중앙 72 만 남긴다."""
    n = full.size[0]
    a = int(round(n * 18 / 108))
    b = int(round(n * 90 / 108))
    return full.crop((a, a, b, b))


def rounded(im: Image.Image, radius_ratio: float) -> Image.Image:
    n = im.size[0]
    mask = Image.new("L", (n * SS, n * SS), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, n * SS - 1, n * SS - 1], radius=int(n * SS * radius_ratio), fill=255)
    mask = mask.resize((n, n), Image.LANCZOS)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def circle(im: Image.Image) -> Image.Image:
    n = im.size[0]
    mask = Image.new("L", (n * SS, n * SS), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, n * SS - 1, n * SS - 1], fill=255)
    mask = mask.resize((n, n), Image.LANCZOS)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def main() -> None:
    # 큰 원본 하나에서 전부 뽑는다 (108 캔버스 = 1536px → 중앙 72 = 1024px)
    full = draw_mark(1536)
    core = center_crop_72(full)  # 1024

    play = core.resize((512, 512), Image.LANCZOS).convert("RGB")
    play.save(ROOT / "app/src/main/ic_launcher-playstore.png", optimize=True)
    (ROOT / "store-listing/ko-KR").mkdir(parents=True, exist_ok=True)
    play.save(ROOT / "store-listing/ko-KR/icon.png", optimize=True)

    web = rounded(core.resize((192, 192), Image.LANCZOS), 0.22)
    web.save(ROOT / "hosting/img/icon.png", optimize=True)

    # legacy mipmap. 모서리는 런처가 깎지 않으므로 여기서 깎는다
    for folder, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        d = ROOT / f"app/src/main/res/mipmap-{folder}"
        base = core.resize((px, px), Image.LANCZOS)
        rounded(base, 0.18).save(d / "ic_launcher.webp", lossless=True)
        circle(base).save(d / "ic_launcher_round.webp", lossless=True)
        old = d / "ic_launcher_foreground.webp"
        if old.exists():
            old.unlink()  # 전경은 벡터(@drawable)로 바꿨으니 비트맵은 지운다
    print("done")


if __name__ == "__main__":
    main()
