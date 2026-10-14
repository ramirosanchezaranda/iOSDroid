"""Genera los layouts del widget (app/src/main/res/layout/widget_*.xml).

RemoteViews necesita un ID distinto para cada vista que se modifica, así que las filas del
pronóstico (f0_*, f1_*, ...) se escriben acá en vez de copiarlas a mano.
Espaciado en múltiplos de 4 dp, como la guía de iOS.

Uso: python3 tools/gen_widget_layouts.py app/src/main/res/layout
"""
import os
import sys

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def attrs(d, indent):
    pad = " " * indent
    # "style" y el "layout" de <include> van sin prefijo android:.
    return "\n".join(f'{pad}{k if k in ("style", "layout") else "android:" + k}="{v}"' for k, v in d.items())


def el(tag, d, indent, children=None, first=False):
    pad = " " * indent
    head = f"{pad}<{tag}" + (f" {NS}" if first else "")
    body = attrs(d, indent + 4)
    if children is None:
        return f"{head}\n{body} />"
    inner = "\n\n".join(children)
    return f"{head}\n{body}>\n\n{inner}\n{pad}</{tag}>"


def root(comment, content, indent=0):
    glass = el("ImageView", {
        "id": "@+id/glass", "layout_width": "match_parent", "layout_height": "match_parent",
        "importantForAccessibility": "no", "scaleType": "fitXY", "src": "@drawable/glass_fallback",
    }, 4)
    frame = el("FrameLayout", {
        "id": "@android:id/background", "layout_width": "match_parent", "layout_height": "match_parent",
        "background": "@drawable/widget_outline", "clipToOutline": "true",
    }, 0, [glass, content], first=True)
    return f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} -->\n<!-- Generado por tools/gen_widget_layouts.py: no editar a mano. -->\n{frame}\n'


DATE_FAMILIES = [("date", "WidgetText.Date"), ("date_condensed", "WidgetText.Date.Condensed"),
                 ("date_serif", "WidgetText.Date.Serif"), ("date_mono", "WidgetText.Date.Mono"),
                 ("date_script", "WidgetText.Date.Script")]


def date(ind, size, width="match_parent", weight=None):
    """La fecha, una vez por familia de fuente (como la hora) más una imagen para la fuente propia.
    La imagen mide lo mismo que una línea de texto y se alinea a la izquierda."""
    clocks = [el("TextClock", {"id": f"@+id/{vid}", "style": f"@style/{style}", "layout_width": "match_parent",
                               "layout_height": "wrap_content", "textSize": f"{size}sp",
                               "visibility": "visible" if vid == "date" else "gone"}, ind + 4)
              for vid, style in DATE_FAMILIES]
    image = el("ImageView", {"id": "@+id/date_image", "layout_width": "match_parent",
                             "layout_height": f"{round(size * 1.35)}dp", "scaleType": "fitStart",
                             "visibility": "gone"}, ind + 4)
    d = {"id": "@+id/date_box", "layout_width": width, "layout_height": "wrap_content"}
    if weight:
        d["layout_weight"] = weight
    return el("FrameLayout", d, ind, clocks + [image])


def time(ind):
    return el("include", {"layout": "@layout/widget_time", "layout_width": "match_parent",
                          "layout_height": "0dp", "layout_weight": "1"}, ind)


def icon(ind, vid, size, extra=None):
    d = {"id": f"@+id/{vid}", "layout_width": f"{size}dp", "layout_height": f"{size}dp", "src": "@drawable/ic_wx_sun"}
    d.update(extra or {})
    return el("ImageView", d, ind)


def text(ind, vid, style, size, txt, width="wrap_content", extra=None):
    d = {"id": f"@+id/{vid}", "style": f"@style/{style}", "layout_width": width,
         "layout_height": "wrap_content", "text": txt, "textSize": f"{size}sp"}
    d.update(extra or {})
    return el("TextView", d, ind)


