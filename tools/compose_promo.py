#!/usr/bin/env python3
"""Composes App Store screenshot frames and social posters from REAL simulator screenshots.
Usage: python3 tools/compose_promo.py <shots_dir> <out_dir>   (needs Pillow, Noto Sans CJK, Inter)"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SHOTS, OUT = Path(sys.argv[1]), Path(sys.argv[2])
ROOT = Path(__file__).resolve().parent.parent
ICON = ROOT / "App/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
TOP, BOTTOM = (0x1F, 0xC8, 0xB4), (0x16, 0x73, 0xD1)
CJK_BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
CJK_REG = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
SC_INDEX = 2  # Noto Sans CJK SC inside the .ttc

CAPTIONS = {  # scene: {lang: (headline, subline)}
    "tv":       {"zh-Hans": ("大屏看局势，手机藏秘密", "电视给所有人看，私密信息只在自己手机上"),
                 "en": ("Big screen for all,\nsecrets on your phone", "The TV shows the game; your hand stays private")},
    "home":     {"zh-Hans": ("一屋子朋友，一台电视", "同一个 Wi-Fi 就能开局，不用注册账号"),
                 "en": ("One room. One TV.\nAll your friends.", "Same Wi-Fi, no accounts needed")},
    "sabotage": {"zh-Hans": ("坑朋友，但有分寸", "坑人卡 + 护盾，强互动默认关闭"),
                 "en": ("Prank your friends —\nkindly", "Sabotage cards and shields; harsh cards off by default")},
    "award":    {"zh-Hans": ("谁最可能……？", "匿名投票，送出好玩又友善的称号"),
                 "en": ("Who’s most likely to…?", "Anonymous votes, friendly titles")},
    "bet":      {"zh-Hans": ("终局大赌，一把翻盘", "押谁会赢最后一局 · 只用游戏内水晶，不能充值"),
                 "en": ("One last bet\nto turn it around", "Bet in-game crystals only — no real money")},
    "results":  {"zh-Hans": ("最后揭晓内鬼任务", "谁一直在偷偷搞事？"),
                 "en": ("Reveal the\nsecret missions", "Who was up to something all along?")},
}

def font(lang, size, bold=True):
    if lang == "en":
        return ImageFont.truetype("/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf" if bold
                                  else "/usr/share/fonts/opentype/inter/Inter-Regular.otf", size)
    return ImageFont.truetype(CJK_BOLD if bold else CJK_REG, size, index=SC_INDEX)

def gradient(w, h):
    g = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(h - 1, 1)
        g.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
    return g.resize((w, h))

def rounded(im, r):
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], r, fill=255)
    out = Image.new("RGBA", im.size)
    out.paste(im.convert("RGB"), (0, 0), m)
    return out

def paste_shot(canvas, shot, x, y, w, radius):
    s = shot.resize((w, round(shot.height * w / shot.width)), Image.LANCZOS)
    s = rounded(s, radius)
    shadow = Image.new("RGBA", (s.width + 160, s.height + 160), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([80, 100, 80 + s.width, 100 + s.height], radius, fill=(10, 30, 70, 110))
    shadow = shadow.filter(ImageFilter.GaussianBlur(40))
    canvas.alpha_composite(shadow, (x - 80, y - 80))
    border = Image.new("RGBA", (s.width + 2 * 14, s.height + 2 * 14), (0, 0, 0, 0))
    ImageDraw.Draw(border).rounded_rectangle([0, 0, border.width - 1, border.height - 1], radius + 14, fill=(255, 255, 255, 255))
    canvas.alpha_composite(border, (x - 14, y - 14))
    canvas.alpha_composite(s, (x, y))
    return s.height

def text_center(d, cx, y, text, f, fill, spacing):
    d.multiline_text((cx, y), text, font=f, fill=fill, anchor="ma", align="center", spacing=spacing)
    return d.multiline_textbbox((cx, y), text, font=f, anchor="ma", align="center", spacing=spacing)[3]

def appstore(device, size, lang, scene):
    W, H = size
    shot = Image.open(SHOTS / device / lang / f"{scene}.png")
    c = gradient(W, H).convert("RGBA")
    d = ImageDraw.Draw(c)
    head, sub = CAPTIONS[scene][lang]
    k = W / 1320
    hs = round((104 if lang == "zh-Hans" else 100) * k * (0.85 if device == "ipad13" else 1))
    y = round(130 * k * (0.8 if device == "ipad13" else 1))
    y = text_center(d, W // 2, y, head, font(lang, hs), "white", round(hs * 0.25)) + round(34 * k)
    y = text_center(d, W // 2, y, sub, font(lang, round(hs * 0.46), False), (255, 255, 255, 225), 10) + round(80 * k)
    sw = round(W * (0.78 if device == "iphone69" else 0.74))
    paste_shot(c, shot, (W - sw) // 2, y, sw, round(sw * (0.075 if device == "iphone69" else 0.035)))
    out = OUT / "appstore" / device / lang
    out.mkdir(parents=True, exist_ok=True)
    c.convert("RGB").save(out / f"{scene}.png")

def poster(lang, size, name, scenes):
    W, H = size
    c = gradient(W, H).convert("RGBA")
    d = ImageDraw.Draw(c)
    k = W / 1080
    icon = rounded(Image.open(ICON).resize((round(150 * k),) * 2, Image.LANCZOS), round(34 * k))
    c.alpha_composite(icon, (round(70 * k), round(70 * k)))
    title = "坑友岛" if lang == "zh-Hans" else "Frenemy Isle"
    d.text((round(245 * k), round(88 * k)), title, font=font(lang, round(64 * k)), fill="white")
    tag = "同一个 Wi-Fi 的派对游戏" if lang == "zh-Hans" else "The same-Wi-Fi party game"
    d.text((round(247 * k), round(168 * k)), tag, font=font(lang, round(32 * k), False), fill=(255, 255, 255, 225))
    slogan = "电视上一起闹，\n手机里藏秘密" if lang == "zh-Hans" else "Party on the TV,\nsecrets on your phone"
    y = text_center(d, W // 2, round(300 * k), slogan, font(lang, round(84 * k)), "white", round(20 * k))
    # two real screenshots, the second one offset
    a = Image.open(SHOTS / "iphone69" / lang / f"{scenes[0]}.png")
    b = Image.open(SHOTS / "iphone69" / lang / f"{scenes[1]}.png")
    sw = round(W * 0.40)
    top = y + round(70 * k)
    paste_shot(c, b, round(W * 0.53), top + round(70 * k), sw, round(sw * 0.075))
    paste_shot(c, a, round(W * 0.07), top, sw, round(sw * 0.075))
    foot = "iPhone · iPad · 2–8 人 · 无需账号 · 即将上线" if lang == "zh-Hans" else "iPhone · iPad · 2–8 players · No account · Coming soon"
    band_h = round(110 * k)
    band = Image.new("RGBA", (W, band_h), (8, 40, 90, 200))
    c.alpha_composite(band, (0, H - band_h))
    d = ImageDraw.Draw(c)
    d.text((W // 2, H - band_h // 2), foot, font=font(lang, round(34 * k), False), fill="white", anchor="mm")
    out = OUT / "posters"
    out.mkdir(parents=True, exist_ok=True)
    c.convert("RGB").save(out / f"{name}-{lang}.png")

if __name__ == "__main__":
    order = ["tv", "home", "sabotage", "award", "bet", "results"]
    for lang in ("zh-Hans", "en"):
        for scene in order:
            appstore("iphone69", (1320, 2868), lang, scene)
            appstore("ipad13", (2064, 2752), lang, scene)
        poster(lang, (1080, 1350), "instagram-1080x1350", ["tv", "sabotage"])
        poster(lang, (1242, 1660), "xiaohongshu-1242x1660", ["award", "bet"])
    print("ok")
