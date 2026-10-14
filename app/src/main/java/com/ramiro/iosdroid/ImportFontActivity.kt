package com.ramiro.iosdroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recibe fuentes desde otras apps: "Abrir con" en el administrador de archivos o "Compartir" desde
 * WhatsApp, Telegram, Drive... Las carga en la biblioteca y abre los ajustes. No tiene pantalla.
 */
class ImportFontActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { CustomFont.importAll(applicationContext, uris) }
            if (summary.loaded.isNotEmpty()) {
                WidgetRenderer.updateAll(applicationContext)
                ClockService.sync(applicationContext)
            }
            Toast.makeText(applicationContext, message(applicationContext, summary), Toast.LENGTH_LONG).show()
            if (summary.loaded.isNotEmpty()) {
                startActivity(Intent(this@ImportFontActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            finish()
        }
    }

    companion object {
        fun message(context: Context, summary: CustomFont.ImportSummary): String {
            val loaded = summary.loaded
            val base = when {
                loaded.isEmpty() -> context.getString(R.string.font_invalid)
                loaded.size == 1 -> context.getString(R.string.font_loaded, loaded.first().fullName)
                else -> context.resources.getQuantityString(R.plurals.fonts_loaded, loaded.size, loaded.size)
            }
            return if (loaded.isNotEmpty() && summary.invalid > 0) {
                base + " · " + context.resources.getQuantityString(R.plurals.fonts_invalid, summary.invalid, summary.invalid)
            } else {
                base
            }
        }
    }
}
