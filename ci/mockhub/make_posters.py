#!/usr/bin/env python3
"""生成演示用的原创海报(渐变 + 剪影 + 标题),只在本地跑一次,结果提交到 fixtures/img。
用法: make_posters.py <媒体目录>   会在每部影片旁写 "<片名>.jpg",剧集写 "小城故事/poster.jpg"。"""
import random
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_B = "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc"
FONT_S = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
W, H = 600, 900

# 片名, 英文名, 年份, 上色, 下色, 剪影色, 随机种子, 输出路径
POSTERS = [
    ("星河漫游", "GALAXY DRIFT", "2021", (20, 30, 90), (240, 120, 80), (10, 14, 40), 1, "星河漫游 (2021).jpg"),
    ("夜航船", "NIGHT FERRY", "2019", (10, 50, 70), (246, 190, 120), (6, 24, 36), 2, "夜航船 (2019).jpg"),
    ("雾中列车", "TRAIN IN THE FOG", "2020", (50, 50, 85), (200, 180, 220), (26, 26, 48), 3, "雾中列车 (2020).jpg"),
    ("海边的钢琴", "PIANO BY THE SEA", "2022", (16, 80, 120), (250, 220, 165), (8, 40, 66), 4, "海边的钢琴 (2022).jpg"),
    ("北纬三十度", "30 DEGREES NORTH", "2018", (120, 40, 40), (245, 205, 140), (60, 20, 24), 5, "北纬三十度 (2018).jpg"),
    ("小城故事", "LITTLE TOWN", "剧集", (74, 44, 107), (240, 150, 140), (36, 20, 56), 6, "小城故事/poster.jpg"),
]


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ridge(rng, base, amp, n=9):
    pts = [(0, H)]
    for i in range(n + 1):
        x = W * i / n
        pts.append((x, base - rng.random() * amp))
    pts.append((W, H))
    return pts


def make(title, en, year, top, bottom, sil, seed, out):
    rng = random.Random(seed)
    img = Image.new("RGB", (W, H))
    px = ImageDraw.Draw(img)
    for y in range(H):
        px.line([(0, y), (W, y)], fill=lerp(top, bottom, (y / H) ** 1.3))
    # 发光的圆(日 / 月 / 星)
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    cx, cy, r = int(W * (0.3 + 0.4 * rng.random())), int(H * 0.52), 92
    for k in range(9, 0, -1):
        g.ellipse([cx - r - k * 18, cy - r - k * 18, cx + r + k * 18, cy + r + k * 18], fill=(255, 245, 225, 9))
    g.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 248, 232, 235))
    img = Image.alpha_composite(img.convert("RGBA"), glow.filter(ImageFilter.GaussianBlur(6))).convert("RGB")
    d = ImageDraw.Draw(img)
    # 三层剪影
    for i, (base, amp) in enumerate([(H * 0.70, 110), (H * 0.80, 90), (H * 0.90, 70)]):
        c = lerp(lerp(bottom, sil, 0.45), sil, i / 2)
        d.polygon(ridge(rng, base, amp), fill=c)
    # 标题
    f = ImageFont.truetype(FONT_B, 92 if len(title) <= 4 else 76)
    tw = d.textlength(title, font=f)
    for dx, dy, col in ((3, 4, (0, 0, 0)), (0, 0, (255, 255, 255))):
        d.text(((W - tw) / 2 + dx, 120 + dy), title, font=f, fill=col)
    fe = ImageFont.truetype(FONT_S, 26)
    ew = d.textlength(en, font=fe)
    d.text(((W - ew) / 2, 240), en, font=fe, fill=(255, 255, 255, 200))
    fy = ImageFont.truetype(FONT_S, 28)
    yw = d.textlength(year, font=fy)
    d.text(((W - yw) / 2, H - 70), year, font=fy, fill=(255, 255, 255))
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, quality=88)


if __name__ == "__main__":
    root = Path(sys.argv[1])
    for p in POSTERS:
        make(*p[:7], root / p[7])
        print("poster", p[7])
