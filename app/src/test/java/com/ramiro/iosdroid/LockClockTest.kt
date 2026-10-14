package com.ramiro.iosdroid

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "es")
class LockClockTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = shadowOf(RuntimeEnvironment.getApplication())
    private val notifications get() = shadowOf(ctx.getSystemService(NotificationManager::class.java))

    private val opened = mutableListOf<ActivityController<ClockActivity>>()

    private fun open(intent: Intent? = ClockActivity.lockIntent(ctx)) =
        Robolectric.buildActivity(ClockActivity::class.java, intent).setup().also { opened += it }

    /** El estado "reloj a la vista" es estático: cada test cierra lo que abrió. */
    @After
    fun tearDown() {
        opened.forEach { runCatching { it.pause().stop().destroy() } }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        Prefs(ctx).apply {
            lockClock = true
            lockMode = LockMode.ALWAYS
            place = Place("Buenos Aires", -34.61, -58.38, auto = false)
        }
    }

    @Test
    fun screenOffShowsClockOverLockScreen() {
        LockClock.onScreenOff(ctx, now = ClockActivity.lastPausedAt + 60_000)

        val started = app.nextStartedActivity
        assertEquals(ClockActivity::class.java.name, started.component?.className)
        assertTrue(started.getBooleanExtra(ClockActivity.EXTRA_FROM_LOCK, false))
        val posted = notifications.getNotification(LockClock.ID_SHOW)
        assertNotNull("falta la notificación de pantalla completa", posted?.fullScreenIntent)
    }

    @Test
    fun doesNothingWhenDisabled() {
        Prefs(ctx).lockClock = false
        LockClock.onScreenOff(ctx, now = ClockActivity.lastPausedAt + 60_000)
        assertNull(app.nextStartedActivity)
        assertNull(notifications.getNotification(LockClock.ID_SHOW))
    }

    @Test
    fun turningOffScreenWhileWatchingClockKeepsItOff() {
        // El reloj se estaba viendo hasta hace un segundo: el usuario lo apagó a propósito.
        LockClock.onScreenOff(ctx, now = ClockActivity.lastPausedAt + 1_000)
        assertNull(app.nextStartedActivity)
    }

    @Test
    fun serviceListensForScreenOff() {
        val service = Robolectric.buildService(ClockService::class.java).create().get()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        // El apagado ocurre bastante después de que se cerró cualquier reloj de otro test.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        ctx.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        // Sin el reloj recién visto, se abre la pantalla de reloj.
        assertEquals(ClockActivity::class.java.name, app.nextStartedActivity?.component?.className)
    }

    @Test
    fun alwaysModeKeepsScreenOnAndTurnsItOn() {
        val activity = open().get()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        // La notificación de pantalla completa se borra al abrirse.
        assertNull(notifications.getNotification(LockClock.ID_SHOW))
    }

    @Test
    fun normalModeLetsScreenTurnOff() {
        Prefs(ctx).lockMode = LockMode.NORMAL
        val activity = open().get()
        assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun openedByHandAlwaysStaysOn() {
        Prefs(ctx).lockMode = LockMode.NORMAL
        val activity = open(null).get()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun touchingTheLockedClockOpensUnlock() {
        val keyguard = shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        try {
            val activity = open().get()
            val root = activity.findViewById<android.view.View>(R.id.clock_root)
            assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.ss_unlock_hint).visibility)
            // Un toque (apoyar y soltar) pide la huella / PIN.
            root.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 100f, 400f, 0))
            root.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 60, android.view.MotionEvent.ACTION_UP, 100f, 400f, 0))
            shadowOf(Looper.getMainLooper()).idle()
            // Se pidió el desbloqueo: el reloj sigue hasta que el usuario se desbloquea (huella / PIN)...
            assertFalse(activity.isFinishing)
            keyguard.setKeyguardLocked(false)
            shadowOf(Looper.getMainLooper()).idle()
            // ...y ahí se cierra.
            assertTrue(activity.isFinishing)
        } finally {
            keyguard.setKeyguardLocked(false)
        }
    }

    @Test
    fun restingTheFingerOpensUnlock() {
        val keyguard = shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        try {
            val activity = open().get()
            val root = activity.findViewById<android.view.View>(R.id.clock_root)
            root.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 100f, 400f, 0))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            keyguard.setKeyguardLocked(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
        } finally {
            keyguard.setKeyguardLocked(false)
        }
    }

    @Test
    fun swipingUpOpensNotificationCenterAndBackClosesIt() {
        val now = System.currentTimeMillis()
        NotificationsHub.publish(listOf(
            NotifItem("a", "com.whatsapp", "WhatsApp", "Ana", "hola", now, null, false, true, null),
            NotifItem("b", "com.whatsapp", "WhatsApp", "Pedro", "llego en 5", now - 60_000, null, false, true, null),
        ))
        shadowOf(Looper.getMainLooper()).idle()
        try {
            val activity = open().get()
            val root = activity.findViewById<android.view.View>(R.id.clock_root)
            fun ev(action: Int, y: Float, t: Long) = android.view.MotionEvent.obtain(0, t, action, 200f, y, 0)
            root.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, 1200f, 0))
            for (i in 1..10) root.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, 1200f - i * 80f, i * 16L))
            root.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 400f, 170))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertTrue(activity.notificationsUi!!.centerOpen)
            assertFalse(activity.isFinishing)
            activity.onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertFalse(activity.notificationsUi!!.centerOpen)
            assertFalse(activity.isFinishing)
        } finally {
            NotificationsHub.publish(emptyList())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun unlockingClosesTheClock() {
        val controller = open()
        ctx.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
    }
}
