package com.ramiro.iosdroid

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "es-xxhdpi")
class CustomFontTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun testFont(): File {
        val out = File(ctx.cacheDir, "Caladea-Regular.ttf")
        javaClass.classLoader!!.getResourceAsStream("testfonts/Caladea-Regular.ttf")!!.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
    }

    @After
    fun tearDown() {
        CustomFont.remove(ctx)
        Prefs(ctx).useCustomFont = false
    }

    @Test
    fun recognizesFontFiles() {
        assertTrue(CustomFont.looksLikeFont(testFont()))
        val text = File(ctx.cacheDir, "notes.txt").apply { writeText("hola, esto no es una fuente") }
        assertFalse(CustomFont.looksLikeFont(text))
    }

    @Test
    fun importsValidFontAndRejectsOthers() {
        val bad = File(ctx.cacheDir, "foto.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1)) }
        assertEquals(CustomFont.ImportResult.Invalid, CustomFont.import(ctx, Uri.fromFile(bad)))
        assertFalse(CustomFont.exists(ctx))

        val result = CustomFont.import(ctx, Uri.fromFile(testFont()))
        assertTrue(result is CustomFont.ImportResult.Ok)
        assertTrue(CustomFont.exists(ctx))
        assertNotNull(CustomFont.typeface(ctx, 400))
    }

    @Test
    fun widgetDrawsTimeWithCustomFont() {
        CustomFont.import(ctx, Uri.fromFile(testFont()))
        Prefs(ctx).useCustomFont = true
        val view = WidgetRenderer.build(ctx, 222f, 181f).apply(ctx, FrameLayout(ctx))
        val image = view.findViewById<ImageView>(R.id.time_image)
        assertEquals(View.VISIBLE, image.visibility)
        assertNotNull(image.drawable)
        assertEquals(WidgetRenderer.currentTime(ctx), image.contentDescription)
        ClockFont.entries.forEach { assertEquals(it.name, View.GONE, view.findViewById<View>(it.timeId).visibility) }
    }

    @Test
    fun serviceIsOnlyNeededWithWidgetsOrLockClock() {
        CustomFont.import(ctx, Uri.fromFile(testFont()))
        Prefs(ctx).apply { useCustomFont = true; lockClock = false }
        // Sin widgets en la pantalla, no hace falta el servicio para la fuente.
        assertFalse(ClockService.needed(ctx))
        Prefs(ctx).lockClock = true
        assertTrue(ClockService.needed(ctx))
        Prefs(ctx).lockClock = false
    }
}
