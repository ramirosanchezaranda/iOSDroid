package com.ramiro.iosdroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Pronóstico de los próximos días: nombres de los días y barras de temperatura estilo iOS. */
object Forecast {

    /** "Hoy" según el reloj del teléfono (los tests lo fijan en una fecha). */
    @Volatile
    var today: () -> LocalDate = { LocalDate.now() }

    /** Días desde hoy en adelante (si los datos son de ayer, se descarta lo viejo). */
    fun upcoming(days: List<DayForecast>, today: LocalDate = this.today()): List<DayForecast> =
        days.filter { runCatching { !LocalDate.parse(it.date).isBefore(today) }.getOrDefault(false) }

    /** "Hoy", "Mañana" y después el día de la semana ("Jue" o "Jueves"). */
    fun dayLabel(context: Context, day: DayForecast, short: Boolean, today: LocalDate = this.today()): String {
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return day.date
        return when (ChronoUnit.DAYS.between(today, date)) {
            0L -> context.getString(R.string.today)
            1L -> context.getString(R.string.tomorrow)
            else -> {
                val locale = Locale.getDefault()
                date.dayOfWeek.getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, locale)
                    .trimEnd('.')
                    .replaceFirstChar { it.titlecase(locale) }
            }
        }
    }
}

/**
 * Barra de rango de temperatura como la del widget de Clima de iOS: la pista representa el rango
 * de todos los días mostrados y el tramo de color, el mínimo y máximo de ese día.
 */
object RangeBar {

    // Colores del sistema de iOS (variante oscura) asignados a temperaturas en °C.
    private val STOPS_C = floatArrayOf(-10f, 0f, 10f, 17f, 23f, 29f, 36f)
    private val STOP_COLORS = intArrayOf(
        0xFF5E5CE6.toInt(), // indigo
        0xFF0A84FF.toInt(), // blue
        0xFF64D2FF.toInt(), // teal
        0xFF30D158.toInt(), // green
        0xFFFFD60A.toInt(), // yellow
        0xFFFF9F0A.toInt(), // orange
        0xFFFF453A.toInt(), // red
    )

    fun colorFor(temp: Double, unit: TempUnit): Int {
        val c = (if (unit == TempUnit.FAHRENHEIT) (temp - 32) * 5 / 9 else temp).toFloat()
        if (c <= STOPS_C.first()) return STOP_COLORS.first()
        if (c >= STOPS_C.last()) return STOP_COLORS.last()
        val i = STOPS_C.indexOfFirst { it > c }
        val f = (c - STOPS_C[i - 1]) / (STOPS_C[i] - STOPS_C[i - 1])
        return blend(STOP_COLORS[i - 1], STOP_COLORS[i], f)
    }

    /**
     * Dibuja la barra centrada verticalmente en [area]. [current], si se pasa, es la temperatura
     * actual y se marca con un punto (solo para "Hoy").
     */
    fun draw(
        canvas: Canvas,
        area: RectF,
        low: Double,
        high: Double,
        scaleLow: Double,
        scaleHigh: Double,
        unit: TempUnit,
        track: Int,
        barHeight: Float,
        current: Double? = null,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val span = (scaleHigh - scaleLow).takeIf { it > 0.01 } ?: 1.0
        val r = barHeight / 2
        val cy = area.centerY()
        val left = area.left + r
        val right = area.right - r
        fun x(t: Double) = (left + ((t - scaleLow) / span).toFloat().coerceIn(0f, 1f) * (right - left))

        p.color = track
        canvas.drawRoundRect(RectF(area.left, cy - r, area.right, cy + r), r, r, p)

        val samples = 8
        val colors = IntArray(samples) { colorFor(scaleLow + span * it / (samples - 1), unit) }
        // El alfa del color de la pintura también afecta al degradé: tiene que ser opaco.
        p.color = Color.WHITE
        p.shader = LinearGradient(x(scaleLow), 0f, x(scaleHigh), 0f, colors, null, Shader.TileMode.CLAMP)
        val x1 = x(min(low, high))
        val x2 = max(x(max(low, high)), x1 + 0.01f)
        canvas.drawRoundRect(RectF(x1 - r, cy - r, x2 + r, cy + r), r, r, p)
        p.shader = null

        if (current != null) {
            val cx = x(current)
            val dot = barHeight * 0.95f
            p.color = Color.argb(110, 0, 0, 0)
            canvas.drawCircle(cx, cy, dot + barHeight * 0.35f, p)
            p.color = Color.WHITE
            canvas.drawCircle(cx, cy, dot, p)
        }
    }

    fun bitmap(
        widthPx: Int,
        heightPx: Int,
        low: Double,
        high: Double,
        scaleLow: Double,
        scaleHigh: Double,
        unit: TempUnit,
        track: Int,
        barHeight: Float,
        current: Double?,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(4), heightPx.coerceAtLeast(4), Bitmap.Config.ARGB_8888)
        val area = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        draw(Canvas(bmp), area, low, high, scaleLow, scaleHigh, unit, track, barHeight, current)
        return bmp
    }

    private fun blend(a: Int, b: Int, f: Float): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )
}
