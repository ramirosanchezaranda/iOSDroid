package com.ramiro.iosdroid

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "es")
class NotificationsTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun builder() = Notification.Builder(ctx, "test").setSmallIcon(R.drawable.ic_stat_clock)

    private fun item(n: Notification, importance: Int = NotificationManager.IMPORTANCE_DEFAULT, pkg: String = "com.whatsapp") =
        NotificationsHub.fromNotification(ctx, "k", pkg, n, 1_000L, importance)

    @After
    fun tearDown() {
        NotificationsHub.publish(emptyList())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun readsTitleAndText() {
        val it = item(builder().setContentTitle("Ana").setContentText("¿Vamos al cine?").build())
        assertNotNull(it)
        assertEquals("Ana", it!!.title)
        assertEquals("¿Vamos al cine?", it.text)
    }

    @Test
    fun skipsOngoingSilentSecretAndSummaries() {
        assertNull(item(builder().setContentTitle("Música").setOngoing(true).build()))
        assertNull(item(builder().setContentTitle("Resumen").setGroup("g").setGroupSummary(true).build()))
        assertNull(item(builder().setContentTitle("Silenciosa").build(), importance = NotificationManager.IMPORTANCE_LOW))
        assertNull(item(builder().setContentTitle("Secreta").setVisibility(Notification.VISIBILITY_SECRET).build()))
        assertNull(item(builder().setContentTitle("Propia").build(), pkg = ctx.packageName))
        assertNull(item(builder().build()))
    }

    @Test
    fun groupChatsShowSenderAndLastMessage() {
        val me = Person.Builder().setName("Yo").build()
        val style = Notification.MessagingStyle(me)
            .setConversationTitle("Familia")
            .setGroupConversation(true)
            .addMessage("hola", 1L, Person.Builder().setName("Ana").build())
            .addMessage("llego en 5", 2L, Person.Builder().setName("Pedro").build())
        val it = item(builder().setStyle(style).build())
        assertEquals("Pedro: llego en 5", it!!.text)
    }

    @Test
    fun privateNotificationsAreMarked() {
        val it = item(builder().setContentTitle("Banco").setContentText("Transferencia recibida").setVisibility(Notification.VISIBILITY_PRIVATE).build())
        assertTrue(it!!.private)
    }

    @Test
    fun dismissRemovesFromHub() {
        val a = item(builder().setContentTitle("A").setContentText("uno").build())!!.copy(key = "a")
        val b = a.copy(key = "b", title = "B")
        NotificationsHub.publish(listOf(a, b))
        shadowOf(Looper.getMainLooper()).idle()
        NotificationsHub.dismiss(a)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("b"), NotificationsHub.items.map { it.key })
    }

    @Test
    fun groupsByAppNewestFirst() {
        val base = item(builder().setContentTitle("A").setContentText("uno").build())!!
        val list = listOf(
            base.copy(key = "1", packageName = "a", postTime = 10),
            base.copy(key = "2", packageName = "b", postTime = 30),
            base.copy(key = "3", packageName = "a", postTime = 20),
        )
        assertEquals(listOf(listOf("2"), listOf("3", "1")), NotificationsHub.groups(list).map { g -> g.map { it.key } })
    }

    @Test
    fun snoozeAndDismissAllRemoveTheWholeGroup() {
        val base = item(builder().setContentTitle("A").setContentText("uno").build())!!
        val a = base.copy(key = "a"); val b = base.copy(key = "b"); val c = base.copy(key = "c", packageName = "otra")
        NotificationsHub.publish(listOf(a, b, c))
        shadowOf(Looper.getMainLooper()).idle()
        NotificationsHub.snooze(listOf(a, b), 60_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("c"), NotificationsHub.items.map { it.key })
        NotificationsHub.dismissAll(listOf(c))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(NotificationsHub.items.isEmpty())
    }

    @Test
    fun mutedAppsAreHiddenForAWhile() {
        val base = item(builder().setContentTitle("A").setContentText("uno").build())!!
        val list = listOf(base.copy(key = "a", packageName = "ruidosa"), base.copy(key = "b", packageName = "otra"))
        NotificationsHub.muteApp(ctx, "ruidosa", 60 * 60_000)
        assertEquals(listOf("b"), NotificationsHub.visible(ctx, list).map { it.key })
        assertEquals(2, NotificationsHub.visible(ctx, list, now = System.currentTimeMillis() + 2 * 60 * 60_000).size)
    }

    @Test
    fun replyFillsRemoteInputAndActionsAreRead() {
        val received = mutableListOf<Intent>()
        val action = "com.ramiro.iosdroid.TEST_REPLY"
        ctx.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received += intent }
        }, android.content.IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val pi = android.app.PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName), android.app.PendingIntent.FLAG_MUTABLE)
        val input = android.app.RemoteInput.Builder("reply").setLabel("Responder").build()
        val n = builder().setContentTitle("Ana").setContentText("hola")
            .addAction(Notification.Action.Builder(null, "Responder", pi).addRemoteInput(input).build())
            .addAction(Notification.Action.Builder(null, "Marcar como leído", pi).build())
            .build()
        val it = item(n)!!
        assertEquals(listOf("Responder", "Marcar como leído"), it.actions.map { a -> a.title })
        assertTrue(it.actions[0].isReply)
        NotificationsHub.reply(ctx, it.actions[0], "dale")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("dale", android.app.RemoteInput.getResultsFromIntent(received.single())?.getCharSequence("reply")?.toString())
    }
}
