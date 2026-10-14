package com.ramiro.iosdroid

import android.app.PendingIntent
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.core.os.BundleCompat
import java.util.Locale
import kotlin.math.roundToInt

/** Arma las RemoteViews del widget para cada tamaño. */
object WidgetRenderer {

    /** Un layout por tamaño (generados con tools/gen_widget_layouts.py). */
    enum class Variant(
        val layout: Int,
        /** Muestra máx./mín. de hoy junto al clima actual (los grandes lo muestran en la fila "Hoy"). */
        val showsHilo: Boolean,
        val hasCity: Boolean,
        val hasDivider: Boolean,
        /** Filas de pronóstico que hay en el layout. */
        val forecastRows: Int,
        val barWidthDp: Int,
        val shortDayNames: Boolean,
        /** Tamaño del texto de la fecha (igual que en tools/gen_widget_layouts.py). */
        val dateSp: Int,
    ) {
        SMALL(R.layout.widget_small, true, false, false, 0, 0, true, 13),
        MEDIUM(R.layout.widget_medium, true, false, false, 0, 0, true, 14),
        LARGE(R.layout.widget_large, false, false, true, 4, 40, true, 15),
        WIDE(R.layout.widget_wide, false, false, false, 4, 30, true, 14),
        XL(R.layout.widget_xl, true, true, true, 5, 100, false, 15),
    }

    private class RowIds(val row: Int, val day: Int, val icon: Int, val min: Int, val bar: Int, val max: Int)

    private val ROWS = listOf(
        RowIds(R.id.f0_row, R.id.f0_day, R.id.f0_icon, R.id.f0_min, R.id.f0_bar, R.id.f0_max),
        RowIds(R.id.f1_row, R.id.f1_day, R.id.f1_icon, R.id.f1_min, R.id.f1_bar, R.id.f1_max),
        RowIds(R.id.f2_row, R.id.f2_day, R.id.f2_icon, R.id.f2_min, R.id.f2_bar, R.id.f2_max),
        RowIds(R.id.f3_row, R.id.f3_day, R.id.f3_icon, R.id.f3_min, R.id.f3_bar, R.id.f3_max),
        RowIds(R.id.f4_row, R.id.f4_day, R.id.f4_icon, R.id.f4_min, R.id.f4_bar, R.id.f4_max),
    )

    /** Tamaño por defecto (3×3) cuando el launcher no informa medidas. */
    private const val DEFAULT_W = 222f
    private const val DEFAULT_H = 273f

    fun variantFor(widthDp: Float, heightDp: Float): Variant = when {
        widthDp >= 330f && heightDp >= 235f -> Variant.XL
        widthDp >= 330f -> Variant.WIDE
        widthDp >= 175f && heightDp >= 235f -> Variant.LARGE
        widthDp >= 190f -> Variant.MEDIUM
        else -> Variant.SMALL
    }

    /** Cuántos días entran según el alto, para que la hora siga siendo lo principal. */
    fun forecastRowsFor(variant: Variant, heightDp: Float): Int = when (variant) {
        Variant.LARGE -> if (heightDp >= 300f) 4 else 3
        Variant.WIDE -> if (heightDp >= 170f) 4 else 3
        Variant.XL -> when {
            heightDp >= 340f -> 5
            heightDp >= 290f -> 4
            else -> 3
        }
        else -> 0
    }

    /** Todo lo que hace falta para dibujar, leído una vez por actualización. */
    class State(
        val style: GlassStyle,
        val tone: TextTone,
        val font: ClockFont,
        val place: Place?,
        val weather: Weather?,
        /** Fuente cargada por el usuario: la hora se dibuja como imagen. */
        val customFont: Typeface? = null,
        /** La fecha usa la familia de la fuente elegida. */
        val fontForDate: Boolean = true,
        /** Fuente propia para la fecha (se dibuja como imagen). */
        val customDateFont: Typeface? = null,
    )

