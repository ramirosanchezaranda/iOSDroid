package com.ramiro.iosdroid

import android.app.PendingIntent
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

    enum class Variant(val layout: Int) {
        SMALL(R.layout.widget_small),
        MEDIUM(R.layout.widget_medium),
        LARGE(R.layout.widget_large),
    }

    /** Tamaño por defecto (3×2) cuando el launcher no informa medidas. */
    private const val DEFAULT_W = 220f
    private const val DEFAULT_H = 180f

    fun variantFor(widthDp: Float, heightDp: Float): Variant = when {
        widthDp >= 175f && heightDp >= 235f -> Variant.LARGE
        widthDp >= 190f -> Variant.MEDIUM
        else -> Variant.SMALL
    }

    /** Todo lo que hace falta para dibujar, leído una vez por actualización. */
    class State(
        val style: GlassStyle,
        val tone: TextTone,
        val place: Place?,
        val weather: Weather?,
    )

    fun loadState(context: Context): State {
        val prefs = Prefs(context)
        return State(prefs.style, resolveTone(context, prefs.style, prefs.textTone), prefs.place, prefs.weather)
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
        rv.setTextColor(R.id.time, primary)
        rv.setTextColor(R.id.temp, primary)
        rv.setTextColor(R.id.date, secondary)
        rv.setTextColor(R.id.condition, secondary)
        rv.setTextColor(R.id.hilo, tertiary)
        rv.setInt(R.id.wx_icon, "setColorFilter", primary)

        // Fecha en el idioma del teléfono: "martes, 13 de octubre" / "mar 13".
        val skeleton = if (variant == Variant.SMALL) "EEEd" else "EEEEdMMMM"
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        rv.setCharSequence(R.id.date, "setFormat12Hour", pattern)
        rv.setCharSequence(R.id.date, "setFormat24Hour", pattern)
        rv.setCharSequence(R.id.time, "setFormat12Hour", "h:mm")
        rv.setCharSequence(R.id.time, "setFormat24Hour", "HH:mm")

        // Clima.
        val weather = state.weather
        if (weather != null) {
            rv.setTextViewText(R.id.temp, deg(weather.temp))
            rv.setImageViewResource(R.id.wx_icon, WeatherCodes.icon(weather.code, weather.isDay))
            rv.setViewVisibility(R.id.wx_icon, View.VISIBLE)
            rv.setTextViewText(R.id.condition, context.getString(WeatherCodes.label(weather.code)))
            val hilo = if (variant == Variant.SMALL) R.string.hilo_short else R.string.hilo
            rv.setTextViewText(R.id.hilo, context.getString(hilo, deg(weather.max), deg(weather.min)))
            rv.setViewVisibility(R.id.hilo, View.VISIBLE)
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
            Variant.LARGE -> {
                rv.setTextColor(R.id.city, primary)
                rv.setInt(R.id.city_arrow, "setColorFilter", primary)
                rv.setInt(R.id.divider, "setColorFilter", primary)
                val place = state.place
                if (place == null) {
                    rv.setViewVisibility(R.id.city, View.GONE)
                    rv.setViewVisibility(R.id.city_arrow, View.GONE)
                } else {
                    rv.setTextViewText(R.id.city, place.name.ifBlank { context.getString(R.string.my_location) })
                    rv.setViewVisibility(R.id.city_arrow, if (place.auto) View.VISIBLE else View.GONE)
                }
            }
        }

        // Toques: hora → alarmas, fecha → calendario, clima (y el resto) → la app.
        val app = appIntent(context)
        rv.setOnClickPendingIntent(android.R.id.background, app)
        rv.setOnClickPendingIntent(R.id.weather, app)
        rv.setOnClickPendingIntent(R.id.time, clockIntent(context) ?: app)
        rv.setOnClickPendingIntent(R.id.date, calendarIntent(context) ?: app)
        return rv
    }

    fun deg(value: Double) = "${value.roundToInt()}°"

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
