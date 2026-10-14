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
    /** Al tocar una notificación (null: no se pueden tocar, como en el protector de pantalla). */
    private val onOpenNotification: ((NotifItem) -> Unit)? = null,
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
    private var shownKeys = emptySet<String>()
    private var glassMode = false
    private var darkText = false
    private val notificationsListener: (List<NotifItem>) -> Unit = { bind() }

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
            renderNotifications(NotificationsHub.items)
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
        glassMode = glass
        darkText = tone == TextTone.DARK

        val dim = prefs.saverDim
        content.alpha = if (dim) 0.6f else 1f
        notifications.alpha = content.alpha
        if (controlBrightness) {
            window.attributes = window.attributes.apply {
                screenBrightness = if (dim) 0.03f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }

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
        renderNotifications(NotificationsHub.items)
    }

    // --- Notificaciones (estilo pantalla de bloqueo del iPhone) ---

    private fun renderNotifications(all: List<NotifItem>) {
        val landscape = forecast.orientation == LinearLayout.VERTICAL
        notifications.removeAllViews()
        val list = if (prefs.clockNotifications) all else emptyList()
        if (list.isEmpty()) {
            notifications.visibility = View.GONE
            shownKeys = emptySet()
            return
        }
        notifications.visibility = View.VISIBLE
        // En horizontal no entra todo: las notificaciones ocupan el lugar del pronóstico.
        if (landscape) forecast.visibility = View.GONE
        val max = if (landscape) 2 else 3
        val locked = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        val shown = list.take(max)
        val animate = ValueAnimator.areAnimatorsEnabled()
        shown.forEachIndexed { i, item ->
            val card = card(item, locked, maxLines = if (landscape) 1 else 2)
            notifications.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP).apply {
                if (i > 0) topMargin = dp(8)
            })
            // Las nuevas entran deslizándose desde abajo.
            if (animate && shownKeys.isNotEmpty() && item.key !in shownKeys) {
                card.alpha = 0f
                card.translationY = dp(24).toFloat()
                card.animate().alpha(1f).translationY(0f).setDuration(380)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
            }
        }
        if (list.size > max) notifications.addView(stackFooter(list.size - max))
        shownKeys = shown.map { it.key }.toSet()
    }

    private fun cardColor(): Int = when {
        !glassMode -> Color.argb(235, 44, 44, 46)
        darkText -> Color.argb(150, 255, 255, 255)
        else -> Color.argb(60, 255, 255, 255)
    }

    private fun card(item: NotifItem, locked: Boolean, maxLines: Int): View {
        // Con el teléfono bloqueado, el contenido privado (o todo, si así se eligió) queda oculto.
        val hide = locked && (prefs.hideNotificationText || item.private)
        val title = if (hide) item.appName else item.title
        val text = if (hide) {
            item.publicText?.takeIf { it.isNotBlank() && it != item.appName } ?: context.getString(R.string.notif_hidden)
        } else {
            item.text
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(cardColor())
                setStroke(maxOf(1, dp(1) / 2), Color.argb(if (glassMode) 90 else 26, 255, 255, 255))
            }
            contentDescription = "${item.appName}: $title. $text"
            addView(ImageView(context).apply {
                val icon = item.icon(context)?.takeIf { it.intrinsicWidth > 0 }
                if (icon != null) setImageDrawable(icon) else setImageBitmap(letterIcon(item.appName, dp(38)))
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(9).toFloat())
                    }
                }
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(title, 15f, primary, 600).apply { ellipsize = android.text.TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(label(ago(item.postTime), 13f, secondary, 400),
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                })
                if (text.isNotBlank()) {
                    addView(label(text, 15f, ColorUtils.setAlphaComponent(primary, 0xE6), 400).apply {
                        this.maxLines = maxLines
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setLineSpacing(0f, 1.1f)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP).apply { topMargin = dp(3) })
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            setOnTouchListener(CardTouch(item))
        }
    }

    /** Si no se puede leer el ícono de la app: su inicial sobre un color propio de la app. */
    private fun letterIcon(name: String, size: Int): android.graphics.Bitmap {
        val palette = intArrayOf(0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(), 0xFF5AC8FA.toInt())
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[Math.floorMod(name.hashCode(), palette.size)] }
        val r = size * 0.24f
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, paint)
        paint.color = Color.WHITE
        paint.textSize = size * 0.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        val letter = name.trim().take(1).uppercase().ifBlank { "•" }
        val fm = paint.fontMetrics
        canvas.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint)
        return bmp
    }

    /** Pila de tarjetas detrás de la última y "N notificaciones más". */
    private fun stackFooter(more: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val radius = dp(14).toFloat()
        listOf(14 to 0.7f, 28 to 0.4f).forEach { (inset, alpha) ->
            addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
                    setColor(cardColor())
                }
                this.alpha = alpha
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply {
                marginStart = dp(inset)
                marginEnd = dp(inset)
            })
        }
        addView(label(context.resources.getQuantityString(R.plurals.notif_more, more, more), 13f, secondary, 500),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
    }

    private fun ago(postTime: Long): String {
        val minutes = ((System.currentTimeMillis() - postTime) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> context.getString(R.string.notif_now)
            minutes < 60 -> context.getString(R.string.notif_minutes, minutes)
            minutes < 6 * 60 -> context.getString(R.string.notif_hours, minutes / 60)
            else -> DateFormat.getTimeFormat(context).format(java.util.Date(postTime))
        }
    }

    /** Tocar abre la notificación; deslizar hacia un costado la borra (como en iOS). */
    private inner class CardTouch(private val item: NotifItem) : View.OnTouchListener {
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!dragging && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(e.rawY - downY)) {
                        dragging = true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        v.translationX = dx
                        v.alpha = 1f - minOf(1f, kotlin.math.abs(dx) / v.width) * 0.8f
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    val moved = kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop
                    when {
                        dragging && kotlin.math.abs(v.translationX) > v.width * 0.35f -> {
                            val direction = if (v.translationX > 0) 1 else -1
                            v.animate().translationX(direction * v.width.toFloat()).alpha(0f).setDuration(200)
                                .withEndAction { NotificationsHub.dismiss(item) }.start()
                        }
                        dragging -> v.animate().translationX(0f).alpha(1f).setDuration(220).start()
                        !moved -> onOpenNotification?.invoke(item)
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            return true
        }
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
        addView(label(Forecast.dayLabel(context, day, short = true), 15f, secondary, 500), LinearLayout.LayoutParams(WRAP, WRAP))
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
        val maxHeight = root.height * if (landscape) 0.62f else 0.34f
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
    }
}
