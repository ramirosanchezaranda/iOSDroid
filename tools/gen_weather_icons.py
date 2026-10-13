"""Genera los íconos del clima (estilo SF Symbols .fill, monocromo) como vector drawables.

Uso: pip install skia-pathops && python3 tools/gen_weather_icons.py app/src/main/res/drawable /tmp/preview.html
"""
import math, os, sys
import pathops
from pathops import Path, PathOp, LineCap, LineJoin, op

K = 0.5522847498

def circle(cx, cy, r):
    p = Path()
    p.moveTo(cx + r, cy)
    p.cubicTo(cx + r, cy + K * r, cx + K * r, cy + r, cx, cy + r)
    p.cubicTo(cx - K * r, cy + r, cx - r, cy + K * r, cx - r, cy)
    p.cubicTo(cx - r, cy - K * r, cx - K * r, cy - r, cx, cy - r)
    p.cubicTo(cx + K * r, cy - r, cx + r, cy - K * r, cx + r, cy)
    p.close()
    return p

def poly(*pts):
    p = Path()
    p.moveTo(*pts[0])
    for q in pts[1:]:
        p.lineTo(*q)
    p.close()
    return p

def rect(x0, y0, x1, y1):
    return poly((x0, y0), (x1, y0), (x1, y1), (x0, y1))

def capsule(x0, y0, x1, y1, w):
    p = Path()
    p.moveTo(x0, y0)
    p.lineTo(x1, y1)
    p.stroke(w, LineCap.ROUND_CAP, LineJoin.ROUND_JOIN, 4)
    p.convertConicsToQuads()
    return p

def U(*ps):
    out = ps[0]
    for q in ps[1:]:
        out = op(out, q, PathOp.UNION)
    return out

def D(a, b):
    return op(a, b, PathOp.DIFFERENCE)

def grow(p, d):
    """Expande una forma d unidades (contorno + relleno)."""
    s = Path()
    s.addPath(p)
    s.stroke(2 * d, LineCap.ROUND_CAP, LineJoin.ROUND_JOIN, 4)
    s.convertConicsToQuads()
    return U(p, s)

def xf(p, s=1.0, tx=0.0, ty=0.0):
    q = Path()
    q.addPath(p)
    q.transform(s, 0, 0, s, tx, ty)
    return q

def cloud():
    return U(circle(7.6, 14.6, 4.1), circle(12.6, 11.2, 5.6), circle(17.4, 14.8, 3.9),
             rect(7.6, 14.0, 17.4, 18.7))

def sun(cx, cy, r, ray_gap, ray_len, ray_w):
    parts = [circle(cx, cy, r)]
    for i in range(8):
        a = i * math.pi / 4
        r0, r1 = r + ray_gap, r + ray_gap + ray_len
        parts.append(capsule(cx + r0 * math.cos(a), cy + r0 * math.sin(a),
                             cx + r1 * math.cos(a), cy + r1 * math.sin(a), ray_w))
    return U(*parts)

def moon(cx, cy, r):
    # creciente: disco menos un disco desplazado hacia arriba a la derecha
    return D(circle(cx, cy, r), circle(cx + r * 0.62, cy - r * 0.55, r * 0.86))

GAP = 1.35

def with_cloud_over(back, cl):
    return U(D(back, grow(cl, GAP)), cl)

def upper_cloud():
    # nube un poco más chica y arriba, deja lugar para precipitación
    return xf(cloud(), 0.9, 1.2, -2.6)

ICONS = {}
ICONS["ic_wx_sun"] = sun(12, 12, 4.6, 2.0, 2.2, 2.0)
ICONS["ic_wx_moon"] = xf(moon(12, 12, 8.4), 1.0, -0.6, 0.6)
ICONS["ic_wx_cloud"] = cloud()
small_cloud = xf(cloud(), 0.76, 5.6, 5.4)
ICONS["ic_wx_partly_day"] = with_cloud_over(sun(8.4, 8.2, 3.7, 1.5, 1.7, 1.7), small_cloud)
ICONS["ic_wx_partly_night"] = with_cloud_over(moon(8.8, 8.6, 6.4), small_cloud)

