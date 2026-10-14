package com.ramiro.iosdroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Una fuente cargada por el usuario. */
data class LoadedFont(
    val id: String,
    /** Familia según el archivo, p. ej. "SF Pro Display". */
    val family: String,
    /** Estilo según el archivo, p. ej. "Bold" o "Light Italic". */
    val style: String,
    /** Fuente variable (eje de peso): se puede elegir el peso. */
    val variable: Boolean,
) {
    val fullName get() = listOf(family, style).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * Fuentes propias del usuario (.ttf / .otf, por ejemplo SF Pro): se pueden cargar varias y elegir
 * una para la hora (y la fecha).
 *
 * Se copian dentro de la app para no depender del archivo original. En la app, el modo reloj y la
 * pantalla bloqueada se usan directo; en el widget la hora y la fecha se dibujan como imagen
 * ([timeBitmap], [lineBitmap]) porque el launcher no puede cargar fuentes de otras apps.
 */
object CustomFont {

    private const val MAX_BYTES = 40L * 1024 * 1024

    /** Pesos para fuentes variables (eje 'wght'). */
    val WEIGHTS = intArrayOf(200, 300, 400, 700)

    /** Peso de la fecha con fuentes variables (medio, como la jerarquía de iOS). */
    const val DATE_WEIGHT = 500

    // Hora y fecha pueden usar pesos distintos: se guardan varias.
    private val cache = object : android.util.LruCache<String, Typeface>(6) {}

    private fun dir(context: Context) = File(context.filesDir, "fonts")

    private fun fileOf(context: Context, id: String) = File(dir(context), "$id.font")

    // --- Biblioteca ---

    fun list(context: Context): List<LoadedFont> {
        migrateSingleFont(context)
        val json = Prefs(context).fontLibrary
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                LoadedFont(o.getString("id"), o.optString("family"), o.optString("style"), o.optBoolean("variable"))
            }
        }.getOrDefault(emptyList()).filter { fileOf(context, it.id).isFile }
    }

    private fun save(context: Context, fonts: List<LoadedFont>) {
        Prefs(context).fontLibrary = JSONArray().apply {
            fonts.forEach {
                put(JSONObject().put("id", it.id).put("family", it.family).put("style", it.style).put("variable", it.variable))
            }
        }.toString()
    }

    /** La fuente propia elegida, o null si se usa una fuente del sistema. */
    fun selected(context: Context): LoadedFont? {
        val id = Prefs(context).selectedFontId.ifBlank { return null }
        return list(context).firstOrNull { it.id == id }
    }

    fun select(context: Context, font: LoadedFont?) {
        Prefs(context).selectedFontId = font?.id.orEmpty()
    }

    fun remove(context: Context, font: LoadedFont) {
        fileOf(context, font.id).delete()
        save(context, list(context).filter { it.id != font.id })
        if (Prefs(context).selectedFontId == font.id) select(context, null)
        cache.evictAll()
    }

    fun typeface(context: Context, font: LoadedFont, weight: Int?): Typeface? {
        val f = fileOf(context, font.id)
        if (!f.isFile) return null
        val w = if (font.variable) weight else null
        val key = "${font.id}-$w"
        cache.get(key)?.let { return it }
        val builder = Typeface.Builder(f)
        if (w != null) builder.setFontVariationSettings("'wght' $w")
        val tf = runCatching { builder.build() }.getOrNull() ?: return null
        cache.put(key, tf)
        return tf
    }

    /** La fuente propia elegida para la hora, si hay una. */
    fun active(context: Context): Typeface? =
        selected(context)?.let { typeface(context, it, Prefs(context).customFontWeight) }

    /** La fuente propia para la fecha, si está elegida y se usa también en la fecha. */
    fun activeForDate(context: Context): Typeface? {
        if (!Prefs(context).fontForDate) return null
        return selected(context)?.let { typeface(context, it, DATE_WEIGHT) }
    }

    // --- Importar ---

    sealed interface ImportResult {
        data class Ok(val font: LoadedFont) : ImportResult
        data object Invalid : ImportResult
    }

    /** Copia el archivo elegido y lo agrega a la biblioteca. Bloqueante: llamar fuera del hilo principal. */
    fun import(context: Context, uri: Uri): ImportResult {
        val tmp = File(context.cacheDir, "font_import_${UUID.randomUUID()}")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
        }.getOrNull()
        if (copied == null || tmp.length() !in 12..MAX_BYTES || !looksLikeFont(tmp) || !loads(tmp)) {
            tmp.delete()
            return ImportResult.Invalid
        }
        val names = readNames(tmp)
        val fallback = displayName(context, uri) ?: "Fuente propia"
        val family = names.first.ifBlank { fallback }
        val style = names.second
        val fonts = list(context)
        // Si ya estaba (misma familia y estilo), se reemplaza.
        val previous = fonts.firstOrNull { it.family == family && it.style == style }
        val font = LoadedFont(previous?.id ?: UUID.randomUUID().toString(), family, style, isVariable(tmp))
        val target = fileOf(context, font.id)
        target.parentFile?.mkdirs()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        save(context, fonts.filter { it.id != font.id } + font)
        cache.evictAll()
        return ImportResult.Ok(font)
    }

    class ImportSummary(val loaded: List<LoadedFont>, val invalid: Int)

    /**
     * Importa varios archivos y, si todavía no hay una fuente propia elegida, elige la más
     * "normal" de las cargadas (Regular, Medium...). Bloqueante.
     */
    fun importAll(context: Context, uris: List<Uri>): ImportSummary {
        val results = uris.map { import(context, it) }
        val loaded = results.filterIsInstance<ImportResult.Ok>().map { it.font }
        if (loaded.isNotEmpty()) select(context, preferred(loaded))
        return ImportSummary(loaded, results.count { it is ImportResult.Invalid })
    }

    /** Entre varios estilos, el más adecuado para la hora: Regular, después Medium, Light... */
    fun preferred(fonts: List<LoadedFont>): LoadedFont {
        val order = listOf("regular", "medium", "light", "semibold", "bold")
        return order.firstNotNullOfOrNull { wanted -> fonts.firstOrNull { it.style.equals(wanted, ignoreCase = true) } }
            ?: fonts.first()
    }

    /** Las versiones anteriores guardaban una sola fuente en fonts/custom_font. */
    private fun migrateSingleFont(context: Context) {
        val old = File(dir(context), "custom_font")
        if (!old.isFile) return
        val prefs = Prefs(context)
        val names = readNames(old)
        val font = LoadedFont(
            UUID.randomUUID().toString(),
            names.first.ifBlank { prefs.customFontName.ifBlank { "Fuente propia" } },
            names.second,
            isVariable(old),
        )
        if (!old.renameTo(fileOf(context, font.id))) return
        val existing = runCatching { JSONArray(prefs.fontLibrary) }.getOrDefault(JSONArray())
        existing.put(JSONObject().put("id", font.id).put("family", font.family).put("style", font.style).put("variable", font.variable))
        prefs.fontLibrary = existing.toString()
        if (prefs.legacyUseCustomFont) prefs.selectedFontId = font.id
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

    /** Tablas de la fuente (etiqueta → posición y largo). Para colecciones (.ttc) usa la primera. */
    private fun tables(raf: RandomAccessFile): Map<String, Pair<Long, Long>> {
        raf.seek(0)
        var base = 0L
        val tag = ByteArray(4).also { raf.readFully(it) }
        if (String(tag, Charsets.ISO_8859_1) == "ttcf") {
            raf.seek(12)
            base = raf.readInt().toLong() and 0xFFFFFFFF
        }
        raf.seek(base + 4)
        val count = raf.readUnsignedShort()
        val out = HashMap<String, Pair<Long, Long>>()
        repeat(count) { i ->
            raf.seek(base + 12 + 16L * i)
            val t = ByteArray(4).also { raf.readFully(it) }
            raf.readInt() // checksum
            val offset = raf.readInt().toLong() and 0xFFFFFFFF
            val length = raf.readInt().toLong() and 0xFFFFFFFF
            out[String(t, Charsets.ISO_8859_1)] = offset to length
        }
        return out
    }

    /** Las fuentes variables tienen tabla 'fvar'. */
    fun isVariable(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { "fvar" in tables(it) }
    }.getOrDefault(false)

    /** Familia y estilo desde la tabla 'name' (IDs 16/17 o 1/2). */
    fun readNames(f: File): Pair<String, String> = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val (offset, _) = tables(raf)["name"] ?: return@use "" to ""
            raf.seek(offset)
            raf.readUnsignedShort() // formato
            val count = raf.readUnsignedShort()
            val strings = offset + raf.readUnsignedShort()
            val found = HashMap<Int, String>()
            repeat(count) { i ->
                raf.seek(offset + 6 + 12L * i)
                val platform = raf.readUnsignedShort()
                raf.readUnsignedShort() // codificación
                val language = raf.readUnsignedShort()
                val nameId = raf.readUnsignedShort()
                val length = raf.readUnsignedShort()
                val start = raf.readUnsignedShort()
                val wanted = nameId in intArrayOf(1, 2, 16, 17)
                // Windows en inglés (UTF-16) primero; si no, Mac Roman.
                val windows = platform == 3 && (language == 0x409 || nameId !in found)
                val mac = platform == 1 && nameId !in found
                if (wanted && (windows || mac) && length in 1..512) {
                    raf.seek(strings + start)
                    val bytes = ByteArray(length).also { raf.readFully(it) }
                    found[nameId] = if (platform == 3) String(bytes, Charsets.UTF_16BE) else String(bytes, Charsets.ISO_8859_1)
                }
            }
            (found[16] ?: found[1] ?: "").trim() to (found[17] ?: found[2] ?: "").trim()
        }
    }.getOrDefault("" to "")

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.')?.ifBlank { null }

    // --- Dibujo para el widget ---

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

    /**
     * Una línea de texto (la fecha) dibujada con la fuente propia, a la altura de una línea de
     * [heightPx]; el widget la muestra alineada a la izquierda.
     */
    fun lineBitmap(text: String, typeface: Typeface, color: Int, shadow: Boolean, heightPx: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            this.color = color
            textSize = heightPx / 1.35f
            if (shadow) setShadowLayer(heightPx * 0.12f, 0f, heightPx * 0.03f, 0x40000000)
        }
        val fm = paint.fontMetrics
        // Sin margen a la izquierda (alineada con el resto del texto); a la derecha, lugar para la sombra.
        val width = paint.measureText(text).toInt() + (heightPx * 0.15f).toInt() + 2
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // Centrado vertical según la caja real de la fuente (ascendentes y descendentes).
        val baseline = (heightPx - (fm.descent - fm.ascent)) / 2f - fm.ascent
        Canvas(bmp).drawText(text, 1f, baseline, paint)
        return bmp
    }
}
