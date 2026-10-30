#!/usr/bin/env python3
"""
Verificación independiente del modelo balístico del juego (BallisticSolver.kt).

- Reimplementa el mismo modelo (punto material, arrastre G7, RK4) en NumPy.
- Compara contra valores de referencia publicados (tablas de fabricantes / calculadoras
  conocidas) con tolerancias razonables para un simulador.
- Si se pasa la ruta de la salida del arnés Kotlin (--kotlin archivo.csv), compara ambos
  modelos punto a punto.

Uso:  python3 tools/verify_ballistics.py [--kotlin kotlin_table.csv]
"""
import math
import sys
import numpy as np

G7_M = np.array([
    0.0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70,
    0.725, 0.75, 0.775, 0.80, 0.825, 0.85, 0.875, 0.90, 0.925, 0.95, 0.975, 1.0, 1.025, 1.05,
    1.075, 1.10, 1.125, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.50, 1.55, 1.60, 1.65, 1.70,
    1.75, 1.80, 1.85, 1.90, 1.95, 2.00, 2.05, 2.10, 2.15, 2.20, 2.25, 2.30, 2.35, 2.40,
    2.45, 2.50, 2.55, 2.60, 2.65, 2.70, 2.75, 2.80, 2.85, 2.90, 2.95, 3.00, 3.10, 3.20,
    3.30, 3.40, 3.50, 3.60, 3.70, 3.80, 3.90, 4.00])
G7_CD = np.array([
    0.1198, 0.1197, 0.1196, 0.1194, 0.1193, 0.1194, 0.1194, 0.1194, 0.1193, 0.1193, 0.1194,
    0.1193, 0.1194, 0.1197, 0.1202, 0.1207, 0.1215, 0.1226, 0.1242, 0.1266, 0.1306, 0.1368,
    0.1464, 0.1660, 0.2054, 0.2993, 0.3803, 0.4015, 0.4043, 0.4034, 0.4014, 0.3987, 0.3955,
    0.3884, 0.3810, 0.3732, 0.3657, 0.3580, 0.3440, 0.3376, 0.3315, 0.3260, 0.3209, 0.3160,
    0.3117, 0.3078, 0.3042, 0.3010, 0.2980, 0.2951, 0.2922, 0.2892, 0.2864, 0.2835, 0.2807,
    0.2779, 0.2752, 0.2725, 0.2697, 0.2670, 0.2643, 0.2615, 0.2588, 0.2561, 0.2533, 0.2506,
    0.2479, 0.2451, 0.2424, 0.2368, 0.2313, 0.2258, 0.2205, 0.2154, 0.2106, 0.2060, 0.2017,
    0.1975, 0.1935])

G = 9.80665
LB_IN2 = 703.0696


def atmosphere(temp_c=15.0, alt=0.0):
    p = 101325.0 * (1 - 2.25577e-5 * alt) ** 5.25588
    rho = p / (287.05 * (temp_c + 273.15))
    a = 331.3 * math.sqrt(1 + temp_c / 273.15)
    return rho, a


def simulate(v0, bc, angle, sight_h, wind_cross=0.0, head=0.0, max_range=1200.0,
             dt=0.0005, temp_c=15.0, alt=0.0):
    """Plano: y adelante, z arriba, x derecha. Devuelve arrays (t, x, y, z, v)."""
    rho, a_snd = atmosphere(temp_c, alt)
    wind = np.array([wind_cross, -head, 0.0])
    p = np.array([0.0, 0.0, -sight_h])
    v = np.array([0.0, math.cos(angle), math.sin(angle)]) * v0
    t = 0.0
    out = [(t, *p, v0)]

    def acc(vel):
        vr = vel - wind
        sp = np.linalg.norm(vr)
        cd = np.interp(sp / a_snd, G7_M, G7_CD)
        k = rho * cd * math.pi / (8 * bc * LB_IN2)
        return -k * sp * vr + np.array([0, 0, -G])

    while p[1] < max_range and t < 6:
        k1v = acc(v); k1p = v
        k2v = acc(v + k1v * dt / 2); k2p = v + k1v * dt / 2
        k3v = acc(v + k2v * dt / 2); k3p = v + k2v * dt / 2
        k4v = acc(v + k3v * dt); k4p = v + k3v * dt
        v = v + (k1v + 2 * k2v + 2 * k3v + k4v) * dt / 6
        p = p + (k1p + 2 * k2p + 2 * k3p + k4p) * dt / 6
        t += dt
        out.append((t, *p, np.linalg.norm(v)))
    return np.array(out)


def at_range(tr, r):
    y = tr[:, 2]
    i = np.searchsorted(y, r)
    if i <= 0 or i >= len(y):
        return None
    k = (r - y[i - 1]) / (y[i] - y[i - 1])
    return tr[i - 1] + (tr[i] - tr[i - 1]) * k


