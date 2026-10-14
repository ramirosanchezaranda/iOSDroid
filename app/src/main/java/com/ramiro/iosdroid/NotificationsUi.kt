package com.ramiro.iosdroid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateFormat
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lo que el reloj sabe hacer con una notificación. En el protector de pantalla no hay host: solo se ven. */
interface NotificationHost {
    /** El teléfono está bloqueado (el contenido privado se oculta). */
    fun locked(): Boolean

    /** Abre la notificación en su app; si está bloqueado, primero pide huella o PIN. */
    fun open(item: NotifItem)

    /** Abre una pantalla (por ejemplo, Ajustes), desbloqueando antes si hace falta. */
    fun launch(intent: Intent)

    /** Corre algo que la app pidió hacer con el teléfono desbloqueado. */
    fun unlockThen(action: () -> Unit)

    /** Se abrió o se cerró un panel (para que Atrás lo cierre primero). */
    fun onOverlayChanged(open: Boolean) {}
}

/**
 * Notificaciones del reloj con los gestos del iPhone, más lo bueno de Android:
 *
 * - Agrupadas por app en pilas; tocar una pila la despliega y tocar una notificación la abre.
 * - Deslizar hacia arriba abre el centro de notificaciones (todas, con scroll y "Borrar todo");
 *   hacia abajo, o Atrás, lo cierra.
 * - Deslizar hacia la izquierda muestra "Opciones" y "Borrar"; hasta el final, borra.
 *   Hacia la derecha, abre.
 * - Mantener presionada la amplía: texto completo, imagen, los botones de la app y responder
 *   sin abrirla (Android).
 * - Opciones: posponer (Android), silenciar en el reloj por 1 hora y ajustes de la app.
 */
