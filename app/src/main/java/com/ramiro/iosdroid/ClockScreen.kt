package com.ramiro.iosdroid

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pantalla de reloj a pantalla completa (hora gigante, fecha y clima). La usan el modo reloj
 * ([ClockActivity]) y el protector de pantalla del sistema ([ClockDreamService]).
 *
 * Para no marcar pantallas OLED cuando queda encendida horas, el contenido se mueve un poco
 * cada minuto.
 */
class ClockScreen(
    private val context: Context,
    private val window: Window,
    private val root: View,
    /** El protector de pantalla maneja el brillo con [android.service.dreams.DreamService.setScreenBright]. */
    private val controlBrightness: Boolean,
    /** Qué hacer con las notificaciones (null: solo se ven, como en el protector de pantalla). */
    host: NotificationHost? = null,
) {
    private val prefs = Prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private val content: View = root.findViewById(R.id.ss_content)
    private val time: TextClock = root.findViewById(R.id.ss_time)
    private val date: TextClock = root.findViewById(R.id.ss_date)
    private val icon: ImageView = root.findViewById(R.id.ss_icon)
    private val temp: TextView = root.findViewById(R.id.ss_temp)
    private val condition: TextView = root.findViewById(R.id.ss_condition)
    private val detail: TextView = root.findViewById(R.id.ss_detail)
    private val forecast: LinearLayout = root.findViewById(R.id.ss_forecast)
    private val notifications: LinearLayout = root.findViewById(R.id.ss_notifications)
    /** Pilas, centro de notificaciones y gestos. */
    val notificationsUi = NotificationsUi(context, root as android.view.ViewGroup, notifications, host)
    // Solo se redibujan las notificaciones (no todo el reloj) cuando llega o se va una.
    private val notificationsListener: (List<NotifItem>) -> Unit = { renderNotifications() }

    private var primary = Color.WHITE
    private var secondary = Color.WHITE
    private var tertiary = Color.WHITE

    private var fittedWidth = -1
    private var glassSize = 0 to 0

    // Al guardar el clima se escriben varias claves seguidas: se agrupan en un solo redibujo.
    private val bindSoon = Runnable { bind() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.removeCallbacks(bindSoon)
        handler.postDelayed(bindSoon, 150)
    }

    private val drift = object : Runnable {
        override fun run() {
            val parent = content.parent as View
            val maxX = ((parent.width - content.width) / 2f).coerceIn(0f, 48 * density)
            // Que el reloj no se meta encima de las notificaciones.
            val bottomLimit = notifications.takeIf { it.visibility == View.VISIBLE && it.parent === parent }?.top ?: parent.height
            val maxY = minOf(content.top, bottomLimit - content.bottom).toFloat().coerceIn(0f, 90 * density)
            // Con "Reducir movimiento" (animaciones desactivadas) se mueve sin animar.
            content.animate()
                .translationX(if (maxX > 0) Random.nextFloat() * 2 * maxX - maxX else 0f)
                .translationY(if (maxY > 0) Random.nextFloat() * 2 * maxY - maxY else 0f)
                .setDuration(if (ValueAnimator.areAnimatorsEnabled()) 2_500 else 0)
                .start()
            // Cada minuto también se actualiza "hace 5 min" en las notificaciones.
            renderNotifications()
            handler.postDelayed(this, DRIFT_MS)
        }
    }

    private val refresher = object : Runnable {
        override fun run() {
            WeatherWorker.refreshIfStale(context)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        date.format12Hour = pattern
        date.format24Hour = pattern
        // Se ajusta en el frame siguiente: cambiar el tamaño en medio de un layout no siempre se aplica.
        time.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> time.post { fitTime(force = false) } }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> renderGlass(force = false) }
        forecast.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> forecast.post { fitDayLabels() } }
        bind()
    }

    fun start() {
        prefs.listen(prefsListener)
        NotificationsHub.listen(notificationsListener)
        bind()
        handler.postDelayed(drift, DRIFT_MS)
        handler.post(refresher)
    }

    fun stop() {
        prefs.unlisten(prefsListener)
        NotificationsHub.unlisten(notificationsListener)
        handler.removeCallbacksAndMessages(null)
        content.animate().cancel()
        notificationsUi.closeAll()
    }

    fun bind() {
        val font = prefs.clockFont
        val custom = CustomFont.active(context)
        time.typeface = custom ?: font.typeface()
        time.letterSpacing = if (custom != null) -0.01f else font.letterSpacing
        // La fecha con la misma fuente (si está activado "Usar también en la fecha").
        date.typeface = when {
            !prefs.fontForDate -> ClockFont.LIGHT.dateTypeface()
            else -> CustomFont.activeForDate(context) ?: font.dateTypeface()
        }
        fitTime(force = true)

        // Fondo: negro puro (ideal OLED) o tarjeta de vidrio sobre tu fondo de pantalla.
        val glass = prefs.saverBackground == SaverBackground.GLASS
        val tone = if (glass) WidgetRenderer.resolveTone(context, prefs.style, prefs.textTone) else TextTone.LIGHT
        if (glass) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            root.setBackgroundColor(if (tone == TextTone.LIGHT) 0x33000000 else Color.TRANSPARENT)
            renderGlass(force = true)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            root.setBackgroundColor(Color.BLACK)
            content.background = null
        }
        applyColors(if (tone == TextTone.DARK) Color.rgb(28, 28, 30) else Color.WHITE)
        notificationsUi.setStyle(glass, tone == TextTone.DARK, primary, landscape = forecast.orientation == LinearLayout.VERTICAL)

        applyDim()

        val weather = prefs.weather
        val city = prefs.place?.name?.ifBlank { null }
        if (weather != null) {
            icon.visibility = View.VISIBLE
            icon.setImageResource(WeatherCodes.icon(weather.code, weather.isDay))
            temp.text = WidgetRenderer.deg(weather.temp)
            condition.setText(WeatherCodes.label(weather.code))
            val hilo = context.getString(R.string.hilo, WidgetRenderer.deg(weather.max), WidgetRenderer.deg(weather.min))
            detail.text = listOfNotNull(hilo, city).joinToString("  ·  ")
            icon.contentDescription = condition.text
        } else {
            icon.visibility = View.GONE
            temp.setText(R.string.temp_unknown)
            condition.setText(if (prefs.place == null) R.string.saver_no_location else R.string.updating)
            detail.text = city.orEmpty()
        }
        bindForecast(weather)
        renderNotifications(force = true)
    }

    /**
     * Ahorro de batería: brillo al mínimo y contenido más tenue (en OLED, menos píxeles
     * encendidos). Lo activa [ClockActivity] cuando nadie lo toca y no hay cargador.
     */
    var batterySaving = false
        set(value) {
            if (field == value) return
            field = value
            applyDim()
        }

    private fun applyDim() {
        val dim = prefs.saverDim || batterySaving
        content.alpha = when {
            batterySaving -> 0.45f
            dim -> 0.6f
            else -> 1f
        }
        notifications.alpha = content.alpha
        if (controlBrightness) {
            window.attributes = window.attributes.apply {
                screenBrightness = when {
                    batterySaving -> 0.01f
                    dim -> 0.03f
                    else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // --- Notificaciones (ver [NotificationsUi]) ---

    private fun renderNotifications(force: Boolean = false) {
        notificationsUi.maxStacks = stacksThatFit()
        notificationsUi.update(NotificationsHub.items, force)
        setCompact(notificationsUi.hasItems)
        // Con notificaciones, ellas ocupan el lugar del pronóstico.
        forecast.visibility = if (notificationsUi.hasItems || forecast.childCount == 0) View.GONE else View.VISIBLE
    }

    private val landscape get() = forecast.orientation == LinearLayout.VERTICAL

    /**
     * Con notificaciones, como en la pantalla de bloqueo del iPhone: el reloj se achica y sube,
     * el clima queda en una línea y las notificaciones ocupan el resto de la pantalla.
     * Sin notificaciones, vuelve el reloj grande.
     */
    private var compact: Boolean? = null

    private fun setCompact(on: Boolean) {
        if (compact == on) return
        val first = compact == null
        compact = on
        if (!first && ValueAnimator.areAnimatorsEnabled() && root.isLaidOut) {
            android.transition.TransitionManager.beginDelayedTransition(root as ViewGroup, android.transition.TransitionSet().apply {
                addTransition(android.transition.ChangeBounds())
                addTransition(android.transition.Fade())
                duration = 320
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            })
        }
        if (landscape) {
            (time.layoutParams as LinearLayout.LayoutParams).weight = if (on) 1.1f else 1.7f
            date.textSize = if (on) 17f else 20f
            temp.textSize = if (on) 30f else 40f
            condition.textSize = if (on) 16f else 19f
            detail.visibility = if (on) View.GONE else View.VISIBLE
        } else {
            root.findViewById<View>(R.id.ss_top_space).updateWeight(if (on) 0f else 1f, dp(if (on) 20 else 0))
            root.findViewById<View>(R.id.ss_bottom_space).updateWeight(if (on) 0f else 1f, dp(if (on) 14 else 0))
            notifications.updateWeight(if (on) 1f else 0f, if (on) 0 else ViewGroup.LayoutParams.WRAP_CONTENT)
            val pad = dp(if (on) 14 else 30)
            content.setPadding(content.paddingLeft, pad, content.paddingRight, pad)
            date.textSize = if (on) 17f else 22f
            temp.textSize = if (on) 19f else 26f
            condition.textSize = if (on) 15f else 20f
            detail.visibility = if (on) View.GONE else View.VISIBLE
            val iconSize = dp(if (on) 22 else 30)
            icon.layoutParams = icon.layoutParams.apply { width = iconSize; height = iconSize }
        }
        content.requestLayout()
        time.post { fitTime(force = true) }
    }

    private fun View.updateWeight(weight: Float, height: Int) {
        val lp = layoutParams as LinearLayout.LayoutParams
        if (lp.weight == weight && lp.height == height) return
        lp.weight = weight
        lp.height = height
        layoutParams = lp
    }

    /** Pilas que entran debajo del reloj chico según el alto de la pantalla (entre 3 y 6). */
    private fun stacksThatFit(): Int {
        val heightDp = (root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels) / density
        return ((heightDp - CLOCK_COMPACT_DP) / STACK_DP).toInt().coerceIn(3, 6)
    }

    /** Que "Mañana" entre en su columna aunque la fuente sea ancha (todas del mismo tamaño, como en iOS). */
    private fun fitDayLabels() {
        if (forecast.orientation != LinearLayout.HORIZONTAL || forecast.childCount == 0) return
        val labels = (0 until forecast.childCount).mapNotNull { (forecast.getChildAt(it) as? ViewGroup)?.getChildAt(0) as? TextView }
        val column = forecast.getChildAt(0).width
        if (labels.isEmpty() || column <= 0) return
        val full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DAY_SP, context.resources.displayMetrics)
        val paint = Paint(labels[0].paint).apply { textSize = full }
        val widest = labels.maxOf { paint.measureText(it.text.toString()) }
        val available = column - dp(4)
        val size = if (widest > available) maxOf(full * 10f / DAY_SP, full * available / widest) else full
        labels.forEach { if (kotlin.math.abs(it.textSize - size) > 0.5f) it.setTextSize(TypedValue.COMPLEX_UNIT_PX, size) }
    }

    /** Próximos días: columnas en vertical, filas con barra de temperatura en horizontal. */
    private fun bindForecast(weather: Weather?) {
        forecast.removeAllViews()
        val days = weather?.let { Forecast.upcoming(it.days) }.orEmpty()
        if (weather == null || days.isEmpty()) {
            forecast.visibility = View.GONE
            return
        }
        forecast.visibility = View.VISIBLE
        if (forecast.orientation == LinearLayout.HORIZONTAL) {
            days.take(5).forEach { forecast.addView(dayColumn(it), LinearLayout.LayoutParams(0, WRAP, 1f)) }
        } else {
            val shown = days.take(4)
            val today = shown.first().takeIf { Forecast.dayLabel(context, it, true) == context.getString(R.string.today) }
            val current = weather.temp.takeIf { today != null }
            val low = (shown.map { it.min } + listOfNotNull(current)).min()
            val high = (shown.map { it.max } + listOfNotNull(current)).max()
            shown.forEach { day ->
                forecast.addView(
                    dayRow(day, low, high, weather.unit, if (day === today) current else null),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)),
                )
            }
        }
    }

    private fun dayColumn(day: DayForecast) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(label(Forecast.dayLabel(context, day, short = true), DAY_SP, secondary, 500), LinearLayout.LayoutParams(WRAP, WRAP))
        addView(ImageView(context).apply {
            setImageResource(WeatherCodes.icon(day.code, isDay = true))
            setColorFilter(primary)
            contentDescription = context.getString(WeatherCodes.label(day.code))
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(8) })
        // Probabilidad de lluvia en celeste, como en iOS (solo si es relevante).
        addView(label("${day.precip}%", 13f, RAIN_COLOR, 600).apply {
            visibility = if (day.precip >= 20) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        addView(label(WidgetRenderer.deg(day.max), 19f, primary, 500), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        addView(label(WidgetRenderer.deg(day.min), 16f, tertiary, 400), LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private fun dayRow(day: DayForecast, low: Double, high: Double, unit: TempUnit, current: Double?) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(Forecast.dayLabel(context, day, short = true), 17f, primary, 500), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ImageView(context).apply {
                setImageResource(WeatherCodes.icon(day.code, isDay = true))
                setColorFilter(primary)
                contentDescription = context.getString(WeatherCodes.label(day.code))
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(label(WidgetRenderer.deg(day.min), 17f, tertiary, 400).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(40), WRAP))
            val track = Color.argb(51, 255, 255, 255)
            addView(RangeBarView(day.min, day.max, low, high, unit, track, current),
                LinearLayout.LayoutParams(dp(72), dp(12)).apply { marginStart = dp(10); marginEnd = dp(6) })
            addView(label(WidgetRenderer.deg(day.max), 17f, primary, 500).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(40), WRAP))
        }

    private fun label(value: String, sizeSp: Float, color: Int, weight: Int) = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        maxLines = 1
        includeFontPadding = false
        typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, weight, false)
        } else {
            Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    private inner class RangeBarView(
        private val low: Double,
        private val high: Double,
        private val scaleLow: Double,
        private val scaleHigh: Double,
        private val unit: TempUnit,
        private val track: Int,
        private val current: Double?,
    ) : View(context) {
        override fun onDraw(canvas: Canvas) {
            val area = RectF(0f, 0f, width.toFloat(), height.toFloat())
            RangeBar.draw(canvas, area, low, high, scaleLow, scaleHigh, unit, track, 5 * density, current)
        }
    }

    private fun applyColors(color: Int) {
        primary = color
        secondary = ColorUtils.setAlphaComponent(color, 0xD9)
        tertiary = ColorUtils.setAlphaComponent(color, 0xA6)
        time.setTextColor(primary)
        temp.setTextColor(primary)
        date.setTextColor(secondary)
        condition.setTextColor(secondary)
        detail.setTextColor(tertiary)
        icon.setColorFilter(primary)
    }

    /** Tamaño de la hora: lo más grande que entre a lo ancho, sin pasarse de alto. */
    private fun fitTime(force: Boolean) {
        val available = time.width - time.paddingLeft - time.paddingRight
        if (available <= 0 || (!force && available == fittedWidth)) return
        fittedWidth = available
        val paint = Paint(time.paint).apply { textSize = 100f }
        val widest = listOf("00:00", "88:88", "22:22", "44:44", "12:59").maxOf { paint.measureText(it) }
        var size = 100f * available / widest * 0.97f
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val small = compact == true
        // Con notificaciones la hora cede lugar (y no ocupa todo el ancho).
        if (small && !landscape) size *= 0.72f
        val maxHeight = root.height * when {
            landscape && small -> 0.42f
            landscape -> 0.52f
            small -> 0.15f
            else -> 0.27f
        }
        if (maxHeight > 0) size = min(size, maxHeight / 1.2f)
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    private fun renderGlass(force: Boolean) {
        if (prefs.saverBackground != SaverBackground.GLASS) return
        val w = content.width
        val h = content.height
        if (w <= 0 || h <= 0 || (!force && glassSize == w to h)) return
        glassSize = w to h
        val bitmap = GlassRenderer.render(w, h, 36f * density, density, prefs.style)
        content.background = BitmapDrawable(context.resources, bitmap)
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val RAIN_COLOR = 0xFF64D2FF.toInt()
        const val DRIFT_MS = 60_000L
        const val REFRESH_MS = 15 * 60_000L
        const val DAY_SP = 15f
        /** Alto aproximado del reloj chico (fecha, hora y clima) y de cada pila de notificaciones. */
        const val CLOCK_COMPACT_DP = 290f
        const val STACK_DP = 104f
    }
}