def current_row(ind, icon_size, temp_size, cond_size, hilo_size=None, margin_top=4):
    """Fila del clima actual: ícono, temperatura, estado y (opcional) máx./mín."""
    kids = [
        icon(ind + 4, "wx_icon", icon_size),
        text(ind + 4, "temp", "WidgetText.Temp", temp_size, "@string/sample_temp", extra={"layout_marginStart": "6dp"}),
        text(ind + 4, "condition", "WidgetText.Secondary", cond_size, "@string/sample_condition", width="0dp",
             extra={"layout_weight": "1", "layout_marginStart": "8dp"}),
    ]
    hilo = {"layout_marginStart": "8dp"}
    if hilo_size is None:
        hilo["visibility"] = "gone"
    kids.append(text(ind + 4, "hilo", "WidgetText.Tertiary", hilo_size or 12, "@string/sample_hilo", extra=hilo))
    return el("LinearLayout", {"id": "@+id/weather", "layout_width": "match_parent", "layout_height": "wrap_content",
                               "layout_marginTop": f"{margin_top}dp", "gravity": "center_vertical",
                               "orientation": "horizontal"}, ind, kids)


def divider(ind):
    return el("ImageView", {"id": "@+id/divider", "layout_width": "match_parent", "layout_height": "1dp",
                            "layout_marginTop": "12dp", "layout_marginBottom": "6dp", "alpha": "0.2",
                            "importantForAccessibility": "no", "scaleType": "fitXY", "src": "@drawable/hairline"}, ind)


def forecast_row(ind, n, row_h, ts, icon_size, num_w, bar_w):
    kids = [
        text(ind + 4, f"f{n}_day", "WidgetText.Secondary", ts, "@string/sample_day", width="0dp",
             extra={"layout_weight": "1"}),
        icon(ind + 4, f"f{n}_icon", icon_size),
        text(ind + 4, f"f{n}_min", "WidgetText.Tertiary", ts, "@string/sample_min", width=f"{num_w}dp",
             extra={"gravity": "end"}),
        el("ImageView", {"id": f"@+id/f{n}_bar", "layout_width": f"{bar_w}dp", "layout_height": "12dp",
                         "layout_marginStart": "8dp", "layout_marginEnd": "4dp", "importantForAccessibility": "no",
                         "scaleType": "fitXY", "src": "@drawable/range_bar_sample"}, ind + 4),
        text(ind + 4, f"f{n}_max", "WidgetText.Temp", ts, "@string/sample_max", width=f"{num_w}dp",
             extra={"gravity": "end"}),
    ]
    return el("LinearLayout", {"id": f"@+id/f{n}_row", "layout_width": "match_parent", "layout_height": f"{row_h}dp",
                               "gravity": "center_vertical", "orientation": "horizontal"}, ind, kids)


def column(ind, kids, extra=None, padding=16):
    d = {"layout_width": "match_parent", "layout_height": "match_parent", "orientation": "vertical",
         "padding": f"{padding}dp"}
    d.update(extra or {})
    return el("LinearLayout", d, ind, kids)


FONTS = [("thin", "Thin"), ("light", "Light"), ("regular", "Regular"), ("bold", "Bold"), ("heavy", "Heavy"),
         ("condensed", "Condensed"), ("serif", "Serif"), ("mono", "Mono"), ("script", "Script")]


def time_block():
    """La hora, una vez por fuente del sistema (RemoteViews no deja cambiar la fuente) y una imagen
    para la fuente propia del usuario, que se dibuja en la app."""
    clocks = [el("TextClock", {
        "id": f"@+id/time_{key}", "style": f"@style/WidgetText.Time.{style}", "layout_width": "match_parent",
        "layout_height": "match_parent", "gravity": "start|center_vertical",
        "visibility": "visible" if key == "light" else "gone",
    }, 4) for key, style in FONTS]
    image = el("ImageView", {
        "id": "@+id/time_image", "layout_width": "wrap_content", "layout_height": "match_parent",
        "layout_gravity": "start|center_vertical", "adjustViewBounds": "true", "scaleType": "fitCenter",
        "visibility": "gone",
    }, 4)
    frame = el("FrameLayout", {"id": "@+id/time_box", "layout_width": "match_parent",
                               "layout_height": "match_parent"}, 0, clocks + [image], first=True)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- La hora del widget. El código deja visible solo la variante elegida. -->\n"
            "<!-- Generado por tools/gen_widget_layouts.py: no editar a mano. -->\n" + frame + "\n")


