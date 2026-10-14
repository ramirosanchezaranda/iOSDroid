package com.ramiro.iosdroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Fuente propia cargada por el usuario (.ttf / .otf, por ejemplo SF Pro).
 *
 * Se copia dentro de la app para no depender del archivo original. En la app, el modo reloj y la
 * pantalla bloqueada se usa directo; en el widget se dibuja la hora como imagen
 * ([timeBitmap]) porque el launcher no puede cargar fuentes de otras apps.
 */
object CustomFont {

    private const val MAX_BYTES = 40L * 1024 * 1024

    /** Pesos para fuentes variables (eje 'wght'); en fuentes de un solo peso no cambian nada. */
    val WEIGHTS = intArrayOf(200, 300, 400, 700)

    @Volatile
    private var cache: Pair<String, Typeface>? = null

    fun file(context: Context) = File(File(context.filesDir, "fonts"), "custom_font")

    fun exists(context: Context) = file(context).isFile

    /** La fuente propia si está cargada y elegida; si no, null. */
    fun active(context: Context): Typeface? {
        val prefs = Prefs(context)
        return if (prefs.useCustomFont) typeface(context, prefs.customFontWeight) else null
    }

    fun typeface(context: Context, weight: Int): Typeface? {
        val f = file(context)
        if (!f.isFile) return null
        val key = "${f.lastModified()}-${f.length()}-$weight"
        cache?.takeIf { it.first == key }?.let { return it.second }
        val tf = runCatching { Typeface.Builder(f).setFontVariationSettings("'wght' $weight").build() }.getOrNull()
            ?: return null
        cache = key to tf
        return tf
    }

    sealed interface ImportResult {
        data class Ok(val name: String) : ImportResult
        data object Invalid : ImportResult
    }

    /** Copia el archivo elegido. Bloqueante: llamar fuera del hilo principal. */
    fun import(context: Context, uri: Uri): ImportResult {
        val tmp = File(context.cacheDir, "font_import")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
        }.getOrNull()
        if (copied == null || tmp.length() !in 12..MAX_BYTES || !looksLikeFont(tmp) || !loads(tmp)) {
            tmp.delete()
            return ImportResult.Invalid
        }
        val target = file(context)
        target.parentFile?.mkdirs()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        cache = null
        return ImportResult.Ok(displayName(context, uri) ?: "Fuente propia")
    }

    fun remove(context: Context) {
        file(context).delete()
        cache = null
    }

    /** Cabecera de TrueType, OpenType o colección de fuentes. */
    fun looksLikeFont(f: File): Boolean {
        val head = ByteArray(4)
        val read = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (read < 4) return false
        val tag = String(head, Charsets.ISO_8859_1)
        return tag == "OTTO" || tag == "true" || tag == "ttcf" ||
            (head[0] == 0.toByte() && head[1] == 1.toByte() && head[2] == 0.toByte() && head[3] == 0.toByte())
    }

    private fun loads(f: File) = runCatching { Typeface.Builder(f).build() }.getOrNull() != null

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.')?.ifBlank { null }

    /**
     * La hora dibujada con la fuente propia, para el widget. El ancho se calcula con la hora más
     * ancha posible, así el tamaño no cambia de un minuto a otro.
     */
    fun timeBitmap(text: String, typeface: Typeface, color: Int, shadow: Boolean, heightPx: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            textSize = heightPx.toFloat()
            this.color = color
            letterSpacing = -0.01f
            fontFeatureSettings = "tnum"
            if (shadow) setShadowLayer(heightPx * 0.05f, 0f, heightPx * 0.01f, 0x40000000)
        }
        val digits = Rect().also { paint.getTextBounds("0123456789", 0, 10, it) }
        val pad = (digits.height() * 0.1f).toInt() + 2
        val widest = listOf("00:00", "88:88", "22:22", "44:44", "12:59", text).maxOf { paint.measureText(it) }
        val bmp = Bitmap.createBitmap(widest.toInt() + 2 * pad, digits.height() + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, pad.toFloat(), (pad - digits.top).toFloat(), paint)
        return bmp
    }
}
