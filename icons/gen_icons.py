#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TwinBox 图标生成：五密度 PNG（浅色底兜底 + 深色版）"""
from PIL import Image, ImageDraw
import os

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res")
BG = (14, 21, 32, 255)        # 深空蓝
TEAL = (79, 209, 197, 255)    # 描边盒
VIOLET = (139, 124, 246, 255) # 实心盒
LIGHT = (233, 238, 246, 255)  # 分割线

def rounded(draw, xy, r, fill=None, outline=None, width=1):
    draw.rounded_rectangle(xy, radius=r, fill=fill, outline=outline, width=width)

def icon(size):
    s = size
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 圆角底
    rounded(d, (0, 0, s - 1, s - 1), r=s // 5, fill=BG)
    # 双盒几何（按 64 单位坐标缩放）
    k = s / 64.0
    # 盒1：描边（0.10~0.56, 0.12~0.58），线宽2.4/64
    rounded(d, (10 * k, 10 * k, 36 * k, 38 * k), r=3 * k,
            outline=TEAL, width=max(2, int(2.6 * k)))
    # 盒2：实心（0.25~0.86, 0.28~0.90）
    rounded(d, (18 * k, 22 * k, 55 * k, 58 * k), r=3 * k, fill=VIOLET)
    # 分割线（贯穿实心盒）
    d.rectangle((22 * k, 34 * k, 52 * k, 35 * k + max(1, int(1.6 * k))), fill=BG)
    return img

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, px in DENSITIES.items():
    out = os.path.join(BASE, "mipmap-" + name, "ic_launcher.png")
    icon(px).save(out)
    print("wrote", out, px)
print("ICONS-OK")
