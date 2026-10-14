package com.ramiro.iosdroid

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * FrameLayout que reconoce toques, dedo apoyado y arrastres verticales, aunque el arrastre
 * empiece encima de una notificación (como una lista con scroll): las tarjetas se quedan con
 * los deslizamientos hacia los costados.
 *
 * Es la raíz de la pantalla de reloj (deslizar hacia arriba abre las notificaciones) y del
 * centro de notificaciones (deslizar hacia abajo lo cierra). Sin [listener] se comporta como
 * un FrameLayout común (el protector de pantalla no recibe toques).
 */
class DragFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    interface Listener {
        /** Se apoyó el dedo en el fondo sin moverlo (para desbloquear con la huella). */
        fun onPress() {}

        fun onTap() {}

        /** Empezó un arrastre vertical (dy < 0: hacia arriba). false: este arrastre no le interesa. */
        fun onDragStart(dy: Float): Boolean = false

        fun onDrag(dy: Float) {}

        /** Fin del arrastre; [velocityY] en px/s (negativa hacia arriba). Cancelado: dy = 0. */
        fun onDragEnd(dy: Float, velocityY: Float) {}
    }

    var listener: Listener? = null

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragging = false
    private var rejected = false
    private var pressFired = false
    private var velocity: VelocityTracker? = null

    private val press = Runnable {
        pressFired = true
        listener?.onPress()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val l = listener ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(ev)
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!dragging) tryStartDrag(ev, l)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val l = listener ?: return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start(ev)
                postDelayed(press, PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!dragging) tryStartDrag(ev, l)
                if (moved) removeCallbacks(press)
                if (dragging) l.onDrag(ev.rawY - downY)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(press)
                track(ev)
                when {
                    dragging -> l.onDragEnd(ev.rawY - downY, velocityY())
                    !moved && !pressFired -> performClick()
                }
                dragging = false
                recycle()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(press)
                if (dragging) l.onDragEnd(0f, 0f)
                dragging = false
                recycle()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onTap()
        return true
    }

    private fun start(ev: MotionEvent) {
        downX = ev.rawX
        downY = ev.rawY
        moved = false
        dragging = false
        rejected = false
        pressFired = false
        recycle()
        velocity = VelocityTracker.obtain()
        track(ev)
    }

    private fun tryStartDrag(ev: MotionEvent, l: Listener) {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
        if (!moved || rejected || abs(dy) <= abs(dx)) return
        if (l.onDragStart(dy)) {
            dragging = true
            removeCallbacks(press)
        } else {
            rejected = true
        }
    }

    private fun track(ev: MotionEvent) {
        // Con coordenadas absolutas: la vista puede moverse con el dedo (el centro de notificaciones).
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        velocity?.addMovement(copy)
        copy.recycle()
    }

    private fun velocityY(): Float {
        val v = velocity ?: return 0f
        v.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
        return v.yVelocity
    }

    private fun recycle() {
        velocity?.recycle()
        velocity = null
    }

    companion object {
        /** Lo que tarda en contar como "dedo apoyado" (un toque rápido se resuelve al soltar). */
        const val PRESS_MS = 180L
    }
}
