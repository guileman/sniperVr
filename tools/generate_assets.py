#!/usr/bin/env python3
"""
Genera los gráficos de apoyo del juego con NumPy y Pillow (sin archivos externos):
  - fx_dust.png    hoja de 8 cuadros: polvo/tierra al impactar el suelo
  - fx_spark.png   hoja de 6 cuadros: chispa al pegar en el acero
  - fx_muzzle.png  humo de boca de cañón
  - menu_bg.png    ilustración del menú (paisaje patagónico al atardecer visto por la mira)
  - ic_launcher.png ícono de la app

Uso: python3 tools/generate_assets.py
"""
import math
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable-nodpi")
rng = np.random.default_rng(7)


def value_noise(h, w, scale, octaves=4, seed=0):
    """Ruido fractal suave (suma de ruidos de valor interpolados)."""
    r = np.random.default_rng(seed)
    out = np.zeros((h, w), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        s = max(2, int(scale * 2 ** o))
        grid = r.random((s + 1, s + 1)).astype(np.float32)
        img = Image.fromarray((grid * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        out += np.asarray(img, np.float32) / 255 * amp
        total += amp
        amp *= 0.5
    return out / total


def radial(size, cx=0.5, cy=0.5):
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
    return np.sqrt((x - cx) ** 2 + (y - cy) ** 2)


def dust_sheet(frames=8, size=128):
    sheet = np.zeros((size, size * frames, 4), np.float32)
    for f in range(frames):
        t = f / (frames - 1)
        n = value_noise(size, size, 3, 4, seed=10 + f)
        # Penacho que sube y se abre
        y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
        cx, cy = 0.5, 0.78 - 0.25 * t
        rx, ry = 0.24 + 0.22 * t, 0.16 + 0.28 * t
        d = np.sqrt(((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2)
        a = np.clip((1.25 - d - (n - 0.5) * 0.9) * 1.4, 0, 1) ** 1.2
        a *= (1 - t) ** 0.7
        # Pedazos de tierra en los primeros cuadros
        if t < 0.5:
            for _ in range(14):
                ang = rng.uniform(-2.6, -0.5)
                dist = 0.1 + t * rng.uniform(0.3, 0.5)
                px, py = 0.5 + math.cos(ang) * dist, 0.8 + math.sin(ang) * dist
                rr = radial(size, px, py)
                a = np.maximum(a, np.clip(1 - rr / 0.02, 0, 1) * (1 - t * 2))
        col = np.stack([0.62 + 0.1 * n, 0.53 + 0.08 * n, 0.40 + 0.06 * n], -1)
        sheet[:, f * size:(f + 1) * size, :3] = col
        sheet[:, f * size:(f + 1) * size, 3] = a
    return Image.fromarray((np.clip(sheet, 0, 1) * 255).astype(np.uint8), "RGBA")


def spark_sheet(frames=6, size=128):
    sheet = np.zeros((size, size * frames, 4), np.float32)
    for f in range(frames):
        t = f / (frames - 1)
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        dr = ImageDraw.Draw(img)
        c = size / 2
        for k in range(18):
            ang = rng.uniform(0, 2 * math.pi)
            l1 = size * (0.05 + 0.3 * t)
            l2 = l1 + size * rng.uniform(0.05, 0.18) * (1 - t)
            col = (255, int(220 - 80 * t), int(120 - 100 * t), int(255 * (1 - t)))
            dr.line([(c + math.cos(ang) * l1, c + math.sin(ang) * l1), (c + math.cos(ang) * l2, c + math.sin(ang) * l2)], fill=col, width=2)
        arr = np.asarray(img, np.float32) / 255
        glow = np.clip(1 - radial(size) / (0.25 * (1 - t) + 0.02), 0, 1) ** 2
        arr[..., 0] = np.maximum(arr[..., 0], glow)
        arr[..., 1] = np.maximum(arr[..., 1], glow * 0.9)
        arr[..., 2] = np.maximum(arr[..., 2], glow * 0.6)
        arr[..., 3] = np.maximum(arr[..., 3], glow * (1 - t))
        sheet[:, f * size:(f + 1) * size] = arr
    return Image.fromarray((np.clip(sheet, 0, 1) * 255).astype(np.uint8), "RGBA")


def muzzle_smoke(size=512):
    n = value_noise(size, size, 4, 5, seed=3)
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
    d = np.sqrt(((x - 0.5) / 0.45) ** 2 + ((y - 0.6) / 0.38) ** 2)
    a = np.clip(1 - d - (n - 0.5) * 0.8, 0, 1) ** 1.8 * 0.85
    col = 0.80 + 0.15 * n
    arr = np.stack([col, col, col * 0.97, a], -1)
    return Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8), "RGBA")


def mountains(w, h, base, rough, seed):
    r = np.random.default_rng(seed)
    xs = np.linspace(0, 1, w)
    y = np.zeros(w)
    for k in range(1, 7):
        y += np.sin(xs * math.pi * 2 * k * r.uniform(0.6, 1.4) + r.uniform(0, 6)) / k
    y = base + y / 3 * rough
    return y


def menu_bg(w=1600, h=900):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    t = y / h
    # Cielo de atardecer
    top = np.array([0.10, 0.12, 0.22]); mid = np.array([0.85, 0.45, 0.22]); low = np.array([0.98, 0.78, 0.45])
    sky = np.where(t[..., None] < 0.45, top + (mid - top) * (t[..., None] / 0.45), mid + (low - mid) * ((t[..., None] - 0.45) / 0.2).clip(0, 1))
    sun = np.exp(-(((x - w * 0.70) ** 2 + (y - h * 0.58) ** 2) / (2 * 60 ** 2)))
    sky = sky + sun[..., None] * np.array([1.0, 0.85, 0.5]) * 0.8
    img = np.clip(sky, 0, 1)
    # Capas de montañas (más lejanas = más claras)
    layers = [(0.60, 0.07, (0.55, 0.38, 0.40), 1), (0.66, 0.06, (0.36, 0.25, 0.30), 2), (0.73, 0.04, (0.20, 0.16, 0.18), 3)]
    for base, rough, col, seed in layers:
        ridge = mountains(w, h, base, rough, seed) * h
        mask = y > ridge[None, :]
        img[mask] = np.array(col)
    # Estepa en primer plano
    grass_n = value_noise(h, w, 30, 3, seed=9)
    fg = y > h * (0.80 + 0.02 * np.sin(x / w * 7))
    img[fg] = (np.array([0.12, 0.10, 0.07]) + grass_n[fg][:, None] * np.array([0.10, 0.08, 0.04]))
    # Siluetas de guanacos en la loma
    im = Image.fromarray((img * 255).astype(np.uint8))
    dr = ImageDraw.Draw(im)
    for gx, s in [(1060, 1.0), (1130, 0.8), (1185, 0.9)]:
        gy = h * 0.80
        body = [gx - 28 * s, gy - 48 * s, gx + 28 * s, gy - 28 * s]
        dr.ellipse(body, fill=(20, 15, 12))
        dr.line([(gx + 22 * s, gy - 42 * s), (gx + 30 * s, gy - 82 * s)], fill=(20, 15, 12), width=int(8 * s))
        dr.ellipse([gx + 24 * s, gy - 92 * s, gx + 44 * s, gy - 80 * s], fill=(20, 15, 12))
        for lx in (-20, -12, 14, 22):
            dr.line([(gx + lx * s, gy - 32 * s), (gx + lx * s, gy)], fill=(20, 15, 12), width=int(4 * s))
    # Vista por la mira: círculo con viñeta sobre la derecha
    arr = np.asarray(im, np.float32) / 255
    cx, cy, r = w * 0.68, h * 0.55, h * 0.42
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / r
    outside = d > 1
    arr[outside] *= 0.28
    vign = np.clip((d - 0.82) / 0.18, 0, 1)
    arr[~outside] *= (1 - 0.7 * vign[~outside])[:, None]
    # Oscurecer el lado izquierdo para el texto
    arr *= (0.55 + 0.45 * np.clip((x / w - 0.1) / 0.4, 0, 1))[..., None]
    im = Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8))
    dr = ImageDraw.Draw(im)
    col = (10, 10, 10)
    dr.line([(cx - r, cy), (cx - 40, cy)], fill=col, width=7)
    dr.line([(cx + 40, cy), (cx + r, cy)], fill=col, width=7)
    dr.line([(cx, cy + 40), (cx, cy + r)], fill=col, width=7)
    dr.line([(cx, cy - r), (cx, cy - 40)], fill=col, width=7)
    dr.line([(cx - 40, cy), (cx + 40, cy)], fill=col, width=2)
    dr.line([(cx, cy - 40), (cx, cy + 40)], fill=col, width=2)
    for i in range(1, 6):
        d = i * 8
        dr.line([(cx + d, cy - 4), (cx + d, cy + 4)], fill=col, width=2)
        dr.line([(cx - d, cy - 4), (cx - d, cy + 4)], fill=col, width=2)
        dr.line([(cx - 4, cy + d), (cx + 4, cy + d)], fill=col, width=2)
    dr.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(25, 25, 25), width=8)
    return im.filter(ImageFilter.SMOOTH)