uc = upper_cloud()
ICONS["ic_wx_fog"] = U(uc, capsule(5.2, 18.2, 18.8, 18.2, 1.9), capsule(7.4, 21.6, 16.6, 21.6, 1.9))
ICONS["ic_wx_rain"] = U(uc, *[capsule(x, 17.4, x - 1.3, 21.6, 1.9) for x in (8.6, 12.6, 16.6)])
ICONS["ic_wx_drizzle"] = U(uc,
    *[capsule(x, 17.4, x - 0.5, 18.7, 1.8) for x in (8.6, 12.6, 16.6)],
    *[capsule(x, 20.6, x - 0.5, 21.9, 1.8) for x in (10.4, 14.4)])

def asterisk(cx, cy, r, w):
    return U(*[capsule(cx + r * math.cos(a), cy + r * math.sin(a),
                       cx - r * math.cos(a), cy - r * math.sin(a), w)
               for a in (math.pi / 2, math.pi / 2 + 2 * math.pi / 3, math.pi / 2 + 4 * math.pi / 3)])

ICONS["ic_wx_snow"] = U(uc, *[asterisk(x, 18.5, 1.3, 1.0) for x in (8.2, 12.4, 16.6)],
                        *[asterisk(x, 21.7, 1.3, 1.0) for x in (10.3, 14.5)])
bolt = poly((13.4, 12.6), (8.6, 18.4), (11.6, 18.4), (10.2, 23.2), (15.6, 16.6), (12.6, 16.6), (14.4, 12.6))
ICONS["ic_wx_thunder"] = U(D(uc, grow(bolt, GAP)), bolt)
arrow = poly((20.6, 3.4), (3.4, 10.9), (11.0, 13.0), (13.1, 20.6))
ICONS["ic_location_arrow"] = grow(arrow, 0.5)

def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s

def to_svg_d(p):
    out = []
    for verb, pts in p.segments:
        if verb == "moveTo":
            out.append("M" + ",".join(fmt(c) for c in pts[0]))
        elif verb == "lineTo":
            out.append("L" + ",".join(fmt(c) for c in pts[0]))
        elif verb == "curveTo":
            out.append("C" + " ".join(",".join(fmt(c) for c in q) for q in pts))
        elif verb == "qCurveTo":
            # pathops puede emitir cadenas de cuadráticas implícitas
            pts = list(pts)
            while len(pts) > 2:
                c0, c1 = pts[0], pts[1]
                mid = ((c0[0] + c1[0]) / 2, (c0[1] + c1[1]) / 2)
                out.append("Q" + ",".join(fmt(c) for c in c0) + " " + ",".join(fmt(c) for c in mid))
                pts = pts[1:]
            out.append("Q" + " ".join(",".join(fmt(c) for c in q) for q in pts))
        elif verb == "closePath":
            out.append("Z")
        else:
            raise ValueError(verb)
    return "".join(out)

def vector_xml(d, size):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{d}" />
</vector>
"""

if __name__ == "__main__":
    res_dir, preview = sys.argv[1], sys.argv[2]
    cells = []
    for name, p in ICONS.items():
        p = Path(p) if not isinstance(p, Path) else p
        p.simplify()
        d = to_svg_d(p)
        with open(os.path.join(res_dir, name + ".xml"), "w") as f:
            f.write(vector_xml(d, 24))
        cells.append(f'<div><svg viewBox="0 0 24 24" width="96" height="96"><path fill="white" d="{d}"/></svg><p>{name}</p></div>')
    with open(preview, "w") as f:
        f.write('<html><body style="background:#2b1a12;color:#ddd;font:12px sans-serif;display:flex;flex-wrap:wrap;gap:16px;padding:16px">'
                + "".join(cells) + "</body></html>")
    print("ok", len(ICONS))
