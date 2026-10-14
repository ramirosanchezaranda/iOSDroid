package com.ramiro.iosdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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

    /** Arranca o detiene el servicio según la preferencia. */
    fun sync(context: Context) {
        if (Prefs(context).lockClock) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LockClockService::class.java))
            }
        } else {
            context.stopService(Intent(context, LockClockService::class.java))
        }
    }
}

/** Servicio en primer plano que escucha el apagado de pantalla (solo se puede escuchar mientras corre). */
class LockClockService : Service() {

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) LockClock.onScreenOff(context)
        }
    }
    private var registered = false

    override fun onCreate() {
        super.onCreate()
        LockClock.createChannels(this)
        val open = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, LockClock.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(getString(R.string.lock_service_title))
            .setContentText(getString(R.string.lock_service_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, LockClock.ID_SERVICE, notification, type)
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).lockClock) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(screenOff)
        registered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Vuelve a activar el reloj en la pantalla bloqueada después de reiniciar o actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> LockClock.sync(context)
        }
    }
}
