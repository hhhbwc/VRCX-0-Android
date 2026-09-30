# -*- coding: utf-8 -*-
"""生成平板客户端的启动图标。

刻意用**青色 #006a6c**（Theme.kt 里声明的品牌色）而不是手机端的动态蓝 #0054d6：
手机端 App 与平板端 App 会同时装在设备上，图标必须有区分度。

输出：app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher[_round].png
"""
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")

TEAL = (0, 106, 108, 255)
WHITE = (255, 255, 255, 255)
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

FONTS = [r"C:\Windows\Fonts\segoeuib.ttf", r"C:\Windows\Fonts\arialbd.ttf",
         r"C:\Windows\Fonts\arial.ttf"]


def load_font(size):
    for p in FONTS:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def draw_icon(size, round_icon=False):
    ss = size * 4  # 超采样，边缘干净
    im = Image.new("RGBA", (ss, ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    if round_icon:
        d.ellipse([0, 0, ss - 1, ss - 1], fill=TEAL)
    else:
        r = int(ss * 0.22)
        d.rounded_rectangle([0, 0, ss - 1, ss - 1], radius=r, fill=TEAL)

    # 白色 "V0"（偏上）
    f = load_font(int(ss * 0.42))
    tb = d.textbbox((0, 0), "V0", font=f)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    d.text(((ss - tw) / 2 - tb[0], ss * 0.44 - th / 2 - tb[1]), "V0", font=f, fill=WHITE)

    # 平板轮廓（横屏圆角矩形，偏下）—— 一眼看出是"平板"版
    gw, gh = ss * 0.50, ss * 0.30
    x0, y0 = (ss - gw) / 2, ss * 0.70 - gh / 2
    lw = max(2, int(ss * 0.045))
    d.rounded_rectangle([x0, y0, x0 + gw, y0 + gh], radius=int(ss * 0.06),
                        outline=WHITE, width=lw)

    return im.resize((size, size), Image.LANCZOS)


def main():
    for dens, size in DENSITIES.items():
        out = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(out, exist_ok=True)
        draw_icon(size, False).save(os.path.join(out, "ic_launcher.png"))
        draw_icon(size, True).save(os.path.join(out, "ic_launcher_round.png"))
        print(dens, size, "ok")


if __name__ == "__main__":
    main()