def small():
    # Temperatura y máx./mín. en una línea, el estado debajo.
    row = el("LinearLayout", {"id": "@+id/weather", "layout_width": "match_parent", "layout_height": "wrap_content",
                              "orientation": "vertical"}, 8, [
        el("LinearLayout", {"layout_width": "match_parent", "layout_height": "wrap_content",
                            "gravity": "center_vertical", "orientation": "horizontal"}, 12, [
            icon(16, "wx_icon", 16),
            text(16, "temp", "WidgetText.Temp", 17, "@string/sample_temp", extra={"layout_marginStart": "4dp"}),
            text(16, "hilo", "WidgetText.Tertiary", 11, "@string/sample_hilo_short", width="0dp",
                 extra={"layout_weight": "1", "layout_marginStart": "4dp", "gravity": "end"}),
        ]),
        text(12, "condition", "WidgetText.Secondary", 11, "@string/sample_condition", width="match_parent",
             extra={"layout_marginTop": "4dp"}),
    ])
    kids = [date(8, 13), time(8), row]
    return root("2×2: fecha corta, hora y temperatura.", column(4, kids))


def medium():
    row = el("LinearLayout", {"id": "@+id/weather", "layout_width": "match_parent", "layout_height": "wrap_content",
                              "orientation": "vertical"}, 8, [
        el("LinearLayout", {"layout_width": "match_parent", "layout_height": "wrap_content",
                            "gravity": "center_vertical", "orientation": "horizontal"}, 12, [
            icon(16, "wx_icon", 20),
            text(16, "temp", "WidgetText.Temp", 20, "@string/sample_temp", extra={"layout_marginStart": "8dp"}),
            text(16, "hilo", "WidgetText.Tertiary", 12, "@string/sample_hilo", width="0dp",
                 extra={"layout_weight": "1", "layout_marginStart": "8dp", "gravity": "end"}),
        ]),
        text(12, "condition", "WidgetText.Secondary", 12, "@string/sample_condition", width="match_parent",
             extra={"layout_marginTop": "4dp"}),
    ])
    kids = [date(8, 14), time(8), row]
    return root("3×2 (y 4×2): fecha, hora grande y clima actual.", column(4, kids))


def large():
    kids = [date(8, 15), time(8), current_row(8, 20, 20, 13), divider(8)]
    kids += [forecast_row(8, n, 22, 13, 16, 28, 40) for n in range(4)]
    return root("3×3: hora, clima actual y los próximos días con barras de temperatura.", column(4, kids))


def wide():
    left = el("LinearLayout", {"layout_width": "0dp", "layout_height": "match_parent", "layout_weight": "1",
                               "orientation": "vertical"}, 8,
              [date(12, 14), time(12), current_row(12, 18, 18, 12)])
    right = el("LinearLayout", {"layout_width": "0dp", "layout_height": "match_parent", "layout_weight": "1.2",
                                "layout_marginStart": "16dp", "gravity": "center_vertical",
                                "orientation": "vertical"}, 8,
               [forecast_row(12, n, 26, 13, 16, 28, 30) for n in range(4)])
    content = el("LinearLayout", {"layout_width": "match_parent", "layout_height": "match_parent",
                                  "orientation": "horizontal", "padding": "16dp"}, 4, [left, right])
    return root("5×2: hora y clima a la izquierda, próximos días a la derecha (como el widget de Clima de iOS).", content)


def xl():
    top = el("LinearLayout", {"layout_width": "match_parent", "layout_height": "wrap_content",
                              "gravity": "center_vertical", "orientation": "horizontal"}, 8, [
        date(12, 15, width="0dp", weight="1"),
        text(12, "city", "WidgetText.Temp", 13, "@string/sample_city", extra={"layout_marginStart": "8dp"}),
        el("ImageView", {"id": "@+id/city_arrow", "layout_width": "10dp", "layout_height": "10dp",
                         "layout_marginStart": "4dp", "importantForAccessibility": "no",
                         "src": "@drawable/ic_location_arrow"}, 12),
    ])
    kids = [top, time(8), current_row(8, 22, 22, 14, hilo_size=13), divider(8)]
    kids += [forecast_row(8, n, 26, 15, 20, 36, 100) for n in range(5)]
    return root("5×3 o más: hora, clima actual con ciudad y pronóstico de 5 días.", column(4, kids))


if __name__ == "__main__":
    out = sys.argv[1]
    for name, fn in [("widget_time", time_block), ("widget_small", small), ("widget_medium", medium), ("widget_large", large),
                     ("widget_wide", wide), ("widget_xl", xl)]:
        with open(os.path.join(out, name + ".xml"), "w") as f:
            f.write(fn())
    print("ok")
