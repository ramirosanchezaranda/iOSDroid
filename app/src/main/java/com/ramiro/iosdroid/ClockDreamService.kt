package com.ramiro.iosdroid

import android.content.res.Configuration
import android.service.dreams.DreamService

/**
 * Protector de pantalla del sistema: se activa solo mientras el teléfono carga (Ajustes →
 * Pantalla → Protector de pantalla). Muestra la misma pantalla que el modo reloj.
 */
class ClockDreamService : DreamService() {

    private var screen: ClockScreen? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = !Prefs(this).saverDim
        inflate()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        screen?.start()
    }

    override fun onDreamingStopped() {
        screen?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        screen?.stop()
        screen = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Al girar el teléfono se vuelve a armar con el layout vertical u horizontal.
        val wasRunning = screen != null
        screen?.stop()
        inflate()
        if (wasRunning) screen?.start()
    }

    private fun inflate() {
        setContentView(R.layout.clock_screen)
        screen = ClockScreen(this, window, findViewById(R.id.clock_root), controlBrightness = false)
    }
}
