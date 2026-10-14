package com.ramiro.iosdroid

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/** Un botón de la notificación ("Responder", "Marcar como leído"...). */
data class NotifAction(
    val title: String,
    val intent: PendingIntent,
    /** Si tiene campos de texto, se puede responder desde el reloj (algo que el iPhone no deja con todas las apps). */
    val remoteInputs: List<android.app.RemoteInput>,
    /** La app pide desbloquear antes de ejecutarla. */
    val needsUnlock: Boolean,
) {
    val isReply get() = remoteInputs.any { it.allowFreeFormInput }
}

/** Una notificación lista para mostrar en el reloj, como en la pantalla de bloqueo del iPhone. */
data class NotifItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    /** Texto que se puede mostrar con el teléfono bloqueado (versión pública), si la app la definió. */
    val publicText: String?,
    /** La app marcó el contenido como privado. */
    val private: Boolean,
    val autoCancel: Boolean,
    val contentIntent: PendingIntent?,
    val actions: List<NotifAction> = emptyList(),
    /** Imagen grande (BigPictureStyle), si la tiene. */
    val picture: android.graphics.Bitmap? = null,
    /** Se puede borrar (las persistentes ya se filtran, pero algunas apps no dejan borrar). */
    val clearable: Boolean = true,
) {
    /** Ícono de la app (se carga al dibujar, no se guarda en el modelo). */
    fun icon(context: Context): Drawable? = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
}

/** Las notificaciones activas, compartidas entre el servicio que las lee y las pantallas de reloj. */
object NotificationsHub {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(List<NotifItem>) -> Unit>()

    @Volatile
    var items: List<NotifItem> = emptyList()
        private set

    fun publish(list: List<NotifItem>) {
        main.post { publishNow(list) }
    }

    private fun publishNow(list: List<NotifItem>) {
        items = list
        listeners.toList().forEach { it(list) }
    }

    /** Las saca de la lista ya mismo (sin esperar al sistema); se filtra sobre la lista vigente al publicar. */
    private fun remove(keys: Set<String>) {
        main.post { publishNow(items.filter { it.key !in keys }) }
    }

    fun listen(listener: (List<NotifItem>) -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: (List<NotifItem>) -> Unit) {
        listeners -= listener
    }

