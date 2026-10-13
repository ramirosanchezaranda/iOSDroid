package com.ramiro.iosdroid

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Dibuja el widget real (mismos layouts y mismo vidrio) y la pantalla de ajustes a PNG
 * en app/build/snapshots, para revisarlos sin un teléfono.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "es-w400dp-h880dp-xxhdpi")
class WidgetSnapshotTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val out = File("build/snapshots").apply { mkdirs() }
    private val density get() = ctx.resources.displayMetrics.density

    private val sizes = linkedMapOf(
        "small" to (150f to 180f),
        "medium" to (210f to 180f),
        "large" to (210f to 275f),
    )

    @Before
    fun setUp() {
        // Martes 13 de octubre, 16:26 en Buenos Aires (como la captura del inicio).
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        SystemClock.setCurrentTimeMillis(ZonedDateTime.of(2026, 10, 13, 16, 26, 0, 0, zone).toInstant().toEpochMilli())
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        Prefs(ctx).apply {
            place = Place("Buenos Aires", -34.61, -58.38, auto = true)
            weather = Weather(22.4, 2, true, 25.2, 14.1, TempUnit.CELSIUS, System.currentTimeMillis())
            textTone = TextTone.LIGHT
            style = GlassStyle.CLEAR
        }
    }

    @Test
    fun variantsMatchSizes() {
        assertEquals(WidgetRenderer.Variant.SMALL, WidgetRenderer.variantFor(140f, 180f))
        assertEquals(WidgetRenderer.Variant.MEDIUM, WidgetRenderer.variantFor(210f, 180f))
        assertEquals(WidgetRenderer.Variant.MEDIUM, WidgetRenderer.variantFor(250f, 110f))
        assertEquals(WidgetRenderer.Variant.LARGE, WidgetRenderer.variantFor(210f, 275f))
    }

    @Test
    fun widgetShowsWeather() {
        val view = inflate(210f, 180f)
        assertEquals("22°", view.findViewById<TextView>(R.id.temp).text.toString())
        assertEquals("Parcialmente nublado", view.findViewById<TextView>(R.id.condition).text.toString())
        assertNotNull(view.findViewById<ImageView>(R.id.glass).drawable)
    }

    @Test
    fun widgetShowsTimeAndDate() {
        val bmp = draw(210f, 180f, wallpaper = false)
        assertNotNull(bmp)
        val view = lastWidget!!
        assertEquals("16:26", view.findViewById<TextView>(R.id.time).text.toString())
        assertEquals("martes, 13 de octubre", view.findViewById<TextView>(R.id.date).text.toString())
    }

    @Test
    fun widgetWithoutLocationAsksForSetup() {
        Prefs(ctx).apply { place = null; weather = null }
        val view = inflate(210f, 180f)
        assertEquals("--°", view.findViewById<TextView>(R.id.temp).text.toString())
        assertEquals("Toca para configurar", view.findViewById<TextView>(R.id.condition).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.wx_icon).visibility)
    }

    @Test
    fun renderWidgetSnapshots() {
        for (style in GlassStyle.entries) {
            Prefs(ctx).style = style
            for ((name, size) in sizes) {
                val (w, h) = size
                // Sobre fondo oscuro (para ver el resultado) y transparente (para componer).
                save(draw(w, h, wallpaper = true), "widget_${style.key}_$name.png")
                save(draw(w, h, wallpaper = false), "alpha_${style.key}_$name.png")
            }
        }
        Prefs(ctx).apply { style = GlassStyle.FROSTED; textTone = TextTone.DARK }
        save(draw(210f, 180f, wallpaper = false), "alpha_frosted_dark_medium.png")
    }

    @Test
    fun renderSettingsScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val w = (400 * density).roundToInt()
        val h = (1700 * density).roundToInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        save(bmp, "settings.png")
    }

    private var lastWidget: View? = null

    private fun inflate(wDp: Float, hDp: Float): View {
        val parent = FrameLayout(ctx)
        return WidgetRenderer.build(ctx, wDp, hDp).apply(ctx, parent).also { lastWidget = it }
    }

    private fun draw(wDp: Float, hDp: Float, wallpaper: Boolean): Bitmap {
        val margin = if (wallpaper) (20 * density).roundToInt() else 0
        val wPx = (wDp * density).roundToInt()
        val hPx = (hDp * density).roundToInt()
        val root = FrameLayout(ctx)
        if (wallpaper) {
            root.background = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(0xFF000000.toInt(), 0xFF120403.toInt(), 0xCCE2470C.toInt(), 0xFFF59E42.toInt(), 0xFF3A0D05.toInt(), 0xFF000000.toInt()),
            )
        }
        val widget = inflate(wDp, hDp)
        root.addView(widget, FrameLayout.LayoutParams(wPx, hPx).apply { setMargins(margin, margin, margin, margin) })
        val totalW = wPx + 2 * margin
        val totalH = hPx + 2 * margin
        // El TextClock solo escribe la hora cuando está en una ventana visible.
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        host.setContentView(root, ViewGroup.LayoutParams(totalW, totalH))
        shadowOf(Looper.getMainLooper()).idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(totalW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(totalH, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, totalW, totalH)
        val bmp = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        return bmp
    }

    private fun save(bmp: Bitmap, name: String) {
        val distinct = HashSet<Int>()
        for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) distinct += bmp.getPixel(x, y)
        assertTrue("$name parece vacío", distinct.size > 20)
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
