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
import org.junit.Assert.assertNull
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

    private fun importTestFont(): LoadedFont =
        (CustomFont.import(ctx, Uri.fromFile(testFont())) as CustomFont.ImportResult.Ok).font

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
    }

    @After
    fun tearDown() {
        CustomFont.list(ctx).forEach { CustomFont.remove(ctx, it) }
        Prefs(ctx).fontForDate = true
    }

    @Test
    fun recognizesFontFiles() {
        assertTrue(CustomFont.looksLikeFont(testFont()))
        val text = File(ctx.cacheDir, "notes.txt").apply { writeText("hola, esto no es una fuente") }
        assertFalse(CustomFont.looksLikeFont(text))
    }

    @Test
    fun readsNamesFromTheFont() {
        assertEquals("Caladea" to "Regular", CustomFont.readNames(testFont()))
        assertFalse(CustomFont.isVariable(testFont()))
    }

    @Test
    fun importsValidFontsAndRejectsOthers() {
        val bad = File(ctx.cacheDir, "foto.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1)) }
        val summary = CustomFont.importAll(ctx, listOf(Uri.fromFile(bad), Uri.fromFile(testFont())))
        assertEquals(1, summary.loaded.size)
        assertEquals(1, summary.invalid)
        assertEquals("Caladea Regular", summary.loaded.first().fullName)
        // La primera fuente cargada queda elegida.
        assertEquals(summary.loaded.first(), CustomFont.selected(ctx))
        assertNotNull(CustomFont.active(ctx))
    }

    @Test
    fun loadingTheSameFontTwiceReplacesIt() {
        importTestFont()
        importTestFont()
        assertEquals(1, CustomFont.list(ctx).size)
    }

    @Test
    fun prefersRegularAmongSeveralStyles() {
        val fonts = listOf("Bold", "Thin Italic", "Regular", "Medium").map { LoadedFont(it, "SF Pro Display", it, false) }
        assertEquals("Regular", CustomFont.preferred(fonts).style)
        assertEquals("Medium", CustomFont.preferred(fonts.filter { it.style != "Regular" }).style)
    }

    @Test
    fun removingTheChosenFontGoesBackToSystemFonts() {
        val font = importTestFont()
        CustomFont.select(ctx, font)
        CustomFont.remove(ctx, font)
        assertNull(CustomFont.selected(ctx))
        assertTrue(CustomFont.list(ctx).isEmpty())
    }

    @Test
    fun migratesTheSingleFontFromVersion13() {
        val old = File(File(ctx.filesDir, "fonts"), "custom_font").apply { parentFile!!.mkdirs() }
        testFont().copyTo(old, overwrite = true)
        ctx.getSharedPreferences("glass_widget", Context.MODE_PRIVATE).edit().putBoolean("custom_font", true).commit()
        val fonts = CustomFont.list(ctx)
        assertEquals(1, fonts.size)
        assertEquals("Caladea", fonts.first().family)
        assertEquals(fonts.first(), CustomFont.selected(ctx))
        assertFalse(old.exists())
    }

    @Test
    fun widgetDrawsTimeWithCustomFont() {
        CustomFont.select(ctx, importTestFont())
        val view = WidgetRenderer.build(ctx, 222f, 181f).apply(ctx, FrameLayout(ctx))
        val image = view.findViewById<ImageView>(R.id.time_image)
        assertEquals(View.VISIBLE, image.visibility)
        assertNotNull(image.drawable)
        assertEquals(WidgetRenderer.currentTime(ctx), image.contentDescription)
        ClockFont.entries.forEach { assertEquals(it.name, View.GONE, view.findViewById<View>(it.timeId).visibility) }
        // La fecha también se dibuja con la fuente propia.
        val date = view.findViewById<ImageView>(R.id.date_image)
        assertEquals(View.VISIBLE, date.visibility)
        assertNotNull(date.drawable)
        ClockFont.DATE_IDS.forEach { assertEquals(View.GONE, view.findViewById<View>(it).visibility) }
    }

    @Test
    fun dateCanKeepTheNormalFont() {
        CustomFont.select(ctx, importTestFont())
        Prefs(ctx).fontForDate = false
        val view = WidgetRenderer.build(ctx, 222f, 181f).apply(ctx, FrameLayout(ctx))
        assertEquals(View.GONE, view.findViewById<View>(R.id.date_image).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.date).visibility)
    }

    @Test
    fun serviceIsOnlyNeededWithWidgetsOrLockClock() {
        CustomFont.select(ctx, importTestFont())
        Prefs(ctx).lockClock = false
        // Sin widgets en la pantalla, no hace falta el servicio para la fuente.
        assertFalse(ClockService.needed(ctx))
        Prefs(ctx).lockClock = true
        assertTrue(ClockService.needed(ctx))
        Prefs(ctx).lockClock = false
    }
}