class NotificationsUi(
    private val context: Context,
    private val root: ViewGroup,
    private val inline: LinearLayout,
    private val host: NotificationHost?,
) {
    private val prefs = Prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val fling = config.scaledMinimumFlingVelocity * 6f
    private val interactive get() = host != null
    private val animations get() = ValueAnimator.areAnimatorsEnabled()

    private var glass = false
    private var darkText = false
    private var primary = Color.WHITE
    private var landscape = false

    private var items: List<NotifItem> = emptyList()
    private var shownKeys = emptySet<String>()
    private var openRow: SwipeRow? = null
    private var touching = 0
    private var pending = false

    private var center: Center? = null
    private var sheet: Sheet? = null
    private var centerProgress = 0f
    private var sheetProgress = 0f

    /** Hay notificaciones para mostrar (ya filtradas por los ajustes). */
    val hasItems get() = items.isNotEmpty()
    val overlayOpen get() = center != null || sheet != null
    val centerOpen get() = center != null

    fun setStyle(glass: Boolean, darkText: Boolean, primary: Int, landscape: Boolean) {
        this.glass = glass
        this.darkText = darkText
        this.primary = primary
        this.landscape = landscape
    }

    fun update(all: List<NotifItem>) {
        items = if (prefs.clockNotifications) NotificationsHub.visible(context, all) else emptyList()
        // No se redibuja debajo del dedo ni con una tarjeta abierta: se hace al soltar.
        if (touching > 0 || openRow != null) {
            pending = true
            return
        }
        render()
    }

    private fun flush() {
        if (pending && touching == 0 && openRow == null) render()
    }

    private fun render() {
        pending = false
        renderInline()
        center?.let { if (items.isEmpty()) closeCenter() else it.render() }
        sheet?.let { s -> if (items.none { it.key in s.keys }) closeSheet() }
    }

    // --- Debajo del reloj ---

    private fun renderInline() {
        val groups = NotificationsHub.groups(items)
        val maxStacks = if (landscape) 2 else 3
        val shown = groups.take(maxStacks)
        val keys = shown.flatMap { group -> group.map { it.key } }.toSet()
        val entering = shown.filter { shownKeys.isNotEmpty() && it.first().key !in shownKeys }.map { it.first().key }.toSet()
        if (animations && entering.isEmpty() && inline.isLaidOut && inline.childCount > 0) {
            TransitionManager.beginDelayedTransition(inline, moveTransition())
        }
        inline.removeAllViews()
        if (groups.isEmpty()) {
            inline.visibility = View.GONE
            shownKeys = emptySet()
            return
        }
        inline.visibility = View.VISIBLE
        val palette = inlinePalette()
        val locked = isLocked()
        shown.forEachIndexed { i, group ->
            val row = stack(group, palette, locked, maxLines = if (landscape) 1 else 2) {
                if (group.size > 1) openCenter(group.first().packageName) else host?.open(group.first())
            }
            row.transitionName = "inline:" + group.first().packageName
            inline.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (i > 0) topMargin = dp(8) })
            // Las nuevas entran deslizándose desde abajo.
            if (animations && group.first().key in entering) {
                row.alpha = 0f
                row.translationY = dp(24).toFloat()
                row.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(OvershootInterpolator(0.8f)).start()
            }
        }
        val hidden = groups.drop(maxStacks).sumOf { it.size }
        if (hidden > 0) {
            inline.addView(TextView(context).apply {
                text = context.resources.getQuantityString(R.plurals.notif_more, hidden, hidden)
                style(13f, palette.secondary, 500)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(4))
                transitionName = "inline:footer"
                if (interactive) setOnClickListener { openCenter(null) }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        shownKeys = keys
    }

    // --- Centro de notificaciones (deslizar hacia arriba) ---

    fun canOpenCenter() = interactive && items.isNotEmpty() && !overlayOpen

    /** El dedo empezó a subir: el centro sigue al dedo desde abajo. */
    fun beginCenterDrag(): Boolean {
        if (!canOpenCenter()) return false
        closeRevealed()
        createCenter(null).frame.translationY = root.height.toFloat()
        return true
    }

    fun dragCenter(dy: Float) {
        val c = center ?: return
        val h = root.height.toFloat().coerceAtLeast(1f)
        c.frame.translationY = (h + dy).coerceIn(0f, h)
        setCenterProgress(1f - c.frame.translationY / h)
    }

    fun settleCenter(dy: Float, velocityY: Float) {
        center ?: return
        val open = velocityY < -fling || (-dy > root.height * 0.18f && velocityY <= fling)
        if (open) slideCenter(0f) else closeCenter()
    }

    /** Abre el centro (al tocar una pila se abre con esa app desplegada). */
    fun openCenter(expand: String?, animated: Boolean = true) {
        if (!interactive || items.isEmpty() || sheet != null) return
        closeRevealed()
        val c = center ?: createCenter(expand).also { it.frame.translationY = root.height.toFloat() }
        if (expand != null) c.expand(expand)
        slideCenter(0f, animated)
    }

    private fun createCenter(expand: String?): Center {
        val c = Center(expand)
        center = c
        host?.onOverlayChanged(true)
        return c
    }

    private fun slideCenter(target: Float, animated: Boolean = true, end: (() -> Unit)? = null) {
        val c = center ?: return
        val h = root.height.toFloat().coerceAtLeast(1f)
        animateValue(c.frame.translationY, target, if (animated) 320 else 0) { v ->
            c.frame.translationY = v
            setCenterProgress(1f - v / h)
        }.withEnd(end)
    }

    private fun closeCenter(animated: Boolean = true) {
        val c = center ?: return
        center = null
        if (openRow?.isInside(c.frame) == true) openRow = null
        val h = root.height.toFloat().coerceAtLeast(1f)
        animateValue(c.frame.translationY, h, if (animated) 260 else 0) { v ->
            c.frame.translationY = v
            setCenterProgress(1f - v / h, c)
        }.withEnd {
            root.removeView(c.frame)
            root.removeView(c.scrim)
            setCenterProgress(0f, c)
            flush()
        }
        host?.onOverlayChanged(overlayOpen)
    }

    private inner class Center(expand: String?) {
        val scrim = View(context).apply {
            tag = OVERLAY
            setBackgroundColor(Color.argb(if (glass) 170 else 225, 0, 0, 0))
            alpha = 0f
        }
        val frame = DragFrame(context).apply { tag = OVERLAY }
        private val scroll = ScrollView(context)
        private val list = LinearLayout(context)
        private val expanded = mutableSetOf<String>().apply { expand?.let { add(it) } }

        init {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), topInset() + dp(22), dp(16), dp(12))
                addView(TextView(context).apply {
                    setText(R.string.notif_center)
                    style(30f, Color.WHITE, 700)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(confirmButton(context.getString(R.string.notif_clear_all)) {
                    closeCenter()
                    NotificationsHub.clearAll(items)
                }, LinearLayout.LayoutParams(WRAP, dp(32)))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            list.orientation = LinearLayout.VERTICAL
            list.setPadding(dp(12), dp(4), dp(12), dp(48))
            // Tocar un espacio vacío cierra el centro (como la barra de Android).
            list.setOnClickListener { if (!closeRevealed()) closeCenter() }
            scroll.isFillViewport = true
            scroll.isVerticalScrollBarEnabled = false
            scroll.clipToPadding = false
            scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
            column.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            frame.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
            // Hacia abajo (estando arriba de todo) se cierra siguiendo al dedo.
            frame.listener = object : DragFrame.Listener {
                override fun onDragStart(dy: Float) = dy > 0 && !scroll.canScrollVertically(-1)

                override fun onDrag(dy: Float) {
                    val h = root.height.toFloat().coerceAtLeast(1f)
                    frame.translationY = dy.coerceAtLeast(0f)
                    setCenterProgress(1f - frame.translationY / h)
                }

                override fun onDragEnd(dy: Float, velocityY: Float) {
                    if (velocityY > fling || (dy > root.height * 0.2f && velocityY >= -fling)) closeCenter() else slideCenter(0f)
                }

                override fun onTap() {
                    closeRevealed()
                }
            }
            root.addView(scrim, ViewGroup.LayoutParams(MATCH, MATCH))
            root.addView(frame, ViewGroup.LayoutParams(MATCH, MATCH))
            render()
        }

        fun expand(packageName: String) {
            if (expanded.add(packageName)) render()
        }

        private fun collapse(packageName: String) {
            if (expanded.remove(packageName)) render()
        }

        fun render() {
            val groups = NotificationsHub.groups(items)
            expanded.retainAll(groups.map { it.first().packageName }.toSet())
            if (animations && list.isLaidOut) TransitionManager.beginDelayedTransition(list, moveTransition())
            list.removeAllViews()
            val palette = overlayPalette()
            val locked = isLocked()
            groups.forEachIndexed { i, group ->
                val pkg = group.first().packageName
                val gap = if (i == 0) 0 else dp(14)
                if (group.size > 1 && pkg in expanded) {
                    list.addView(groupHeader(group) { collapse(pkg) }.apply { transitionName = "center:h:$pkg" },
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap })
                    group.forEach { item ->
                        list.addView(stack(listOf(item), palette, locked, maxLines = 4) { host?.open(item) }.apply {
                            transitionName = "center:${item.key}"
                        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
                    }
                } else {
                    list.addView(stack(group, palette, locked, maxLines = 4) {
                        if (group.size > 1) expand(pkg) else host?.open(group.first())
                    }.apply { transitionName = "center:" + group.first().key },
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = gap })
                }
            }
        }

        /** Encabezado de una app desplegada: nombre, "Mostrar menos" y ✕ para borrar sus notificaciones. */
        private fun groupHeader(group: List<NotifItem>, onCollapse: () -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
            addView(TextView(context).apply {
                text = group.first().appName
                style(20f, Color.WHITE, 700)
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                setText(R.string.notif_show_less)
                style(13f, Color.WHITE, 600)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(overlayPalette().card, dp(16).toFloat())
                setOnClickListener { onCollapse() }
            }, LinearLayout.LayoutParams(WRAP, dp(32)))
            addView(confirmButton(context.getString(R.string.notif_clear)) { NotificationsHub.dismissAll(group) },
                LinearLayout.LayoutParams(WRAP, dp(32)).apply { marginStart = dp(8) })
        }
    }

    /** ✕ que al tocarlo se convierte en "Borrar…" y recién al segundo toque borra (como en iOS). */
    private fun confirmButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
        var armed = false
        val reset = Runnable {
            armed = false
            text = "×"
            setPadding(0, 0, 0, dp(2))
        }
        style(15f, Color.WHITE, 700)
        gravity = Gravity.CENTER
        minWidth = dp(32)
        background = rounded(overlayPalette().card, dp(16).toFloat())
        contentDescription = label
        reset.run()
        setOnClickListener {
            if (armed) {
                handler.removeCallbacks(reset)
                haptic(this, confirm())
                action()
            } else {
                (parent as? ViewGroup)?.let { if (animations) TransitionManager.beginDelayedTransition(it) }
                armed = true
                text = label
                textSize = 14f
                setPadding(dp(14), 0, dp(14), 0)
                haptic(this, HapticFeedbackConstants.CLOCK_TICK)
                handler.postDelayed(reset, 3_000)
            }
        }
    }

    // --- Pilas y tarjetas ---

    private data class Palette(val text: Int, val secondary: Int, val tertiary: Int, val card: Int, val stroke: Int)

    private fun inlinePalette() = Palette(
        primary,
        ColorUtils.setAlphaComponent(primary, 0xD9),
        ColorUtils.setAlphaComponent(primary, 0xA6),
        when {
            !glass -> Color.argb(235, 44, 44, 46)
            darkText -> Color.argb(150, 255, 255, 255)
            else -> Color.argb(60, 255, 255, 255)
        },
        Color.argb(if (glass) 90 else 26, 255, 255, 255),
    )

    /** En los paneles el fondo siempre queda oscuro: texto blanco. */
    private fun overlayPalette() = Palette(
        Color.WHITE,
        ColorUtils.setAlphaComponent(Color.WHITE, 0xD9),
        ColorUtils.setAlphaComponent(Color.WHITE, 0xA6),
        if (glass) Color.argb(72, 255, 255, 255) else Color.rgb(44, 44, 46),
        Color.argb(if (glass) 80 else 26, 255, 255, 255),
    )

    /** Una notificación sola o la pila de una app (la más reciente adelante y "tarjetas" detrás). */
    private fun stack(group: List<NotifItem>, palette: Palette, locked: Boolean, maxLines: Int, onTap: () -> Unit): SwipeRow {
        val front = card(group.first(), palette, locked, maxLines, more = group.size - 1)
        if (group.size == 1) return SwipeRow(group, front, 0, palette, onTap)
        val plates = min(group.size - 1, 2)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(front, LinearLayout.LayoutParams(MATCH, WRAP))
            val radius = dp(14).toFloat()
            listOf(14 to 0.7f, 28 to 0.4f).take(plates).forEach { (inset, alpha) ->
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
                        setColor(palette.card)
                    }
                    this.alpha = alpha
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(MATCH, dp(7)).apply {
                    marginStart = dp(inset)
                    marginEnd = dp(inset)
                })
            }
        }
        return SwipeRow(group, column, plates * dp(7), palette, onTap)
    }

    private fun card(item: NotifItem, palette: Palette, locked: Boolean, maxLines: Int, more: Int): View {
        val hide = hides(item, locked)
        val title = if (hide) item.appName else item.title
        val text = if (hide) hiddenText(item) else item.text
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(palette.card, dp(22).toFloat()).apply { setStroke(max(1, dp(1) / 2), palette.stroke) }
            contentDescription = "${item.appName}: $title. $text"
            addView(appIcon(item, dp(38)), LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        this.text = title
                        style(15f, palette.text, 600)
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(TextView(context).apply {
                        this.text = ago(item.postTime)
                        style(13f, palette.secondary, 400)
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                })
                if (text.isNotBlank()) {
                    addView(TextView(context).apply {
                        this.text = text
                        style(15f, ColorUtils.setAlphaComponent(palette.text, 0xE6), 400)
                        this.maxLines = maxLines
                        ellipsize = TextUtils.TruncateAt.END
                        setLineSpacing(0f, 1.1f)
                    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
                }
                if (more > 0) {
                    addView(TextView(context).apply {
                        this.text = context.resources.getQuantityString(R.plurals.notif_more, more, more)
                        style(13f, palette.tertiary, 500)
                    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }
    }

    /**
     * Una tarjeta que se desliza: a la izquierda aparecen "Opciones" y "Borrar" (hasta el final,
     * borra); a la derecha, "Abrir". Mantener presionada la amplía.
     */
    private inner class SwipeRow(
        val group: List<NotifItem>,
        val content: View,
        platesHeight: Int,
        palette: Palette,
        private val onTap: () -> Unit,
    ) : FrameLayout(context), View.OnTouchListener {
        private val buttonWidth = dp(78)
        private val gap = dp(8)
        private val revealWidth = (buttonWidth + gap) * 2f
        private val clearable = group.any { it.clearable }
        private val options = pill(context.getString(R.string.notif_options), palette)
        private val clear = pill(context.getString(R.string.notif_clear), palette)
        private val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.INVISIBLE
        }
        private val openPill = pill(context.getString(R.string.notif_open), palette).apply { visibility = View.INVISIBLE }

        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var moved = false
        private var horizontal = false
        private var longPressed = false
        private var pastFull = false
        private var active = false
        private var anim: ValueAnimator? = null

        private val longPress = Runnable {
            longPressed = true
            haptic(content, HapticFeedbackConstants.LONG_PRESS)
            content.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            showPreview(group.first())
        }

        init {
            if (interactive) {
                buttons.addView(options, LinearLayout.LayoutParams(buttonWidth, MATCH).apply { marginStart = gap })
                if (clearable) buttons.addView(clear, LinearLayout.LayoutParams(buttonWidth, MATCH).apply { marginStart = gap })
                addView(openPill, LayoutParams(buttonWidth, MATCH, Gravity.START).apply { bottomMargin = platesHeight })
                addView(buttons, LayoutParams(WRAP, MATCH, Gravity.END).apply { bottomMargin = platesHeight })
            }
            addView(content, LayoutParams(MATCH, WRAP))
            if (interactive) {
                content.setOnTouchListener(this)
                // Con TalkBack: tocar dos veces abre y las acciones están en el menú de accesibilidad.
                content.setOnClickListener { onTap() }
                options.setOnClickListener { close(); showOptions(group) }
                clear.setOnClickListener { clearNow() }
                ViewCompat.addAccessibilityAction(content, context.getString(R.string.notif_expand)) { _, _ -> showPreview(group.first()); true }
                ViewCompat.addAccessibilityAction(content, context.getString(R.string.notif_options)) { _, _ -> showOptions(group); true }
                if (clearable) ViewCompat.addAccessibilityAction(content, context.getString(R.string.notif_clear)) { _, _ -> clearNow(); true }
            }
        }

        fun isInside(view: View): Boolean {
            var p = parent
            while (p != null) {
                if (p === view) return true
                p = p.parent
            }
            return false
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!active) {
                        active = true
                        touching++
                    }
                    anim?.cancel()
                    downX = e.rawX
                    downY = e.rawY
                    startTx = content.translationX
                    moved = false
                    horizontal = false
                    longPressed = false
                    pastFull = false
                    openRow?.takeIf { it !== this }?.close()
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    if (startTx == 0f) content.animate().scaleX(0.98f).scaleY(0.98f).setDuration(100).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        content.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }
                    if (moved && !horizontal && abs(dx) > abs(dy)) {
                        horizontal = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) drag(startTx + dx)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    content.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    when {
                        longPressed -> Unit
                        horizontal -> settle()
                        moved -> Unit
                        startTx != 0f -> close()
                        else -> onTap()
                    }
                    endTouch()
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    content.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    if (!longPressed) {
                        if (startTx < 0f && !pastFull) reveal() else close()
                    }
                    endTouch()
                }
            }
            return true
        }

        private fun endTouch() {
            if (active) {
                active = false
                touching--
            }
            flush()
        }

        private fun drag(raw: Float) {
            val w = width.toFloat().coerceAtLeast(1f)
            // Sin botones a la derecha si no hay host; con resistencia pasado el umbral de "Abrir".
            val tx = when {
                raw > w * OPEN_SWIPE -> w * OPEN_SWIPE + (raw - w * OPEN_SWIPE) * 0.3f
                !clearable && raw < -revealWidth -> -revealWidth + (raw + revealWidth) * 0.3f
                else -> raw
            }
            content.translationX = tx.coerceIn(-w, w)
            val full = clearable && -tx > w * FULL_SWIPE
            if (full != pastFull) {
                pastFull = full
                haptic(content, HapticFeedbackConstants.CLOCK_TICK)
            }
            updateButtons()
        }

        private fun updateButtons() {
            val tx = content.translationX
            val left = (-tx / revealWidth).coerceIn(0f, 1f)
            buttons.alpha = left
            buttons.visibility = if (left > 0f) View.VISIBLE else View.INVISIBLE
            // Pasado el umbral, "Borrar" ocupa todo el espacio, como en el iPhone.
            options.visibility = if (pastFull) View.GONE else View.VISIBLE
            val clearWidth = if (pastFull) max(buttonWidth, (-tx).roundToInt() - gap) else buttonWidth
            (clear.layoutParams as? LinearLayout.LayoutParams)?.let {
                if (it.width != clearWidth) {
                    it.width = clearWidth
                    clear.layoutParams = it
                }
            }
            val right = (tx / (width.coerceAtLeast(1) * OPEN_SWIPE)).coerceIn(0f, 1f)
            openPill.alpha = right
            openPill.visibility = if (right > 0f) View.VISIBLE else View.INVISIBLE
        }

        private fun settle() {
            val tx = content.translationX
            when {
                pastFull -> clearNow()
                -tx > revealWidth * 0.45f -> reveal()
                tx >= width * OPEN_SWIPE -> {
                    haptic(content, confirm())
                    close()
                    host?.open(group.first())
                }
                else -> close()
            }
        }

        fun reveal() {
            openRow = this
            pastFull = false
            slideTo(-revealWidth)
        }

        fun close() {
            if (openRow === this) openRow = null
            pastFull = false
            slideTo(0f) { flush() }
        }

        fun clearNow() {
            if (openRow === this) openRow = null
            haptic(content, confirm())
            pastFull = clearable
            slideTo(-width * 1.1f, 200) {
                content.alpha = 0f
                buttons.alpha = 0f
                NotificationsHub.dismissAll(group)
            }
        }

        private fun slideTo(target: Float, ms: Long = 240, end: (() -> Unit)? = null) {
            anim?.cancel()
            anim = animateValue(content.translationX, target, ms) { v ->
                content.translationX = v
                updateButtons()
            }.withEnd(end)
        }
    }

    /** Cierra la tarjeta que tenga los botones a la vista. */
    fun closeRevealed(): Boolean {
        val row = openRow ?: return false
        row.close()
        return true
    }

    private fun pill(label: String, palette: Palette) = TextView(context).apply {
        text = label
        style(14f, palette.text, 600)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(4), 0, dp(4), 0)
        background = rounded(palette.card, dp(20).toFloat())
    }

    // --- Ampliada (mantener presionada) ---

    private fun showPreview(item: NotifItem, animated: Boolean = true) {
        if (!interactive) return
        closeRevealed()
        closeSheet(animated = false)
        val locked = isLocked()
        val hidden = hides(item, locked)
        val palette = overlayPalette()
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val openIt = {
            closeSheet()
            host?.open(item)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(if (glass) Color.argb(215, 48, 48, 52) else Color.rgb(44, 44, 46), dp(26).toFloat())
            setOnClickListener { openIt() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(appIcon(item, dp(24)), LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(TextView(context).apply {
                    text = item.appName
                    style(14f, palette.secondary, 500)
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
                addView(TextView(context).apply {
                    text = ago(item.postTime)
                    style(14f, palette.secondary, 400)
                })
            })
            addView(TextView(context).apply {
                text = if (hidden) item.appName else item.title
                style(18f, Color.WHITE, 700)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            val body = if (hidden) hiddenText(item) else item.text
            if (body.isNotBlank()) {
                // El texto entero; si es muy largo, se desplaza dentro de la tarjeta.
                val maxHeight = (root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels) * 0.38f
                addView(object : ScrollView(context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight.roundToInt(), View.MeasureSpec.AT_MOST))
                }.apply {
                    isVerticalScrollBarEnabled = false
                    addView(TextView(context).apply {
                        text = body
                        style(16f, ColorUtils.setAlphaComponent(Color.WHITE, 0xF0), 400)
                        maxLines = Int.MAX_VALUE
                        setLineSpacing(0f, 1.15f)
                        setOnClickListener { openIt() }
                    })
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            }
            val picture = item.picture
            if (!hidden && picture != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(picture)
                    adjustViewBounds = true
                    maxHeight = dp(260)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    roundCorners(this, dp(14).toFloat())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            }
        }
        box.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))

        // Los botones de la app (responder, marcar como leído...): lo que el iPhone no deja con todas.
        val menuSlot = FrameLayout(context)
        val rows = mutableListOf<MenuRow>()
        if (hidden) {
            rows += MenuRow(context.getString(R.string.notif_unlock_to_view), bold = true) { openIt() }
        } else {
            item.actions.forEach { action ->
                rows += MenuRow(action.title) {
                    if (action.isReply) {
                        showReply(menuSlot, item, action)
                    } else {
                        closeSheet()
                        if (action.needsUnlock && isLocked()) {
                            host?.unlockThen { NotificationsHub.runAction(context, action) }
                        } else {
                            NotificationsHub.runAction(context, action)
                        }
                    }
                }
            }
            if (item.text.isNotBlank()) {
                rows += MenuRow(context.getString(R.string.notif_copy)) {
                    closeSheet()
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(item.title, item.text))
                }
            }
        }
        if (rows.isNotEmpty()) {
            menuSlot.addView(menu(rows, centered = false))
            box.addView(menuSlot, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        val extra = mutableListOf(MenuRow(context.getString(R.string.notif_options)) { showOptions(listOf(item)) })
        if (item.clearable) {
            extra += MenuRow(context.getString(R.string.notif_clear), destructive = true) {
                closeSheet()
                NotificationsHub.dismiss(item)
            }
        }
        box.addView(menu(extra, centered = false), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        openSheet(setOf(item.key), box, bottom = false, animated = animated)
    }

    /** Responder sin abrir la app (RemoteInput de Android). */
    private fun showReply(slot: FrameLayout, item: NotifItem, action: NotifAction) {
        slot.removeAllViews()
        val field = EditText(context).apply {
            hint = context.getString(R.string.notif_reply_hint, item.title)
            setHintTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 0x99))
            setTextColor(Color.WHITE)
            textSize = 16f
            background = rounded(Color.argb(240, 44, 44, 46), dp(20).toFloat()).apply { setStroke(max(1, dp(1) / 2), Color.argb(60, 255, 255, 255)) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val send = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_up)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IOS_BLUE)
            }
            contentDescription = context.getString(R.string.notif_reply_send)
        }
        val submit = submit@{
            val text = field.text.toString().trim()
            if (text.isEmpty()) return@submit
            hideKeyboard(field)
            haptic(send, confirm())
            if (action.needsUnlock && isLocked()) {
                closeSheet()
                host?.unlockThen { NotificationsHub.reply(context, action, text) }
                return@submit
            }
            NotificationsHub.reply(context, action, text)
            slot.removeAllViews()
            slot.addView(TextView(context).apply {
                setText(R.string.notif_reply_sent)
                style(15f, Color.WHITE, 600)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                background = rounded(Color.argb(240, 44, 44, 46), dp(16).toFloat())
            })
            handler.postDelayed({ closeSheet() }, 900)
        }
        field.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }
        send.setOnClickListener { submit() }
        slot.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(send, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        })
        field.requestFocus()
        field.post {
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // --- Opciones ---

    private fun showOptions(group: List<NotifItem>, animated: Boolean = true) {
        if (!interactive || group.isEmpty()) return
        closeRevealed()
        closeSheet(animated = false)
        val first = group.first()
        val run = { action: () -> Unit ->
            closeSheet()
            action()
        }
        val rows = listOf(
            MenuRow(context.getString(R.string.notif_snooze_15), tint = IOS_BLUE) { run { NotificationsHub.snooze(group, 15 * MINUTE) } },
            MenuRow(context.getString(R.string.notif_snooze_hour), tint = IOS_BLUE) { run { NotificationsHub.snooze(group, 60 * MINUTE) } },
            MenuRow(context.getString(R.string.notif_snooze_tomorrow), tint = IOS_BLUE) { run { NotificationsHub.snooze(group, untilTomorrowMorning()) } },
            MenuRow(context.getString(R.string.notif_mute_hour), tint = IOS_BLUE) {
                run { NotificationsHub.muteApp(context, first.packageName, 60 * MINUTE) }
            },
            MenuRow(context.getString(R.string.notif_app_settings, first.appName), tint = IOS_BLUE) {
                run {
                    host?.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, first.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        ) + if (group.any { it.clearable }) {
            listOf(MenuRow(
                if (group.size > 1) context.resources.getQuantityString(R.plurals.notif_clear_count, group.size, group.size) else context.getString(R.string.notif_clear),
                destructive = true,
            ) { run { NotificationsHub.dismissAll(group) } })
        } else {
            emptyList()
        }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(menu(rows, centered = true, title = first.appName), LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(menu(listOf(MenuRow(context.getString(R.string.notif_cancel), bold = true, tint = IOS_BLUE) { closeSheet() }), centered = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        openSheet(group.map { it.key }.toSet(), box, bottom = true, animated = animated)
    }

    private fun untilTomorrowMorning(): Long {
        val now = ZonedDateTime.now()
        val morning = now.toLocalDate().plusDays(1).atTime(8, 0).atZone(now.zone)
        return Duration.between(now, morning).toMillis().coerceAtLeast(60 * MINUTE)
    }

    private class MenuRow(
        val label: String,
        val destructive: Boolean = false,
        val bold: Boolean = false,
        val tint: Int = Color.WHITE,
        val action: () -> Unit,
    )

    /** Lista de opciones al estilo iOS (grupo redondeado con separadores finos). */
    private fun menu(rows: List<MenuRow>, centered: Boolean, title: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.argb(245, 38, 38, 40), dp(16).toFloat())
        clipToOutline = true
        fun separator() = addView(View(context).apply { setBackgroundColor(Color.argb(56, 255, 255, 255)) },
            LinearLayout.LayoutParams(MATCH, max(1, dp(1) / 2)))
        if (title != null) {
            addView(TextView(context).apply {
                text = title
                style(13f, ColorUtils.setAlphaComponent(Color.WHITE, 0x99), 600)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            separator()
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) separator()
            addView(TextView(context).apply {
                text = row.label
                style(17f, if (row.destructive) IOS_RED else row.tint, if (row.bold) 600 else 400)
                gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(18), 0, dp(18), 0)
                ellipsize = TextUtils.TruncateAt.END
                setBackgroundResource(R.drawable.row_highlight)
                setOnClickListener { row.action() }
            }, LinearLayout.LayoutParams(MATCH, dp(if (centered) 56 else 50)))
        }
    }

    // --- Paneles (ampliada y opciones) ---

    private class Sheet(val keys: Set<String>, val frame: FrameLayout, val content: View, val bottom: Boolean)

    private fun openSheet(keys: Set<String>, content: View, bottom: Boolean, animated: Boolean) {
        val frame = FrameLayout(context).apply {
            tag = OVERLAY
            setBackgroundColor(Color.argb(if (glass) 165 else 190, 0, 0, 0))
            isClickable = true
            setOnClickListener { closeSheet() }
            setPadding(dp(12), topInset() + dp(12), dp(12), dp(12))
        }
        content.isClickable = true
        frame.addView(content, FrameLayout.LayoutParams(MATCH, WRAP, if (bottom) Gravity.BOTTOM else Gravity.CENTER_VERTICAL))
        // Con el teclado abierto (responder), el panel sube.
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(dp(12), topInset() + dp(12), dp(12), dp(12) + ime)
            insets
        }
        root.addView(frame, ViewGroup.LayoutParams(MATCH, MATCH))
        val s = Sheet(keys, frame, content, bottom)
        sheet = s
        host?.onOverlayChanged(true)
        if (animated && animations) {
            frame.alpha = 0f
            if (bottom) content.translationY = dp(320).toFloat() else {
                content.scaleX = 0.92f
                content.scaleY = 0.92f
            }
            content.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(if (bottom) DecelerateInterpolator(2f) else OvershootInterpolator(1.1f)).start()
        }
        animateValue(0f, 1f, if (animated) 220 else 0) { v ->
            frame.alpha = v
            sheetProgress = v
            applyBlur()
        }
    }

    private fun closeSheet(animated: Boolean = true) {
        val s = sheet ?: return
        sheet = null
        hideKeyboard(s.frame)
        if (animated && animations) {
            s.content.animate().translationY(if (s.bottom) dp(320).toFloat() else 0f)
                .scaleX(if (s.bottom) 1f else 0.95f).scaleY(if (s.bottom) 1f else 0.95f)
                .setInterpolator(DecelerateInterpolator()).setDuration(200).start()
        }
        animateValue(s.frame.alpha, 0f, if (animated) 200 else 0) { v ->
            s.frame.alpha = v
            sheetProgress = v
            applyBlur()
        }.withEnd {
            root.removeView(s.frame)
            sheetProgress = 0f
            applyBlur()
        }
        host?.onOverlayChanged(overlayOpen)
    }

    /** Atrás: cierra lo de más arriba. false si no había nada abierto. */
    fun closeTop(): Boolean = when {
        sheet != null -> { closeSheet(); true }
        center != null -> { closeCenter(); true }
        else -> closeRevealed()
    }

    /** Al salir del reloj o apagar la pantalla, todo vuelve a su lugar. */
    fun closeAll() {
        openRow?.close()
        closeSheet(animated = false)
        closeCenter(animated = false)
    }

    // --- Fondo desenfocado detrás de los paneles (Android 12+) ---

    private fun setCenterProgress(progress: Float, c: Center? = center) {
        centerProgress = progress.coerceIn(0f, 1f)
        c?.scrim?.alpha = centerProgress
        applyBlur()
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val base = max(centerProgress, sheetProgress)
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            when {
                child.tag != OVERLAY -> blur(child, base)
                child === center?.frame -> blur(child, sheetProgress)
            }
        }
    }

    private fun blur(view: View, progress: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = BLUR_DP * density * progress
        runCatching {
            view.setRenderEffect(if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    }

    // --- Utilidades ---

    private fun isLocked() = host?.locked()
        ?: (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)

    /**
     * Con el teléfono bloqueado se oculta el contenido si así se eligió en iOSDroid, o si la app
     * lo marcó privado y en Ajustes de Android está "Ocultar contenido sensible". Si no, se ve
     * todo, como en la pantalla de bloqueo del sistema.
     */
    private fun hides(item: NotifItem, locked: Boolean): Boolean =
        locked && (prefs.hideNotificationText || (item.private && !systemShowsPrivate()))

    private fun systemShowsPrivate(): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, "lock_screen_allow_private_notifications", 1) != 0
    }.getOrDefault(true)

    private fun hiddenText(item: NotifItem) =
        item.publicText?.takeIf { it.isNotBlank() && it != item.appName } ?: context.getString(R.string.notif_hidden)

    private fun appIcon(item: NotifItem, size: Int) = ImageView(context).apply {
        val icon = item.icon(context)?.takeIf { it.intrinsicWidth > 0 }
        if (icon != null) setImageDrawable(icon) else setImageBitmap(letterIcon(item.appName, size))
        roundCorners(this, size * 0.24f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Si no se puede leer el ícono de la app: su inicial sobre un color propio de la app. */
    private fun letterIcon(name: String, size: Int): Bitmap {
        val palette = intArrayOf(0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(), 0xFF5AC8FA.toInt())
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
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

    private fun roundCorners(view: View, radius: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        view.clipToOutline = true
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

    private fun TextView.style(sizeSp: Float, color: Int, weight: Int) {
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

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun topInset(): Int =
        ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.displayCutout())?.top ?: 0

    private fun hideKeyboard(view: View) {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun confirm() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY

    private fun haptic(view: View, feedback: Int) {
        view.performHapticFeedback(feedback)
    }

    private fun moveTransition() = TransitionSet().apply {
        ordering = TransitionSet.ORDERING_TOGETHER
        addTransition(ChangeBounds())
        addTransition(Fade(Fade.IN or Fade.OUT))
        duration = 260
        interpolator = DecelerateInterpolator(1.5f)
    }

    /** Anima un valor; con "Quitar animaciones" (o ms = 0) salta directo al final. */
    private fun animateValue(from: Float, to: Float, ms: Long, update: (Float) -> Unit): ValueAnimator {
        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = if (animations) ms else 0
        animator.interpolator = DecelerateInterpolator(1.6f)
        animator.addUpdateListener { update(it.animatedValue as Float) }
        if (animator.duration == 0L) {
            update(to)
        } else {
            animator.start()
        }
        return animator
    }

    /** Lo que corre al terminar (no si la animación se cancela para empezar otra). */
    private fun ValueAnimator.withEnd(end: (() -> Unit)?): ValueAnimator {
        if (end == null) return this
        if (!isStarted) {
            end()
            return this
        }
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) end()
            }
        })
        return this
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val OVERLAY = "notifications-overlay"
        const val FULL_SWIPE = 0.6f
        const val OPEN_SWIPE = 0.35f
        const val BLUR_DP = 24f
        const val MINUTE = 60_000L
        const val IOS_BLUE = 0xFF0A84FF.toInt()
        const val IOS_RED = 0xFFFF453A.toInt()
    }
}
