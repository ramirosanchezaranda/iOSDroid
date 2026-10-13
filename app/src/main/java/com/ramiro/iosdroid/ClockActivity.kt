package com.ramiro.iosdroid

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Modo reloj: pantalla completa que no se apaga mientras está abierta. También se muestra
 * encima de la pantalla de bloqueo, así que sirve como reloj de mesa de luz.
 */
class ClockActivity : ComponentActivity() {

    private var screen: ClockScreen? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var exit: View
    private val hideExit = Runnable {
        exit.animate().alpha(0f).withEndAction { exit.visibility = View.GONE }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.clock_screen)
        hideSystemBars()

        val root = findViewById<View>(R.id.clock_root)
        exit = findViewById(R.id.ss_exit)
        screen = ClockScreen(this, window, root, controlBrightness = true)
        // Un toque muestra el botón para salir (así un roce no lo cierra).
        root.setOnClickListener { showExit() }
        exit.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        screen?.start()
    }

    override fun onStop() {
        screen?.stop()
        handler.removeCallbacks(hideExit)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showExit() {
        handler.removeCallbacks(hideExit)
        exit.animate().cancel()
        exit.visibility = View.VISIBLE
        exit.alpha = 0f
        exit.animate().alpha(1f).start()
        handler.postDelayed(hideExit, 3_000)
    }
}
