package com.ramiro.iosdroid

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Servicio en primer plano (con su notificación fija) para lo que necesita escuchar eventos del
 * sistema mientras la app no está abierta:
 * - el apagado de pantalla, para mostrar el reloj al bloquear ([LockClock]);
 * - el paso de los minutos, para redibujar la hora del widget cuando usa una fuente propia.
 *
 * Solo corre si alguna de las dos cosas está activa, y solo escucha el minuto si hace falta.
 */
class ClockService : Service() {

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> LockClock.onScreenOff(context)
                Intent.ACTION_SCREEN_ON -> if (tickRegistered) refreshWidgetTime(context, onlyIfScreenOn = false)
                @Suppress("DEPRECATION")
                Intent.ACTION_WALLPAPER_CHANGED -> {
                    WidgetRenderer.forgetWallpaper()
                    WidgetRenderer.updateAll(context)
                }
            }
        }
    }

    private val minuteEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshWidgetTime(context, onlyIfScreenOn = true)
    }

    private var screenRegistered = false
    private var tickRegistered = false

    override fun onCreate() {
        super.onCreate()
        LockClock.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, LockClock.ID_SERVICE, notification(), type)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
        }
        ContextCompat.registerReceiver(this, screenEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!needed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // El texto de la notificación depende de qué está activo.
        NotificationManagerCompat.from(this).let { nm ->
            if (nm.areNotificationsEnabled()) runCatching { nm.notify(LockClock.ID_SERVICE, notification()) }
        }
        // Al empezar a escuchar el minuto, la hora se dibuja ya mismo con la fuente propia.
        if (syncMinuteTick()) refreshWidgetTime(this, onlyIfScreenOn = false)
        return START_STICKY
    }

    /**
     * El aviso de cada minuto despierta a la app: solo se pide si hay widgets con fuente propia.
     * Devuelve true si se empezó a escuchar recién.
     */
    private fun syncMinuteTick(): Boolean {
        val wanted = needsMinuteTick(this)
        if (wanted == tickRegistered) return false
        if (wanted) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(this, minuteEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            unregisterReceiver(minuteEvents)
        }
        tickRegistered = wanted
        return wanted
    }

    override fun onDestroy() {
        if (screenRegistered) unregisterReceiver(screenEvents)
        if (tickRegistered) unregisterReceiver(minuteEvents)
        screenRegistered = false
        tickRegistered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val prefs = Prefs(this)
        val open = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (prefs.lockClock) R.string.lock_service_text else R.string.font_service_text
        return NotificationCompat.Builder(this, LockClock.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(getString(R.string.lock_service_title))
            .setContentText(getString(text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        /** Hace falta el servicio si el reloj al bloquear está activo o algún widget usa fuente propia. */
        fun needed(context: Context): Boolean = Prefs(context).lockClock || needsMinuteTick(context)

        /** Con fuente propia, la hora del widget es una imagen que hay que redibujar cada minuto. */
        fun needsMinuteTick(context: Context): Boolean =
            CustomFont.selected(context) != null && widgetCount(context) > 0

        /** Arranca o detiene el servicio según las preferencias (y actualiza qué escucha). */
        fun sync(context: Context) {
            if (needed(context)) {
                runCatching { ContextCompat.startForegroundService(context, Intent(context, ClockService::class.java)) }
            } else {
                context.stopService(Intent(context, ClockService::class.java))
            }
        }

        private fun widgetCount(context: Context) = runCatching {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, GlassClockWidget::class.java)).size
        }.getOrDefault(0)

        /** Redibuja la hora del widget con fuente propia (cada minuto, solo con la pantalla encendida). */
        fun refreshWidgetTime(context: Context, onlyIfScreenOn: Boolean) {
            if (CustomFont.selected(context) == null) return
            if (onlyIfScreenOn && context.getSystemService(PowerManager::class.java)?.isInteractive == false) return
            WidgetRenderer.updateAll(context)
        }
    }
}

/** Vuelve a arrancar el servicio después de reiniciar el teléfono o actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ClockService.sync(context)
        }
    }
}