    fun accessGranted(context: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Convierte una notificación del sistema en una tarjeta, o null si no corresponde mostrarla:
     * las persistentes (música, descargas, servicios), los resúmenes de grupo, las silenciosas,
     * las secretas y las de esta misma app.
     */
    fun fromNotification(
        context: Context,
        key: String,
        packageName: String,
        n: Notification,
        postTime: Long,
        importance: Int,
    ): NotifItem? {
        if (packageName == context.packageName) return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        if (importance in 0 until NotificationManager.IMPORTANCE_DEFAULT && importance != NotificationManager.IMPORTANCE_UNSPECIFIED) return null
        if (n.visibility == Notification.VISIBILITY_SECRET) return null
        val extras = n.extras
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString()?.trim().orEmpty()
        val text = lastMessage(n) ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val appName = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val publicText = n.publicVersion?.extras?.let {
            (it.getCharSequence(Notification.EXTRA_TITLE) ?: it.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        }
        return NotifItem(
            key = key,
            packageName = packageName,
            appName = appName,
            title = title.ifBlank { appName },
            text = text,
            postTime = postTime,
            publicText = publicText,
            private = n.visibility == Notification.VISIBILITY_PRIVATE,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            contentIntent = n.contentIntent,
            actions = n.actions.orEmpty().mapNotNull { a ->
                val intent = a.actionIntent ?: return@mapNotNull null
                val title = a.title?.toString()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                NotifAction(
                    title = title,
                    intent = intent,
                    remoteInputs = a.remoteInputs.orEmpty().toList(),
                    needsUnlock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && a.isAuthenticationRequired,
                )
            },
            picture = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    extras.getParcelable(Notification.EXTRA_PICTURE, android.graphics.Bitmap::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    extras.getParcelable(Notification.EXTRA_PICTURE) as? android.graphics.Bitmap
                }
            }.getOrNull(),
            clearable = n.flags and Notification.FLAG_NO_CLEAR == 0,
        )
    }

    /** Agrupadas por app, como las pilas del iPhone (la app con la más reciente, primero). */
    fun groups(items: List<NotifItem>): List<List<NotifItem>> =
        items.sortedByDescending { it.postTime }.groupBy { it.packageName }.values.toList()

    /** Sin las apps que se ocultaron del reloj por un rato. */
    fun visible(context: Context, items: List<NotifItem>, now: Long = System.currentTimeMillis()): List<NotifItem> {
        val muted = Prefs(context).mutedApps(now)
        return items.filter { it.packageName !in muted }
    }

    /** Responde desde el reloj (como en la barra de notificaciones de Android). */
    fun reply(context: Context, action: NotifAction, text: String) {
        val results = android.os.Bundle()
        action.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        val fill = android.content.Intent()
        android.app.RemoteInput.addResultsToIntent(action.remoteInputs.toTypedArray(), fill, results)
        runCatching { send(context, action.intent, fill) }
    }

    fun runAction(context: Context, action: NotifAction) {
        runCatching { send(context, action.intent, null) }
    }

    /** Posponer: las notificaciones vuelven solas más tarde (función de Android que el iPhone no tiene). */
    fun snooze(group: List<NotifItem>, millis: Long) {
        val service = NotificationsService.instance
        group.forEach { item -> service?.let { runCatching { it.snoozeNotification(item.key, millis) } } }
        remove(group.map { it.key }.toSet())
    }

    fun dismissAll(group: List<NotifItem>) {
        val keys = group.filter { it.clearable }.map { it.key }.toSet()
        if (keys.isEmpty()) return
        NotificationsService.instance?.let { runCatching { it.cancelNotifications(keys.toTypedArray()) } }
        remove(keys)
    }

    /** "Borrar todo" (lo que se ve en el reloj). */
    fun clearAll(shown: List<NotifItem> = items) {
        dismissAll(shown)
    }

    /** Ocultar una app del reloj por un rato (no cambia nada en el sistema). */
    fun muteApp(context: Context, packageName: String, millis: Long) {
        Prefs(context).muteApp(packageName, System.currentTimeMillis() + millis)
        main.post { publishNow(items) }
    }

    private fun send(context: Context, intent: PendingIntent, fill: android.content.Intent?) {
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: hay que ceder el permiso de abrir pantallas a la app de la notificación.
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            intent.send(context, 0, fill, null, null, null, options.toBundle())
        } else {
            intent.send(context, 0, fill)
        }
    }

    /** En chats (MessagingStyle), el último mensaje. */
    private fun lastMessage(n: Notification): String? {
        val messages = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? android.os.Bundle ?: return null
        val text = last.getCharSequence("text")?.toString()?.trim()
        val sender = last.getCharSequence("sender")?.toString()?.trim()
        val isGroup = n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        return when {
            text.isNullOrBlank() -> null
            isGroup && !sender.isNullOrBlank() -> "$sender: $text"
            else -> text
        }
    }

    /** Abre la app de la notificación (como tocarla en la barra). */
    fun open(context: Context, item: NotifItem) {
        val intent = item.contentIntent ?: return
        runCatching { send(context, intent, null) }
        if (item.autoCancel) dismiss(item)
    }

    fun dismiss(item: NotifItem) {
        NotificationsService.instance?.let { runCatching { it.cancelNotification(item.key) } }
        remove(setOf(item.key))
    }
}

/** Lee las notificaciones (requiere que el usuario le dé acceso en Ajustes). */
class NotificationsService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        NotificationsHub.publish(emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = refresh()

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val items = active.mapNotNull { sbn ->
            val r = Ranking()
            val importance = if (ranking?.getRanking(sbn.key, r) == true) r.importance else NotificationManager.IMPORTANCE_UNSPECIFIED
            NotificationsHub.fromNotification(this, sbn.key, sbn.packageName, sbn.notification, sbn.postTime, importance)
        }.sortedByDescending { it.postTime }
        NotificationsHub.publish(items)
    }

    companion object {
        @Volatile
        var instance: NotificationsService? = null
            private set
    }
}
