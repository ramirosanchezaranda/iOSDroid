package com.ramiro.iosdroid

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Context
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
}