def icon(size=512):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    dr.rounded_rectangle([0, 0, size, size], radius=size * 0.22, fill=(28, 32, 26, 255))
    c = size / 2; r = size * 0.36
    dr.ellipse([c - r, c - r, c + r, c + r], fill=(214, 170, 72, 255))
    dr.ellipse([c - r * 0.86, c - r * 0.86, c + r * 0.86, c + r * 0.86], fill=(40, 46, 36, 255))
    w = int(size * 0.025)
    dr.line([(c - r * 0.86, c), (c - r * 0.15, c)], fill=(214, 170, 72, 255), width=w)
    dr.line([(c + r * 0.15, c), (c + r * 0.86, c)], fill=(214, 170, 72, 255), width=w)
    dr.line([(c, c - r * 0.86), (c, c - r * 0.15)], fill=(214, 170, 72, 255), width=w)
    dr.line([(c, c + r * 0.15), (c, c + r * 0.86)], fill=(214, 170, 72, 255), width=w)
    dr.ellipse([c - w, c - w, c + w, c + w], fill=(220, 60, 40, 255))
    return im


def main():
    os.makedirs(OUT, exist_ok=True)
    dust_sheet().save(os.path.join(OUT, "fx_dust.png"), optimize=True)
    spark_sheet().save(os.path.join(OUT, "fx_spark.png"), optimize=True)
    muzzle_smoke().save(os.path.join(OUT, "fx_muzzle.png"), optimize=True)
    menu_bg().convert("RGB").save(os.path.join(OUT, "menu_bg.jpg"), quality=86)
    icon().resize((192, 192), Image.LANCZOS).save(os.path.join(OUT, "ic_launcher.png"), optimize=True)
    for f in sorted(os.listdir(OUT)):
        print(f"{f:20s} {os.path.getsize(os.path.join(OUT, f)) // 1024} KB")


if __name__ == "__main__":
    main()
