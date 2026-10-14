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
import org.junit.Assert.assertFalse
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
        "small" to (144f to 181f),
        "medium" to (222f to 181f),
        "large" to (222f to 273f),
        "wide" to (378f to 181f),
        "xl" to (378f to 273f),
    )

    @Before
    fun setUp() {
        // Martes 13 de octubre, 16:26 en Buenos Aires (como la captura del inicio).
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Forecast.today = { java.time.LocalDate.of(2026, 10, 13) }
        SystemClock.setCurrentTimeMillis(ZonedDateTime.of(2026, 10, 13, 16, 26, 0, 0, zone).toInstant().toEpochMilli())
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        Prefs(ctx).apply {
            place = Place("Buenos Aires", -34.61, -58.38, auto = true)
            weather = Weather(
                22.4, 2, true, 25.2, 14.1, TempUnit.CELSIUS, System.currentTimeMillis(),
                days = listOf(
                    DayForecast("2026-10-13", 2, 25.2, 14.1, 10),
                    DayForecast("2026-10-14", 0, 27.0, 15.3, 0),
                    DayForecast("2026-10-15", 61, 19.4, 12.2, 70),
                    DayForecast("2026-10-16", 3, 17.1, 9.8, 30),
                    DayForecast("2026-10-17", 1, 22.6, 11.0, 0),
                    DayForecast("2026-10-18", 95, 28.4, 18.9, 80),
                ),
            )
            textTone = TextTone.LIGHT
            style = GlassStyle.CLEAR
        }
    }

    @Test
    fun variantsMatchSizes() {
        assertEquals(WidgetRenderer.Variant.SMALL, WidgetRenderer.variantFor(140f, 180f))
        assertEquals(WidgetRenderer.Variant.MEDIUM, WidgetRenderer.variantFor(210f, 180f))
        assertEquals(WidgetRenderer.Variant.MEDIUM, WidgetRenderer.variantFor(250f, 110f))
        assertEquals(WidgetRenderer.Variant.LARGE, WidgetRenderer.variantFor(222f, 273f))
        assertEquals(WidgetRenderer.Variant.WIDE, WidgetRenderer.variantFor(378f, 181f))
        assertEquals(WidgetRenderer.Variant.XL, WidgetRenderer.variantFor(378f, 273f))
    }

    @Test
    fun largeShowsUpcomingDays() {
        val view = inflate(222f, 273f)
        assertEquals("Hoy", view.findViewById<TextView>(R.id.f0_day).text.toString())
        assertEquals("Mañana", view.findViewById<TextView>(R.id.f1_day).text.toString())
        assertEquals("Jue", view.findViewById<TextView>(R.id.f2_day).text.toString())
        assertEquals("19°", view.findViewById<TextView>(R.id.f2_max).text.toString())
        assertEquals("12°", view.findViewById<TextView>(R.id.f2_min).text.toString())
        // 3×3: tres días, para que la hora siga siendo lo principal.
        assertEquals(View.GONE, view.findViewById<View>(R.id.f3_row).visibility)
        assertNotNull(view.findViewById<ImageView>(R.id.f0_bar).drawable)
    }

    @Test
    fun xlUsesFullDayNamesAndCity() {
        val view = inflate(378f, 273f)
        assertEquals("Jueves", view.findViewById<TextView>(R.id.f2_day).text.toString())
        assertEquals("Buenos Aires", view.findViewById<TextView>(R.id.city).text.toString())
    }

    @Test
    fun dateFollowsChosenFamily() {
        Prefs(ctx).clockFont = ClockFont.SERIF
        var view = inflate(222f, 181f)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.date_serif).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.date).visibility)
        // Desactivado: la fecha vuelve a la fuente normal.
        Prefs(ctx).fontForDate = false
        view = inflate(222f, 181f)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.date).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.date_serif).visibility)
        Prefs(ctx).fontForDate = true
        // Las variantes de OPPO Sans (fina, gruesa...) comparten la fecha normal.
        Prefs(ctx).clockFont = ClockFont.HEAVY
        view = inflate(222f, 181f)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.date).visibility)
    }

    @Test
    fun onlyChosenFontIsVisible() {
        Prefs(ctx).clockFont = ClockFont.SERIF
        val view = inflate(222f, 181f)
        ClockFont.entries.forEach {
            val expected = if (it == ClockFont.SERIF) View.VISIBLE else View.GONE
            assertEquals(it.name, expected, view.findViewById<View>(it.timeId).visibility)
        }
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
        assertEquals("16:26", view.findViewById<TextView>(ClockFont.LIGHT.timeId).text.toString())
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
        Prefs(ctx).apply { style = GlassStyle.CLEAR; clockFont = ClockFont.SERIF }
        save(draw(222f, 181f, wallpaper = true), "date_serif_medium.png")
        Prefs(ctx).clockFont = ClockFont.LIGHT
        // Medida del hueco 3×3 libre en la captura del inicio (para componer la vista previa).
        save(draw(210f, 300f, wallpaper = false), "alpha_home_3x3.png")
        for (font in ClockFont.entries) {
            Prefs(ctx).clockFont = font
            save(draw(222f, 181f, wallpaper = true), "font_${font.key}.png")
        }
    }

    @Test
    fun renderCustomFontWidget() {
        val font = java.io.File(ctx.cacheDir, "Caladea-Regular.ttf")
        javaClass.classLoader!!.getResourceAsStream("testfonts/Caladea-Regular.ttf")!!.use { input ->
            font.outputStream().use { input.copyTo(it) }
        }
        CustomFont.importAll(ctx, listOf(android.net.Uri.fromFile(font)))
        try {
            save(draw(222f, 181f, wallpaper = true), "custom_font_medium.png")
            save(draw(222f, 273f, wallpaper = true), "custom_font_large.png")
            save(draw(144f, 181f, wallpaper = true), "custom_font_small.png")
            save(draw(378f, 273f, wallpaper = true), "custom_font_xl.png")
        } finally {
            CustomFont.list(ctx).forEach { CustomFont.remove(ctx, it) }
        }
    }

    /**
     * Solo local: si SFPRO_DIR apunta a archivos de SF Pro, los carga y dibuja la vista previa.
     * Las fuentes de Apple no se suben al repositorio (su licencia no permite redistribuirlas).
     */
    @Test
    fun renderWithLocalSfPro() {
        val dir = System.getenv("SFPRO_DIR")?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue(dir != null && dir.isDirectory)
        val files = dir!!.listFiles()!!.filter { it.name.endsWith(".otf", ignoreCase = true) }.sortedBy { it.name }
        val summary = CustomFont.importAll(ctx, files.map { android.net.Uri.fromFile(it) })
        try {
            assertEquals(files.size, summary.loaded.size)
            println("SF Pro cargadas: " + summary.loaded.joinToString { it.fullName } + " · elegida: " + CustomFont.selected(ctx)?.fullName)
            save(draw(222f, 273f, wallpaper = true), "sfpro_large.png")
            save(draw(378f, 181f, wallpaper = true), "sfpro_wide.png")
            CustomFont.select(ctx, summary.loaded.first { it.style == "Bold" })
            save(draw(222f, 181f, wallpaper = true), "sfpro_bold_medium.png")
            save(drawClock(landscape = false), "sfpro_clock.png")
        } finally {
            CustomFont.list(ctx).forEach { CustomFont.remove(ctx, it) }
        }
    }

    private fun sampleNotifications(): List<NotifItem> {
        val now = System.currentTimeMillis()
        fun n(key: String, app: String, title: String, text: String, minutesAgo: Long, private: Boolean = false) = NotifItem(
            key, ctx.packageName, app, title, text, now - minutesAgo * 60_000, null, private, true, null,
        )
        return listOf(
            n("1", "WhatsApp", "Ana", "¿Vamos al cine a las 9? Saco las entradas", 0),
            n("2", "Mercado Pago", "Te transfirieron $12.500", "Juan Pérez te envió dinero", 4, private = true),
            n("3", "Gmail", "Facturación", "Tu resumen de octubre ya está disponible", 38),
            n("4", "Trenes Argentinos", "Línea Mitre", "Servicio con demoras", 75),
            n("5", "Calendario", "Reunión", "Mañana 10:00", 130),
        )
    }

    @Test
    fun renderClockWithNotifications() {
        NotificationsHub.publish(sampleNotifications())
        shadowOf(Looper.getMainLooper()).idle()
        try {
            for (bg in SaverBackground.entries) {
                Prefs(ctx).saverBackground = bg
                save(drawClock(landscape = false), "clock_notifications_${bg.key}.png")
            }
        } finally {
            NotificationsHub.publish(emptyList())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun lockedClockHidesPrivateText() {
        NotificationsHub.publish(sampleNotifications())
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        try {
            val activity = Robolectric.buildActivity(ClockActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val texts = mutableListOf<String>()
            fun collect(v: View) {
                if (v is TextView) texts += v.text.toString()
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
            collect(activity.findViewById(R.id.ss_notifications))
            // La de Mercado Pago es privada: con el teléfono bloqueado solo se ve la app.
            assertTrue("Mercado Pago" in texts)
            assertTrue("Notificación" in texts)
            assertFalse(texts.any { "Juan Pérez" in it })
            // Las demás se ven completas; la quinta queda en la pila.
            assertTrue("Ana" in texts)
            assertTrue("2 notificaciones más" in texts)
        } finally {
            shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
            NotificationsHub.publish(emptyList())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun renderClockScreens() {
        for (bg in SaverBackground.entries) {
            Prefs(ctx).saverBackground = bg
            save(drawClock(landscape = false), "clock_${bg.key}_portrait.png")
        }
    }

    @Config(qualifiers = "es-w880dp-h400dp-land-xxhdpi")
    @Test
    fun renderClockLandscape() {
        Prefs(ctx).saverBackground = SaverBackground.BLACK
        Prefs(ctx).clockFont = ClockFont.BOLD
        save(drawClock(landscape = true), "clock_black_landscape.png")
        NotificationsHub.publish(sampleNotifications())
        shadowOf(Looper.getMainLooper()).idle()
        try {
            save(drawClock(landscape = true), "clock_notifications_landscape.png")
        } finally {
            NotificationsHub.publish(emptyList())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun drawClock(landscape: Boolean): Bitmap {
        val activity = Robolectric.buildActivity(ClockActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val w = ((if (landscape) 880 else 400) * density).roundToInt()
        val h = ((if (landscape) 400 else 880) * density).roundToInt()
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Fondo de ejemplo para ver el modo "Vidrio" (en el teléfono es tu fondo de pantalla).
        if (Prefs(ctx).saverBackground == SaverBackground.GLASS) {
            GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(0xFF000000.toInt(), 0xFF120403.toInt(), 0xCCE2470C.toInt(), 0xFFF59E42.toInt(), 0xFF3A0D05.toInt(), 0xFF000000.toInt()),
            ).apply { setBounds(0, 0, w, h) }.draw(Canvas(bmp))
        }
        root.draw(Canvas(bmp))
        return bmp
    }

    @Test
    fun renderSettingsWithCustomFont() {
        val font = java.io.File(ctx.cacheDir, "Caladea-Regular.ttf")
        javaClass.classLoader!!.getResourceAsStream("testfonts/Caladea-Regular.ttf")!!.use { input ->
            font.outputStream().use { input.copyTo(it) }
        }
        CustomFont.importAll(ctx, listOf(android.net.Uri.fromFile(font)))
        try {
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val root = activity.window.decorView
            val w = (400 * density).roundToInt()
            val h = (1900 * density).roundToInt()
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            save(bmp, "settings_custom_font.png")
        } finally {
            CustomFont.list(ctx).forEach { CustomFont.remove(ctx, it) }
        }
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
