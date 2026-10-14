package com.ramiro.iosdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Reloj en la pantalla bloqueada: cada vez que se apaga la pantalla (al bloquear con el botón o
 * por tiempo) abre el modo reloj encima de la pantalla de bloqueo.
 *
 * Android no deja que una app reemplace la pantalla de bloqueo, así que se hace como las apps de
 * alarma: un servicio en primer plano escucha el apagado de pantalla y lanza [ClockActivity]
 * (que se muestra sobre el bloqueo) con una notificación de pantalla completa.
 */
object LockClock {

    const val CHANNEL_SERVICE = "lock_service"
    const val CHANNEL_SHOW = "lock_show"
    const val ID_SERVICE = 41
    const val ID_SHOW = 42

    /** Si el reloj se estaba viendo hasta hace este tiempo, el usuario apagó la pantalla a propósito. */
    private const val JUST_SEEN_MS = 2_500L

    fun onScreenOff(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        if (!Prefs(context).lockClock) return
        // Apagaste la pantalla mirando el reloj: queda debajo y no se vuelve a encender sola.
        if (ClockActivity.resumed || now - ClockActivity.lastPausedAt < JUST_SEEN_MS) return
        show(context)
    }

    fun show(context: Context) {
        val intent = ClockActivity.lockIntent(context)
        // Directo: funciona si el sistema permite abrir pantallas desde segundo plano.
        runCatching { context.startActivity(intent) }
        // Vía oficial: notificación de pantalla completa (como una alarma). La actividad la borra al abrirse.
        if (!notificationsAllowed(context)) return
        createChannels(context)
        val pending = PendingIntent.getActivity(
            context, 10, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SHOW)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(context.getString(R.string.dream_name))
            .setContentText(context.getString(R.string.lock_show_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_SHOW, notification) }
    }

    fun cancelShowNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_SHOW)
    }

    fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** En Android 14+ el usuario puede negar las notificaciones de pantalla completa. */
    fun fullScreenAllowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.lock_channel_service), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SHOW, context.getString(R.string.lock_channel_show), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }
}

/**
 * Servicio en primer plano (con su notificación fija) para lo que necesita escuchar eventos del
 * sistema mientras la app no está abierta:
 * - el apagado de pantalla, para mostrar el reloj al bloquear ([LockClock]);
 * - el paso de los minutos, para redibujar la hora del widget cuando usa una fuente propia.
 * Solo corre si alguna de las dos cosas está activa.
 */
class ClockService : Service() {

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> LockClock.onScreenOff(context)
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refreshWidgetTime(context, onlyIfScreenOn = true)
                Intent.ACTION_SCREEN_ON -> refreshWidgetTime(context, onlyIfScreenOn = false)
            }
        }
    }
    private var registered = false

    override fun onCreate() {
        super.onCreate()
        LockClock.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, LockClock.ID_SERVICE, notification(), type)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, events, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
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
        refreshWidgetTime(this, onlyIfScreenOn = false)
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(events)
        registered = false
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
        fun needed(context: Context): Boolean {
            val prefs = Prefs(context)
            return prefs.lockClock || (prefs.useCustomFont && CustomFont.exists(context) && widgetCount(context) > 0)
        }

        /** Arranca o detiene el servicio según las preferencias. */
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

        /** Con fuente propia, la hora del widget es una imagen: se redibuja cada minuto con la pantalla encendida. */
        fun refreshWidgetTime(context: Context, onlyIfScreenOn: Boolean) {
            if (!Prefs(context).useCustomFont || !CustomFont.exists(context)) return
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