def zero_angle(v0, bc, zero_r, sight_h):
    lo, hi = -0.01, 0.03
    for _ in range(40):
        mid = (lo + hi) / 2
        tr = simulate(v0, bc, mid, sight_h, max_range=zero_r + 50, dt=0.001)
        z = at_range(tr, zero_r)[3]
        if z > 0:
            hi = mid
        else:
            lo = mid
    return (lo + hi) / 2


def table(name, v0, bc, zero_r, sight_h, ranges, wind_cross=0.0):
    ang = zero_angle(v0, bc, zero_r, sight_h)
    tr = simulate(v0, bc, ang, sight_h, wind_cross=wind_cross, max_range=max(ranges) + 10)
    rows = []
    for r in ranges:
        s = at_range(tr, r)
        drop_mil = -math.atan2(s[3], r) * 1000  # corrección a subir
        drift_mil = math.atan2(s[1], r) * 1000
        rows.append((r, s[0], s[4], s[3], drop_mil, drift_mil))
    print(f"\n== {name}  (V0={v0} m/s, G7={bc}, cero {zero_r} m, ángulo cero {ang*1000:.3f} mrad)")
    print("  dist   ToF[s]  vel[m/s]  caída[cm]  elev[mrad]  deriva viento[mrad]")
    for r, tof, vel, z, dm, wm in rows:
        print(f"  {r:5.0f}  {tof:6.3f}  {vel:8.1f}  {z*100:9.1f}  {dm:9.2f}  {wm:9.2f}")
    return ang, rows


def check(label, got, lo, hi):
    ok = lo <= got <= hi
    print(f"  [{'OK' if ok else 'FALLA'}] {label}: {got:.2f} (esperado {lo}..{hi})")
    return ok


def main():
    ok = True
    # .308 Win 175gr SMK, 2600 fps (792 m/s), G7 0.243 (Litz), cero 100 m, mira 4.5 cm.
    # Referencia publicada: ~1100 fps (≈335 m/s) a 1000 yd; cruce transónico cerca de 1000-1100 yd.
    YD = 0.9144
    ang, rows = table(".308 Win 175gr SMK", 792.0, 0.243, 100, 0.045,
                      [100, 200, 300, 400, 500, 600, 700, 800, 1000 * YD, 1000], wind_cross=4.47)
    r = {round(row[0], 1): row for row in rows}
    ok &= check("Velocidad a 1000 yd [m/s]", r[914.4][2], 320, 360)
    ok &= check("Viento 10 mph a 500 m [mrad]", r[500][5], 1.1, 1.6)
    ok &= check("Cero en 100 m [cm]", r[100][3] * 100, -0.5, 0.5)

    # 6.5 Creedmoor 140gr ELD-M, 2710 fps (826 m/s), G7 0.326. Tabla Hornady (cero 100 yd):
    # 600 yd -86.9 in (≈4.0 mrad), 1000 yd -317 in (≈8.8 mrad), ~1490 fps a 1000 yd.
    ang, rows = table("6.5 Creedmoor 140gr ELD-M", 826.0, 0.326, 100 * YD, 0.038,
                      [100 * YD, 600 * YD, 1000 * YD])
    r = {round(row[0], 1): row for row in rows}
    ok &= check("Elevación 600 yd [mrad]", r[548.6][4], 3.7, 4.3)
    ok &= check("Elevación 1000 yd [mrad]", r[914.4][4], 8.3, 9.3)
    ok &= check("Velocidad 1000 yd [m/s]", r[914.4][2], 430, 480)

    # Comparación con el arnés Kotlin (opcional)
    if "--kotlin" in sys.argv:
        path = sys.argv[sys.argv.index("--kotlin") + 1]
        kt = {}
        for line in open(path):
            if line.startswith("#") or not line.strip():
                continue
            name, dist, elev = line.strip().split(",")[:3]
            kt[(name, float(dist))] = float(elev)
        print("\n== Comparación Kotlin vs Python (elevación mrad)")
        for name, v0, bc in [(".308", 792.0, 0.243), ("6.5CM", 823.0, 0.326)]:
            a = zero_angle(v0, bc, 100, 0.045)
            tr = simulate(v0, bc, a, 0.045, max_range=1010)
            for d in [300.0, 600.0, 1000.0]:
                py = -math.atan2(at_range(tr, d)[3], d) * 1000
                k = kt.get((name, d))
                if k is None:
                    continue
                diff = abs(py - k)
                good = diff < 0.05
                ok &= good
                print(f"  [{'OK' if good else 'FALLA'}] {name} {d:.0f} m: python {py:.3f}  kotlin {k:.3f}  dif {diff:.4f}")

    print("\nRESULTADO:", "TODO OK" if ok else "HAY FALLAS")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
