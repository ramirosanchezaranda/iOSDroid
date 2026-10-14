package com.ramiro.iosdroid

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Modo reloj a pantalla completa. Se ve encima de la pantalla de bloqueo, así que sirve como
 * reloj de mesa de luz y como "pantalla de bloqueo" (ver [LockClock]).
 *
 * - Abierto a mano: la pantalla no se apaga mientras está abierto.
 * - Abierto al bloquear: según [LockMode], enciende la pantalla y la deja encendida; si lo tapás
 *   (bolsillo, boca abajo) el sensor de proximidad apaga la pantalla. Al desbloquear se cierra solo.
 */
class ClockActivity : ComponentActivity() {

    private var screen: ClockScreen? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var exit: TextView
    private var fromLock = false
    private var proximityLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false

    private val hideExit = Runnable {
        exit.animate().alpha(0f).withEndAction { exit.visibility = View.GONE }.start()
    }

    private val systemEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Desbloqueaste con huella, cara o PIN: volvés directo al teléfono.
                Intent.ACTION_USER_PRESENT -> if (fromLock) finish()
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> applyScreenMode(turnOn = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromLock = intent.getBooleanExtra(EXTRA_FROM_LOCK, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.clock_screen)
        hideSystemBars()
        applyScreenMode(turnOn = true)
        LockClock.cancelShowNotification(this)

        val root = findViewById<View>(R.id.clock_root)
        exit = findViewById(R.id.ss_exit)
        screen = ClockScreen(this, window, root, controlBrightness = true)
        // Un toque muestra el botón (así un roce no lo cierra).
        root.setOnClickListener { showExit() }
        exit.setOnClickListener { leave() }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, systemEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fromLock = intent.getBooleanExtra(EXTRA_FROM_LOCK, false)
        applyScreenMode(turnOn = true)
        LockClock.cancelShowNotification(this)
    }

    override fun onStart() {
        super.onStart()
        screen?.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (keepsScreenOn() && fromLock) acquireProximity()
        // La pantalla ya se encendió: que no se vuelva a encender sola cada vez que se reanude.
        handler.postDelayed({ setTurnOn(false) }, 1_000)
    }

    override fun onPause() {
        resumed = false
        lastPausedAt = SystemClock.elapsedRealtime()
        releaseProximity()
        super.onPause()
    }

    override fun onStop() {
        screen?.stop()
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(systemEvents)
        receiverRegistered = false
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Abierto a mano, siempre encendida. Al bloquear, según el modo elegido. */
    private fun keepsScreenOn(): Boolean {
        if (!fromLock) return true
        return when (Prefs(this).lockMode) {
            LockMode.ALWAYS -> true
            LockMode.CHARGING -> isCharging()
            LockMode.NORMAL -> false
        }
    }

    private fun applyScreenMode(turnOn: Boolean) {
        val keepOn = keepsScreenOn()
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (turnOn && fromLock && keepOn) setTurnOn(true)
        if (!keepOn) releaseProximity()
    }

    private fun setTurnOn(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }

    private fun isCharging() = getSystemService(BatteryManager::class.java)?.isCharging == true

    /** Con el teléfono en el bolsillo o boca abajo, el sensor de proximidad apaga la pantalla. */
    private fun acquireProximity() {
        if (proximityLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "iosdroid:clock-pocket").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseProximity() {
        proximityLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        proximityLock = null
    }

    private fun locked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Salir: si el teléfono está bloqueado, pide huella/PIN y después cierra. */
    private fun leave() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            })
        } else {
            finish()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showExit() {
        exit.setText(if (locked()) R.string.clock_unlock else R.string.clock_exit)
        handler.removeCallbacks(hideExit)
        exit.animate().cancel()
        exit.visibility = View.VISIBLE
        exit.alpha = 0f
        exit.animate().alpha(1f).start()
        handler.postDelayed(hideExit, 3_000)
    }

    companion object {
        const val EXTRA_FROM_LOCK = "from_lock"

        /** Para saber si el reloj se estaba viendo cuando se apagó la pantalla. */
        @Volatile
        var resumed = false
            private set

        @Volatile
        var lastPausedAt = 0L
            private set

        fun lockIntent(context: Context) = Intent(context, ClockActivity::class.java)
            .putExtra(EXTRA_FROM_LOCK, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
