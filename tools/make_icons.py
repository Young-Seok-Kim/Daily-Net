"""
앱 아이콘 래스터를 사용자가 고른 원본 한 장에서 뽑는다.

원본: tools/assets/dailynet-icon-512.png (2안 — 딥틸 바탕·민트 링·코랄 점·흰 불꽃, 512×512)

원본은 AI 로 그린 래스터라 배경에 미세한 노이즈가 섞여 있다. 그래서 모양을 다시 그리지 않고,
"배경색 + 도형색 3가지"의 선형 혼합으로 풀어(unmix) 덮임률(coverage)을 구해 알파로 쓴다.
색은 브랜드 값으로 다시 칠하므로 결과물에는 노이즈가 남지 않는다.

만들어 내는 것
  - app/src/main/ic_launcher-playstore.png             512  플레이 콘솔 "앱 아이콘" (모서리는 Play 가 깎는다)
  - store-listing/ko-KR/icon.png                       512  deploy.yml 의 listing 잡이 Play 에 올리는 사본
  - hosting/img/icon.png                               192  웹 파비콘 (모서리 둥글게)
  - app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png   108dp 캔버스(density 4x = 432px) 전경 비트맵
  - app/src/main/res/drawable-xxxhdpi/ic_launcher_monochrome.png   테마 아이콘용 단색 실루엣
  - app/src/main/res/mipmap-*/ic_launcher.webp         48~192  어댑티브 이전 런처용 (minSdk 26 이라 실제로는 안 쓰이지만 네 곳을 같게 둔다)
  - app/src/main/res/mipmap-*/ic_launcher_round.webp   48~192

어댑티브 아이콘은 배경(단색 #013042, drawable/ic_launcher_background.xml) + 전경(위 PNG)으로 그려진다.
전경은 108dp 캔버스의 중앙 72dp(런처 마스크가 보여주는 영역)에 원본 비율 그대로 앉힌다.
원본에서 링이 한 변의 65.8% 를 차지하므로, 마스크 안에서도 같은 비율(72dp 의 65.8% = 47.4dp)이 된다.

사용: python tools/make_icons.py   (저장소 루트에서. Pillow·numpy 필요)
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
MASTER = ROOT / "tools/assets/dailynet-icon-512.png"

# 원본에서 읽어낸 색. 배경은 실측값, 도형 색은 브랜드 팔레트에 맞춰 정리한 값이다.
BG = (0x01, 0x30, 0x42)
MINT = (0x70, 0xE4, 0xAD)
CORAL = (0xFC, 0x57, 0x40)
WHITE = (0xFD, 0xFD, 0xFD)

SS = 4  # 마스크(둥근 모서리·원) 슈퍼샘플 배율


def coverage_and_label(rgb: np.ndarray):
    """
    배경색과 도형색 3가지의 선형 혼합으로 푼다.

    픽셀 p = c*mark + (1-c)*bg  (c = 덮임률). 후보색마다 c 를 구하고,
    남는 오차가 가장 작은 후보를 그 픽셀의 도형으로 본다.
    돌려주는 값: (알파 0~1 float 배열, RGB float 배열)
    """
    p = rgb.astype(np.float64)
    bg = np.array(BG, dtype=np.float64)
    out_a = np.zeros(p.shape[:2], dtype=np.float64)
    out_rgb = np.zeros(p.shape, dtype=np.float64)

    best_res = np.full(p.shape[:2], np.inf)
    for color in (MINT, CORAL, WHITE):
        v = np.array(color, dtype=np.float64) - bg          # 도형 방향 벡터
        d = p - bg                                          # 픽셀 - 배경
        c = (d @ v) / (v @ v)                               # 최소제곱 덮임률
        c = np.clip(c, 0.0, 1.0)
        res = np.linalg.norm(d - c[..., None] * v, axis=2)  # 이 후보로 설명되지 않는 잔차
        # 배경에 가까운 픽셀은 어느 후보든 잔차가 작다. 도형이 실제로 있는 픽셀만 이긴다.
        take = (res < best_res) & (c > 0.02)
        best_res = np.where(take, res, best_res)
        out_a = np.where(take, c, out_a)
        out_rgb = np.where(take[..., None], np.array(color, dtype=np.float64), out_rgb)
    return out_a, out_rgb


def build_layers():
    """원본에서 (마크 RGBA, 마크 단색 실루엣 RGBA, 원본 RGB) 를 만든다."""
    src = Image.open(MASTER).convert("RGB")
    rgb = np.asarray(src)
    a, col = coverage_and_label(rgb)

    mark = np.dstack([col, a * 255.0]).astype(np.uint8)
    mark_im = Image.fromarray(mark, "RGBA")

    # 실루엣: 알파만 남기고 흰색으로 (테마 아이콘은 시스템이 한 색으로 물들인다)
    sil = np.dstack([np.full(a.shape, 255, np.float64)] * 3 + [a * 255.0]).astype(np.uint8)
    sil_im = Image.fromarray(sil, "RGBA")

    bbox = Image.fromarray((a > 0.35).astype(np.uint8) * 255).getbbox()
    return mark_im, sil_im, src, bbox


def on_canvas(layer: Image.Image, canvas_px: int) -> Image.Image:
    """
    마크를 108dp 캔버스(canvas_px)의 중앙 72dp 안에 원본 비율 그대로 앉힌다.
    마크의 바깥 상자를 정확히 중앙에 두어, 원본에서 살짝 치우쳐 그려진 것도 바로잡는다.
    """
    box_px = int(round(canvas_px * 72 / 108))          # 마스크가 보여주는 영역
    scale = box_px / layer.size[0]                      # 원본 512 → 72dp
    w = int(round(layer.size[0] * scale))
    h = int(round(layer.size[1] * scale))
    placed = layer.resize((w, h), Image.LANCZOS)
    # 마크 상자의 중심이 캔버스 중심에 오도록
    bbox = placed.getbbox()
    dx = (w - (bbox[2] + bbox[0])) // 2
    dy = (h - (bbox[3] + bbox[1])) // 2

    out = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    out.paste(placed, ((canvas_px - w) // 2 + dx, (canvas_px - h) // 2 + dy), placed)
    return out


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
    mark, sil, src, bbox = build_layers()
    print(f"원본 {src.size[0]}×{src.size[1]}  마크 상자 {bbox}")

    # --- 플레이 스토어 아이콘 (512, 불투명) ---
    play = src.convert("RGBA").resize((512, 512), Image.LANCZOS)
    play.putalpha(255)
    play.save(ROOT / "app/src/main/ic_launcher-playstore.png", optimize=True)
    store = ROOT / "store-listing/ko-KR"
    store.mkdir(parents=True, exist_ok=True)
    play.save(store / "icon.png", optimize=True)

    # --- 웹 파비콘 (192, 모서리 둥글게) ---
    web = rounded(src.convert("RGBA").resize((192, 192), Image.LANCZOS), 0.22)
    web.save(ROOT / "hosting/img/icon.png", optimize=True)

    # --- 어댑티브 아이콘 전경·모노크롬 (108dp 캔버스, density 4x = 432px) ---
    # 해상도 폴더를 xxxhdpi 로 두면 432px 가 108dp 로 해석된다. 런처가 큰 아이콘을 그릴 때도 충분하다.
    dxxx = ROOT / "app/src/main/res/drawable-xxxhdpi"
    dxxx.mkdir(parents=True, exist_ok=True)
    on_canvas(mark, 432).save(dxxx / "ic_launcher_foreground.png", optimize=True)
    on_canvas(sil, 432).save(dxxx / "ic_launcher_monochrome.png", optimize=True)
    # 예전 벡터 도형은 지운다 (같은 이름의 PNG 와 공존할 수 없다)
    for old in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"):
        p = ROOT / "app/src/main/res/drawable" / old
        if p.exists():
            p.unlink()

    # --- legacy mipmap ---
    for folder, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        d = ROOT / f"app/src/main/res/mipmap-{folder}"
        base = src.convert("RGBA").resize((px, px), Image.LANCZOS)
        rounded(base, 0.18).save(d / "ic_launcher.webp", lossless=True)
        circle(base).save(d / "ic_launcher_round.webp", lossless=True)
    print("done")


if __name__ == "__main__":
    main()
