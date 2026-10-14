package com.ramiro.iosdroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.GradientDrawable.Orientation
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.max
import kotlin.math.min
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

    private var previewSize = 2
    private var wallpaper = 0
    private var searchJob: Job? = null
    private var askedNotifications = false

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateMe() else toast(R.string.location_denied)
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        renderLockState()
    }

    // Selector de archivos (el del sistema o "Mis archivos"), con varios a la vez.
    private val pickFonts = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val uris = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add) }
            if (isEmpty()) data.data?.let(::add)
        }
        if (uris.isNotEmpty()) importFonts(uris)
    }

    // Con fuente propia la hora de la vista previa es una imagen: se redibuja cada minuto.
    private val minuteTick = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (CustomFont.selected(this@MainActivity) != null) renderPreview()
        }
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

        segmented(R.id.seg_size, PREVIEW_SIZES.map { it.first }, previewSize) {
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
        setupFonts()
        setupFontForDate()
        setupLock()
        setupNotifications()
        setupClockMode()

        findViewById<View>(R.id.pin).apply {
            pressFeedback()
            setOnClickListener { pinWidget() }
        }
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

    override fun onStart() {
        super.onStart()
        androidx.core.content.ContextCompat.registerReceiver(
            this, minuteTick, android.content.IntentFilter(Intent.ACTION_TIME_TICK),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(minuteTick)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Al volver de Ajustes (permisos, fondo de pantalla) o si el sistema detuvo el servicio.
        WidgetRenderer.forgetWallpaper()
        ClockService.sync(this)
        renderLockState()
        renderNotificationState()
        renderCity()
        renderPreview()
        renderStatus()
    }

    private fun settingsChanged() {
        renderPreview()
        WidgetRenderer.updateAll(this)
    }

    private fun renderPreview() {
        val (w, h) = PREVIEW_SIZES[previewSize].second
        previewSlot.layoutParams = previewSlot.layoutParams.apply {
            width = dp(w)
            height = dp(h)
        }
        // Los tamaños anchos (5 columnas) se achican para entrar en la tarjeta.
        val available = (previewWall.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(32f))) - dp(24f)
        val scale = min(1f, available / dp(w).toFloat())
        previewSlot.scaleX = scale
        previewSlot.scaleY = scale
        previewWall.layoutParams = previewWall.layoutParams.apply { height = (dp(h) * scale).roundToInt() + dp(56f) }
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

    // --- Fuente de la hora ---

    private fun setupFonts() {
        val row = findViewById<LinearLayout>(R.id.fonts)
        row.removeAllViews()
        val selected = CustomFont.selected(this)
        var selectedIndex = 0

        // "+" primero, así se ve sin deslizar.
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.font_chip)
            contentDescription = getString(R.string.font_load_row)
            addView(TextView(context).apply {
                text = "+"
                textSize = 30f
                includeFontPadding = false
                setTextColor(getColor(R.color.ios_blue))
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(TextView(context).apply {
                setText(R.string.font_add)
                textSize = 12f
                setTextColor(getColor(R.color.ios_blue))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6f) })
            pressFeedback()
            setOnClickListener { openFontPicker() }
        }, chipParams())

        // Tus fuentes, con el nombre que trae el archivo ("Bold" / "SF Pro Display").
        CustomFont.list(this).forEach { font ->
            val typeface = CustomFont.typeface(this, font, prefs.customFontWeight) ?: return@forEach
            val title = font.style.ifBlank { font.family }
            val subtitle = font.family.takeIf { font.style.isNotBlank() }
            if (font.id == selected?.id) selectedIndex = row.childCount
            val chip = fontChip(typeface, -0.01f, title, subtitle, selected = font.id == selected?.id) {
                CustomFont.select(this, font)
                fontChanged()
            }
            chip.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.font_remove_title, font.fullName))
                    .setPositiveButton(R.string.font_remove) { _, _ ->
                        CustomFont.remove(this, font)
                        fontChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            row.addView(chip, chipParams())
        }

        // Fuentes del sistema (OPPO Sans en distintos pesos, Serif, Mono...).
        ClockFont.entries.forEach { font ->
            val isSelected = selected == null && font == prefs.clockFont
            if (isSelected) selectedIndex = row.childCount
            row.addView(fontChip(font.typeface(), font.letterSpacing, getString(font.label), null, isSelected) {
                CustomFont.select(this, null)
                prefs.clockFont = font
                fontChanged()
            }, chipParams())
        }

        // El peso solo se puede elegir en fuentes variables.
        segmented(
            R.id.seg_font_weight,
            listOf(R.string.font_thin, R.string.font_light, R.string.font_regular, R.string.font_bold),
            CustomFont.WEIGHTS.indexOf(prefs.customFontWeight).coerceAtLeast(0),
        ) {
            prefs.customFontWeight = CustomFont.WEIGHTS[it]
            fontChanged()
        }
        val weightVisibility = if (selected?.variable == true) View.VISIBLE else View.GONE
        findViewById<View>(R.id.seg_font_weight).visibility = weightVisibility
        findViewById<View>(R.id.font_weight_sep).visibility = weightVisibility

        // Que la fuente elegida quede a la vista.
        val scroll = findViewById<HorizontalScrollView>(R.id.font_scroll)
        scroll.post {
            val chip = row.getChildAt(selectedIndex) ?: return@post
            scroll.scrollTo(max(0, chip.left - dp(12f)), 0)
        }
    }

    private fun openFontPicker() {
        val get = Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        runCatching { pickFonts.launch(Intent.createChooser(get, getString(R.string.font_pick_title))) }
    }

    private fun importFonts(uris: List<Uri>) {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { CustomFont.importAll(this@MainActivity, uris) }
            if (summary.loaded.isNotEmpty()) fontChanged()
            Toast.makeText(this@MainActivity, ImportFontActivity.message(this@MainActivity, summary), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupFontForDate() {
        findViewById<View>(R.id.font_load).setOnClickListener { openFontPicker() }
        val switch = findViewById<Switch>(R.id.font_date_switch)
        switch.isChecked = prefs.fontForDate
        switch.setOnCheckedChangeListener { view, on ->
            selectionTick(view)
            prefs.fontForDate = on
            settingsChanged()
        }
        findViewById<View>(R.id.font_date_row).setOnClickListener { switch.toggle() }
    }

    private fun fontChip(
        typeface: android.graphics.Typeface,
        spacing: Float,
        label: String,
        subtitle: String?,
        selected: Boolean,
        onPick: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.font_chip)
        setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
        isSelected = selected
        contentDescription = listOfNotNull(subtitle, label).joinToString(" ")
        addView(TextView(context).apply {
            setText(R.string.font_sample)
            this.typeface = typeface
            letterSpacing = spacing
            textSize = 30f
            includeFontPadding = false
            setTextColor(getColor(R.color.ios_label))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(TextView(context).apply {
            text = label
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.ios_secondary))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6f) })
        if (subtitle != null) {
            addView(TextView(context).apply {
                text = subtitle
                textSize = 10f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.ios_secondary))
                alpha = 0.7f
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        pressFeedback()
        setOnClickListener {
            if (isSelected) return@setOnClickListener
            selectionTick(this)
            onPick()
        }
    }

    private fun chipParams() = LinearLayout.LayoutParams(dp(84f), dp(96f)).apply { marginEnd = dp(8f) }

    private fun fontChanged() {
        setupFonts()
        settingsChanged()
        ClockService.sync(this)
    }

    // --- Reloj al bloquear el teléfono ---

    private fun setupLock() {
        val switch = findViewById<Switch>(R.id.lock_switch)
        switch.isChecked = prefs.lockClock
        switch.setOnCheckedChangeListener { view, on ->
            selectionTick(view)
            prefs.lockClock = on
            if (on && Build.VERSION.SDK_INT >= 33 && !LockClock.notificationsAllowed(this)) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ClockService.sync(this)
            renderLockState()
        }
        findViewById<View>(R.id.lock_row).setOnClickListener { switch.toggle() }
        segmented(
            R.id.seg_lock_mode,
            listOf(R.string.lock_mode_always, R.string.lock_mode_charging, R.string.lock_mode_normal),
            prefs.lockMode.ordinal,
        ) { prefs.lockMode = LockMode.entries[it] }
        val saver = findViewById<Switch>(R.id.lock_saver_switch)
        saver.isChecked = prefs.lockBatterySaver
        saver.setOnCheckedChangeListener { view, on ->
            selectionTick(view)
            prefs.lockBatterySaver = on
        }
        findViewById<View>(R.id.lock_saver_row).setOnClickListener { saver.toggle() }
        findViewById<View>(R.id.lock_permission).setOnClickListener { openLockPermission() }
    }

    private fun renderLockState() {
        val on = prefs.lockClock
        val modeVisibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.lock_mode_sep).visibility = modeVisibility
        findViewById<View>(R.id.seg_lock_mode).visibility = modeVisibility
        findViewById<View>(R.id.lock_saver_sep).visibility = modeVisibility
        findViewById<View>(R.id.lock_saver_row).visibility = modeVisibility
        val notifications = LockClock.notificationsAllowed(this)
        val missing = on && (!notifications || !LockClock.fullScreenAllowed(this))
        val permissionVisibility = if (missing) View.VISIBLE else View.GONE
        findViewById<View>(R.id.lock_permission_sep).visibility = permissionVisibility
        findViewById<TextView>(R.id.lock_permission).apply {
            visibility = permissionVisibility
            setText(if (notifications) R.string.lock_permission else R.string.lock_notifications)
        }
    }

    /** Lleva al permiso que falta: notificaciones o pantalla completa (Android 14+). */
    private fun openLockPermission() {
        val app = Uri.parse("package:$packageName")
        val intent = when {
            // Primero se pide; si ya se negó, se abre la pantalla de notificaciones de la app.
            !LockClock.notificationsAllowed(this) && Build.VERSION.SDK_INT >= 33 && !askedNotifications -> {
                askedNotifications = true
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            !LockClock.notificationsAllowed(this) ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            Build.VERSION.SDK_INT >= 34 -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, app)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
        }
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)) } }
    }

    // --- Notificaciones en el reloj ---

    private fun setupNotifications() {
        val show = findViewById<Switch>(R.id.notif_switch)
        show.isChecked = prefs.clockNotifications
        show.setOnCheckedChangeListener { view, on ->
            selectionTick(view)
            prefs.clockNotifications = on
            if (on && !NotificationsHub.accessGranted(this)) showNotificationGuide()
            renderNotificationState()
        }
        findViewById<View>(R.id.notif_row).setOnClickListener { show.toggle() }
        val hide = findViewById<Switch>(R.id.notif_hide_switch)
        hide.isChecked = prefs.hideNotificationText
        hide.setOnCheckedChangeListener { view, on ->
            selectionTick(view)
            prefs.hideNotificationText = on
        }
        findViewById<View>(R.id.notif_hide_row).setOnClickListener { hide.toggle() }
        findViewById<View>(R.id.notif_permission).setOnClickListener { showNotificationGuide() }
        findViewById<View>(R.id.notif_app_info).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    private fun renderNotificationState() {
        val missing = prefs.clockNotifications && !NotificationsHub.accessGranted(this)
        val visibility = if (missing) View.VISIBLE else View.GONE
        listOf(R.id.notif_permission, R.id.notif_permission_sep, R.id.notif_app_info, R.id.notif_app_info_sep)
            .forEach { findViewById<View>(it).visibility = visibility }
    }

    /**
     * Android 13+ bloquea el acceso a notificaciones de las apps instaladas desde un APK ("ajustes
     * restringidos"): se explica cómo habilitarlo antes de abrir Ajustes.
     */
    private fun showNotificationGuide() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openNotificationAccess()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.notif_guide_title)
            .setMessage(R.string.notif_guide_text)
            .setPositiveButton(R.string.notif_guide_grant) { _, _ -> openNotificationAccess() }
            .setNeutralButton(R.string.notif_guide_app_info) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Pantalla de Ajustes para dar acceso a las notificaciones (directo a esta app si se puede). */
    private fun openNotificationAccess() {
        val component = ComponentName(this, NotificationsService::class.java).flattenToString()
        val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        } else {
            null
        }
        val general = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val started = direct?.let { runCatching { startActivity(it) }.isSuccess } ?: false
        if (!started) runCatching { startActivity(general) }
    }

    // --- Modo reloj y protector de pantalla ---

    private fun setupClockMode() {
        findViewById<View>(R.id.open_clock).setOnClickListener {
            startActivity(Intent(this, ClockActivity::class.java))
        }
        segmented(R.id.seg_saver_bg, listOf(R.string.saver_black, R.string.saver_glass), prefs.saverBackground.ordinal) {
            prefs.saverBackground = SaverBackground.entries[it]
        }
        val dim = findViewById<Switch>(R.id.dim_switch)
        dim.isChecked = prefs.saverDim
        dim.setOnCheckedChangeListener { view, checked ->
            selectionTick(view)
            prefs.saverDim = checked
        }
        findViewById<View>(R.id.dim_row).setOnClickListener { dim.toggle() }

        val dream = dreamSettingsIntent()
        val dreamRow = findViewById<View>(R.id.dream_settings)
        if (dream == null) {
            dreamRow.visibility = View.GONE
            findViewById<View>(R.id.dream_sep).visibility = View.GONE
        } else {
            dreamRow.setOnClickListener {
                runCatching { startActivity(dream) }.onFailure { toast(R.string.dream_unavailable) }
            }
        }
    }

    /** Ajustes del protector de pantalla. En ColorOS no aparece en el menú, pero la pantalla existe. */
    private fun dreamSettingsIntent(): Intent? = listOf(
        Intent(Settings.ACTION_DREAM_SETTINGS),
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DreamSettingsActivity"),
    ).firstOrNull { it.resolveActivity(packageManager) != null }

    // --- Microinteracciones (guía de iOS: respuesta inmediata al tocar) ---

    /** Al tocar, se achica y atenúa un poco (~100 ms) y vuelve al soltar. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pressFeedback() = setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.85f).setDuration(100).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
        }
        false
    }

    /** Vibración leve de selección, como el "selection feedback" de iOS. */
    private fun selectionTick(view: View) {
        val effect = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        view.performHapticFeedback(effect)
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
                    if (isSelected) return@setOnClickListener
                    group.children.forEach { it.isSelected = false }
                    isSelected = true
                    selectionTick(this)
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
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val WALLPAPER_COUNT = 3

        /** Medidas en dp de cada tamaño en un inicio de 5 columnas como el tuyo. */
        val PREVIEW_SIZES = listOf(
            R.string.size_2x2 to (144f to 181f),
            R.string.size_3x2 to (222f to 181f),
            R.string.size_3x3 to (222f to 273f),
            R.string.size_5x2 to (378f to 181f),
            R.string.size_5x3 to (378f to 273f),
        )
    }
}