    fun loadState(context: Context): State {
        val prefs = Prefs(context)
        return State(
            prefs.style, resolveTone(context, prefs.style, prefs.textTone), prefs.clockFont, prefs.place, prefs.weather,
            CustomFont.active(context), prefs.fontForDate, CustomFont.activeForDate(context),
        )
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, GlassClockWidget::class.java))
        if (ids.isEmpty()) return
        val state = loadState(context)
        ids.forEach { update(context, manager, it, state) }
    }

    fun update(context: Context, manager: AppWidgetManager, id: Int, state: State = loadState(context)) {
        manager.updateAppWidget(id, forOptions(context, manager.getAppWidgetOptions(id), state))
    }

    private fun forOptions(context: Context, options: Bundle, state: State): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            if (!sizes.isNullOrEmpty()) {
                // Un layout (y un vidrio a medida) por cada tamaño real que informa el launcher.
                return RemoteViews(sizes.distinct().take(4).associateWith { build(context, it.width, it.height, state) })
            }
        }
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat()
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat()
        if (minW <= 0f || maxH <= 0f) return build(context, DEFAULT_W, DEFAULT_H, state)
        val portrait = build(context, minW, maxH, state)
        val landscape = build(context, maxW.coerceAtLeast(minW), minH.coerceAtLeast(1f), state)
        return RemoteViews(landscape, portrait)
    }

    fun build(context: Context, widthDp: Float, heightDp: Float, state: State = loadState(context)): RemoteViews {
        val variant = variantFor(widthDp, heightDp)
        val rv = RemoteViews(context.packageName, variant.layout)
        val res = context.resources
        val density = res.displayMetrics.density

        // Vidrio dibujado a la medida exacta del widget.
        val wPx = (widthDp * density).roundToInt().coerceIn(32, 1400)
        val hPx = (heightDp * density).roundToInt().coerceIn(32, 1400)
        val radius = res.getDimension(R.dimen.widget_radius).let { if (it < 4f * density) 24f * density else it }
        rv.setImageViewBitmap(R.id.glass, GlassRenderer.render(wPx, hPx, radius, density, state.style))

        // Color del texto.
        val primary = if (state.tone == TextTone.DARK) Color.rgb(28, 28, 30) else Color.WHITE
        val secondary = ColorUtils.setAlphaComponent(primary, if (state.tone == TextTone.DARK) 0xCC else 0xD9)
        val tertiary = ColorUtils.setAlphaComponent(primary, if (state.tone == TextTone.DARK) 0x99 else 0xA6)
        // Hora: solo queda visible el TextClock con la fuente elegida, o la imagen con la fuente propia.
        val timeId = state.font.timeId
        val custom = state.customFont
        ClockFont.entries.forEach {
            rv.setViewVisibility(it.timeId, if (custom == null && it == state.font) View.VISIBLE else View.GONE)
        }
        if (custom != null) {
            val now = currentTime(context)
            val bitmap = CustomFont.timeBitmap(
                now, custom, primary, shadow = state.tone == TextTone.LIGHT, heightPx = (64 * density).roundToInt(),
            )
            rv.setImageViewBitmap(R.id.time_image, bitmap)
            rv.setContentDescription(R.id.time_image, now)
            rv.setViewVisibility(R.id.time_image, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.time_image, View.GONE)
        }
        rv.setTextColor(timeId, primary)
        rv.setTextColor(R.id.temp, primary)

        // Fecha: con la misma familia que la hora, o dibujada con la fuente propia.
        val dateId = if (state.fontForDate) state.font.dateId else R.id.date
        val customDate = state.customDateFont
        ClockFont.DATE_IDS.forEach {
            rv.setViewVisibility(it, if (customDate == null && it == dateId) View.VISIBLE else View.GONE)
        }
        rv.setTextColor(dateId, secondary)
        rv.setTextColor(R.id.condition, secondary)
        rv.setTextColor(R.id.hilo, tertiary)
        rv.setInt(R.id.wx_icon, "setColorFilter", primary)

        // Fecha en el idioma del teléfono: "martes, 13 de octubre" / "mar 13".
        val skeleton = if (variant == Variant.SMALL) "EEEd" else "EEEEdMMMM"
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        rv.setCharSequence(dateId, "setFormat12Hour", pattern)
        rv.setCharSequence(dateId, "setFormat24Hour", pattern)
        if (customDate != null) {
            val text = DateFormat.format(pattern, System.currentTimeMillis()).toString()
            val bitmap = CustomFont.lineBitmap(
                text, customDate, secondary, shadow = state.tone == TextTone.LIGHT,
                heightPx = (Math.round(variant.dateSp * 1.35f) * density).roundToInt(),
            )
            rv.setImageViewBitmap(R.id.date_image, bitmap)
            rv.setContentDescription(R.id.date_image, text)
            rv.setViewVisibility(R.id.date_image, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.date_image, View.GONE)
        }
        rv.setCharSequence(timeId, "setFormat12Hour", "h:mm")
        rv.setCharSequence(timeId, "setFormat24Hour", "HH:mm")

        // Clima actual.
        val weather = state.weather
        val days = weather?.let { Forecast.upcoming(it.days) }.orEmpty()
        if (weather != null) {
            val label = context.getString(WeatherCodes.label(weather.code))
            rv.setTextViewText(R.id.temp, deg(weather.temp))
            rv.setImageViewResource(R.id.wx_icon, WeatherCodes.icon(weather.code, weather.isDay))
            rv.setContentDescription(R.id.wx_icon, label)
            rv.setViewVisibility(R.id.wx_icon, View.VISIBLE)
            rv.setTextViewText(R.id.condition, label)
            val hilo = if (variant == Variant.SMALL) R.string.hilo_short else R.string.hilo
            rv.setTextViewText(R.id.hilo, context.getString(hilo, deg(weather.max), deg(weather.min)))
            val showHilo = variant.showsHilo || (variant.forecastRows > 0 && days.isEmpty())
            rv.setViewVisibility(R.id.hilo, if (showHilo) View.VISIBLE else View.GONE)
        } else {
            rv.setTextViewText(R.id.temp, context.getString(R.string.temp_unknown))
            rv.setViewVisibility(R.id.wx_icon, View.GONE)
            val hint = if (state.place == null) R.string.tap_to_setup else R.string.updating
            rv.setTextViewText(R.id.condition, context.getString(hint))
            rv.setViewVisibility(R.id.hilo, View.GONE)
        }

        // Ajustes finos según el espacio.
        val roomy = heightDp >= 150f || weather == null
        when (variant) {
            Variant.SMALL -> {
                rv.setViewVisibility(R.id.condition, if (roomy) View.VISIBLE else View.GONE)
                if (weather != null && widthDp < 135f) rv.setViewVisibility(R.id.hilo, View.GONE)
            }
            Variant.MEDIUM -> rv.setViewVisibility(R.id.condition, if (roomy) View.VISIBLE else View.GONE)
            else -> Unit
        }
        if (variant.hasCity) {
            rv.setTextColor(R.id.city, primary)
            rv.setInt(R.id.city_arrow, "setColorFilter", primary)
            val place = state.place
            if (place == null) {
                rv.setViewVisibility(R.id.city, View.GONE)
                rv.setViewVisibility(R.id.city_arrow, View.GONE)
            } else {
                rv.setTextViewText(R.id.city, place.name.ifBlank { context.getString(R.string.my_location) })
                rv.setViewVisibility(R.id.city_arrow, if (place.auto) View.VISIBLE else View.GONE)
            }
        }

        // Próximos días: "Hoy", "Mañana", "Jue"... con barra de temperatura.
        if (variant.forecastRows > 0) {
            val shown = days.take(forecastRowsFor(variant, heightDp))
            if (variant.hasDivider) {
                rv.setInt(R.id.divider, "setColorFilter", primary)
                rv.setViewVisibility(R.id.divider, if (shown.isEmpty()) View.GONE else View.VISIBLE)
            }
            val track = if (state.tone == TextTone.DARK) Color.argb(31, 0, 0, 0) else Color.argb(51, 255, 255, 255)
            val today = shown.firstOrNull()?.takeIf { Forecast.dayLabel(context, it, true) == context.getString(R.string.today) }
            val current = weather?.temp?.takeIf { today != null }
            val low = (shown.map { it.min } + listOfNotNull(current)).minOrNull() ?: 0.0
            val high = (shown.map { it.max } + listOfNotNull(current)).maxOrNull() ?: 1.0
            ROWS.take(variant.forecastRows).forEachIndexed { i, ids ->
                val day = shown.getOrNull(i)
                if (day == null || weather == null) {
                    rv.setViewVisibility(ids.row, View.GONE)
                    return@forEachIndexed
                }
                rv.setViewVisibility(ids.row, View.VISIBLE)
                rv.setTextViewText(ids.day, Forecast.dayLabel(context, day, variant.shortDayNames))
                rv.setTextColor(ids.day, primary)
                rv.setImageViewResource(ids.icon, WeatherCodes.icon(day.code, isDay = true))
                rv.setInt(ids.icon, "setColorFilter", primary)
                rv.setContentDescription(ids.icon, context.getString(WeatherCodes.label(day.code)))
                rv.setTextViewText(ids.min, deg(day.min))
                rv.setTextColor(ids.min, tertiary)
                rv.setTextViewText(ids.max, deg(day.max))
                rv.setTextColor(ids.max, primary)
                val bar = RangeBar.bitmap(
                    (variant.barWidthDp * density).roundToInt(), (12 * density).roundToInt(),
                    day.min, day.max, low, high, weather.unit, track, 4 * density,
                    current = if (day === today) current else null,
                )
                rv.setImageViewBitmap(ids.bar, bar)
            }
        }

        // Toques: hora → alarmas, fecha → calendario, clima (y el resto) → la app.
        val app = appIntent(context)
        rv.setOnClickPendingIntent(android.R.id.background, app)
        rv.setOnClickPendingIntent(R.id.weather, app)
        rv.setOnClickPendingIntent(R.id.time_box, clockIntent(context) ?: app)
        rv.setOnClickPendingIntent(R.id.date_box, calendarIntent(context) ?: app)
        return rv
    }

    fun deg(value: Double) = "${value.roundToInt()}°"

    fun currentTime(context: Context): String =
        DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", System.currentTimeMillis()).toString()

    /** En automático, usa texto oscuro solo si el fondo de pantalla es claro. */
    fun resolveTone(context: Context, style: GlassStyle, tone: TextTone): TextTone {
        if (tone != TextTone.AUTO) return tone
        if (style == GlassStyle.SMOKED) return TextTone.LIGHT
        val colors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull() ?: return TextTone.LIGHT
        val darkText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        } else {
            Color.luminance(colors.primaryColor.toArgb()) > 0.6f
        }
        return if (darkText) TextTone.DARK else TextTone.LIGHT
    }

    private fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun appIntent(context: Context) = activity(context, 0, Intent(context, MainActivity::class.java))

    private fun clockIntent(context: Context): PendingIntent? {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        return if (intent.resolveActivity(context.packageManager) != null) activity(context, 1, intent) else null
    }

    private fun calendarIntent(context: Context): PendingIntent? {
        val pm = context.packageManager
        val app = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        if (app.resolveActivity(pm) != null) return activity(context, 2, app)
        val today = Intent(
            Intent.ACTION_VIEW,
            CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build(),
        )
        return if (today.resolveActivity(pm) != null) activity(context, 3, today) else null
    }
}
