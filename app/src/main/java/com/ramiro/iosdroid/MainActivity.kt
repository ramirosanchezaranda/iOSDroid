package com.ramiro.iosdroid

import android.Manifest
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.GradientDrawable.Orientation
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/** Ajustes del widget con vista previa en vivo. */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var previewWall: FrameLayout
    private lateinit var previewSlot: FrameLayout
    private lateinit var cityValue: TextView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var results: LinearLayout

    private var previewSize = 1
    private var wallpaper = 0
    private var searchJob: Job? = null

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateMe() else toast(R.string.location_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        // Si el launcher la abre para "configurar" el widget, siempre confirmamos.
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }

        previewWall = findViewById(R.id.preview_wall)
        previewSlot = findViewById(R.id.preview_slot)
        cityValue = findViewById(R.id.city_value)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        results = findViewById(R.id.results)

        previewWall.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(28f).toFloat())
            }
        }
        previewWall.clipToOutline = true
        findViewById<View>(R.id.preview_touch).setOnClickListener {
            wallpaper = (wallpaper + 1) % WALLPAPER_COUNT
            applyWallpaper()
        }
        applyWallpaper()

        segmented(R.id.seg_size, listOf(R.string.size_small, R.string.size_medium, R.string.size_large), previewSize) {
            previewSize = it
            renderPreview()
        }
        segmented(R.id.seg_glass, listOf(R.string.glass_clear, R.string.glass_frosted, R.string.glass_dark), prefs.style.ordinal) {
            prefs.style = GlassStyle.entries[it]
            settingsChanged()
        }
        segmented(R.id.seg_text, listOf(R.string.text_auto, R.string.text_light, R.string.text_dark), prefs.textTone.ordinal) {
            prefs.textTone = TextTone.entries[it]
            settingsChanged()
        }
        segmented(R.id.seg_unit, listOf(R.string.celsius, R.string.fahrenheit), prefs.unit.ordinal) {
            prefs.unit = TempUnit.entries[it]
            WeatherWorker.refreshNow(this)
        }

        findViewById<View>(R.id.pin).setOnClickListener { pinWidget() }
        findViewById<View>(R.id.use_location).setOnClickListener { requestLocation() }
        findViewById<View>(R.id.refresh).setOnClickListener {
            WeatherWorker.refreshNow(this)
            toast(R.string.refreshing)
        }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }

        // Cuando termina una descarga del clima, refrescamos la vista previa.
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(WeatherWorker.NOW).observe(this) {
            renderCity()
            renderPreview()
            renderStatus()
        }

        WeatherWorker.schedule(this)
        val place = prefs.place
        when {
            place == null && LocationHelper.hasPermission(this) -> locateMe()
            place == null -> askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            place.auto && LocationHelper.hasPermission(this) -> locateMe(quiet = true)
            else -> WeatherWorker.refreshIfStale(this)
        }
    }

    override fun onResume() {
        super.onResume()
        renderCity()
        renderPreview()
        renderStatus()
    }

    private fun settingsChanged() {
        renderPreview()
        WidgetRenderer.updateAll(this)
    }

    private fun renderPreview() {
        val (w, h) = when (previewSize) {
            0 -> 150f to 150f
            2 -> 220f to 280f
            else -> 230f to 190f
        }
        previewSlot.layoutParams = previewSlot.layoutParams.apply {
            width = dp(w)
            height = dp(h)
        }
        previewWall.layoutParams = previewWall.layoutParams.apply { height = dp(h + 70f) }
        previewSlot.removeAllViews()
        runCatching { WidgetRenderer.build(this, w, h).apply(this, previewSlot) }
            .onSuccess { previewSlot.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
    }

    private fun renderCity() {
        cityValue.text = prefs.place?.name?.ifBlank { null } ?: getString(R.string.not_set)
    }

    private fun renderStatus() {
        val weather = prefs.weather
        status.text = if (weather == null) {
            getString(R.string.status_never)
        } else {
            val time = DateFormat.getTimeFormat(this).format(Date(weather.updatedAt))
            getString(R.string.status_updated, time, prefs.place?.name.orEmpty())
        }
    }

    // --- Ubicación ---

    private fun requestLocation() {
        if (LocationHelper.hasPermission(this)) locateMe() else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun locateMe(quiet: Boolean = false) {
        if (!quiet) cityValue.setText(R.string.locating)
        lifecycleScope.launch {
            val loc = LocationHelper.current(this@MainActivity)
            if (loc == null) {
                renderCity()
                if (!quiet) toast(R.string.location_failed)
                WeatherWorker.refreshIfStale(this@MainActivity)
                return@launch
            }
            val old = prefs.place
            val moved = old == null || !old.auto ||
                LocationHelper.distanceKm(old.lat, old.lon, loc.latitude, loc.longitude) > 3.0
            val name = LocationHelper.cityName(this@MainActivity, loc.latitude, loc.longitude)
                ?: old?.takeIf { it.auto && !moved }?.name?.ifBlank { null }
                ?: getString(R.string.my_location)
            prefs.place = Place(name, loc.latitude, loc.longitude, auto = true)
            if (moved) prefs.weather = null
            renderCity()
            settingsChanged()
            if (moved || !quiet) WeatherWorker.refreshNow(this@MainActivity) else WeatherWorker.refreshIfStale(this@MainActivity)
        }
    }

    private fun runSearch() {
        val query = search.text.toString().trim()
        if (query.length < 2) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        searchJob?.cancel()
        showResultMessage(R.string.searching)
        searchJob = lifecycleScope.launch {
            val found = runCatching { OpenMeteo.searchCity(query) }
            results.removeAllViews()
            found.onSuccess { list ->
                if (list.isEmpty()) showResultMessage(R.string.no_results) else list.forEach(::addResult)
            }.onFailure { showResultMessage(R.string.network_error) }
        }
    }

    private fun showResultMessage(text: Int) {
        results.removeAllViews()
        results.addView(separator())
        results.addView(TextView(this).apply {
            setText(text)
            setTextColor(getColor(R.color.ios_secondary))
            textSize = 15f
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        })
    }

    private fun addResult(result: OpenMeteo.CityResult) {
        results.addView(separator())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = selectableBackground()
            addView(TextView(context).apply {
                text = result.place.name
                setTextColor(getColor(R.color.ios_label))
                textSize = 17f
            })
            if (result.detail.isNotBlank()) {
                addView(TextView(context).apply {
                    text = result.detail
                    setTextColor(getColor(R.color.ios_secondary))
                    textSize = 13f
                })
            }
            setOnClickListener {
                prefs.place = result.place
                prefs.weather = null
                results.removeAllViews()
                search.text.clear()
                search.clearFocus()
                renderCity()
                settingsChanged()
                WeatherWorker.refreshNow(this@MainActivity)
            }
        }
        results.addView(row)
    }

    // --- Widget ---

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val requested = manager.isRequestPinAppWidgetSupported &&
            manager.requestPinAppWidget(ComponentName(this, GlassClockWidget::class.java), null, null)
        if (!requested) {
            AlertDialog.Builder(this)
                .setMessage(R.string.add_manually)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // --- Vista previa: fondos de ejemplo para ver el vidrio ---

    private fun applyWallpaper() {
        val radius = dp(28f).toFloat()
        fun grad(orientation: Orientation, vararg colors: Long): Drawable =
            GradientDrawable(orientation, colors.map { it.toInt() }.toIntArray()).apply { cornerRadius = radius }
        previewWall.background = when (wallpaper) {
            // Negro con un haz ámbar en diagonal (parecido a tu fondo actual).
            0 -> LayerDrawable(
                arrayOf(
                    grad(Orientation.TL_BR, 0xFF000000, 0xFF0A0302, 0xFF170604),
                    grad(
                        Orientation.BL_TR, 0x00000000, 0x00000000, 0x33B8330A, 0xCCE2470C,
                        0xFFF59E42, 0x66B8330A, 0x00000000, 0x00000000,
                    ),
                ),
            )
            // Celeste claro tipo vidrio.
            1 -> grad(Orientation.TOP_BOTTOM, 0xFFEEF2F8, 0xFFD3DFF2, 0xFF9DBBEA, 0xFF4A7FD6)
            // Azul profundo.
            else -> grad(Orientation.TL_BR, 0xFF0B1D3A, 0xFF1F4E8C, 0xFF3D7CC9, 0xFF7FB2E5)
        }
    }

    // --- Utilidades de UI ---

    private fun segmented(id: Int, labels: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
        val group = findViewById<LinearLayout>(id)
        group.removeAllViews()
        labels.forEachIndexed { index, label ->
            val item = TextView(this).apply {
                setText(label)
                gravity = Gravity.CENTER
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(Typeface.DEFAULT, 500, false)
                } else {
                    Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                setTextColor(getColor(R.color.ios_label))
                setBackgroundResource(R.drawable.seg_thumb)
                isSelected = index == selected
                setOnClickListener {
                    group.children.forEach { it.isSelected = false }
                    isSelected = true
                    onSelect(index)
                }
            }
            group.addView(item, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    private fun separator() = View(this).apply {
        setBackgroundColor(getColor(R.color.ios_separator))
        layoutParams = LinearLayout.LayoutParams(MATCH, max(1, dp(0.5f))).apply { marginStart = dp(16f) }
    }

    private fun selectableBackground(): Drawable? {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return getDrawable(value.resourceId)
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WALLPAPER_COUNT = 3
    }
}
