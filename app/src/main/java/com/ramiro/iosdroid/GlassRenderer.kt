package com.ramiro.iosdroid

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Dibuja el fondo "Liquid Glass" del widget en un bitmap.
 *
 * Un widget de Android no puede desenfocar ni refractar el fondo de pantalla que tiene detrás,
 * así que el vidrio se simula con capas: cuerpo translúcido, brillo superior, resplandor en el
 * canto (como la refracción del borde de un vidrio grueso), canto con degradé y destellos.
 */
object GlassRenderer {

    private class Spec(
        val fillStart: Int,
        val fillEnd: Int,
        val sheen: Int,
        val edgeGlow: Int,
        val shade: Int,
        val rim: IntArray,
        val innerRim: Int,
        val glint: Int,
        val grain: Int,
    )

    private val RIM_POSITIONS = floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f)

    private fun white(a: Int) = Color.argb(a, 255, 255, 255)
    private fun black(a: Int) = Color.argb(a, 0, 0, 0)

    private fun spec(style: GlassStyle) = when (style) {
        GlassStyle.CLEAR -> Spec(
            fillStart = white(40), fillEnd = white(10),
            sheen = white(42), edgeGlow = white(52), shade = black(22),
            rim = intArrayOf(white(235), white(84), white(26), white(42), white(165)),
            innerRim = black(18), glint = white(225), grain = 0,
        )
        GlassStyle.FROSTED -> Spec(
            fillStart = white(112), fillEnd = Color.argb(72, 236, 240, 255),
            sheen = white(64), edgeGlow = white(62), shade = black(16),
            rim = intArrayOf(white(245), white(115), white(52), white(72), white(195)),
            innerRim = black(12), glint = white(240), grain = 14,
        )
        GlassStyle.SMOKED -> Spec(
            fillStart = Color.argb(118, 30, 30, 36), fillEnd = Color.argb(158, 10, 10, 14),
            sheen = white(26), edgeGlow = white(30), shade = black(40),
            rim = intArrayOf(white(185), white(54), white(16), white(26), white(115)),
            innerRim = black(42), glint = white(175), grain = 0,
        )
    }

    // Con fuente propia el widget se redibuja cada minuto: el vidrio no cambia, se reutiliza.
    private val cache = object : LruCache<String, Bitmap>(6) {}

    fun render(width: Int, height: Int, radius: Float, density: Float, style: GlassStyle): Bitmap {
        val key = "$width-$height-$radius-$density-$style"
        cache.get(key)?.let { return it }
        return draw(width, height, radius, density, style).also { cache.put(key, it) }
    }

    private fun draw(width: Int, height: Int, radius: Float, density: Float, style: GlassStyle): Bitmap {
        val w = width.coerceAtLeast(8)
        val h = height.coerceAtLeast(8)
        val s = spec(style)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        val inset = 0.6f * density
        val box = RectF(inset, inset, w - inset, h - inset)
        val r = (radius - inset).coerceIn(0f, min(box.width(), box.height()) / 2f)
        val shape = Path().apply { addRoundRect(box, r, r, Path.Direction.CW) }
        val fw = w.toFloat()
        val fh = h.toFloat()

        // 1. Cuerpo translúcido.
        p.shader = LinearGradient(0f, 0f, fw * 0.6f, fh, s.fillStart, s.fillEnd, Shader.TileMode.CLAMP)
        c.drawPath(shape, p)

        c.save()
        c.clipPath(shape)

        // 2. Brillo que entra por arriba a la izquierda.
        p.shader = RadialGradient(
            fw * 0.1f, -fh * 0.2f, max(fw, fh) * 0.9f,
            intArrayOf(s.sheen, Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, fw, fh, p)

        // 3. Sombra interior abajo: da volumen.
        p.shader = LinearGradient(0f, fh * 0.5f, 0f, fh, Color.TRANSPARENT, s.shade, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)

        // 4. Resplandor del canto (la luz que se refracta en el borde del vidrio).
        p.shader = null
        p.style = Paint.Style.STROKE
        p.color = s.edgeGlow
        p.strokeWidth = 10f * density
        p.maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(shape, p)
        p.maskFilter = null

        // 5. Grano fino para el vidrio esmerilado.
        if (s.grain > 0) {
            val rnd = Random(7)
            val count = (w * h / (40 * density * density)).toInt().coerceIn(200, 20_000)
            val pts = FloatArray(count * 2) { i -> if (i % 2 == 0) rnd.nextFloat() * fw else rnd.nextFloat() * fh }
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 0.9f * density
            p.color = white(s.grain)
            c.drawPoints(pts, 0, count, p)
            p.color = black(s.grain * 2 / 3)
            c.drawPoints(pts, count, count, p)
        }
        c.restore()

        // 6. Canto: luz fuerte arriba a la izquierda, tenue en el medio, reflejo abajo a la derecha.
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = 1.1f * density
        p.shader = LinearGradient(0f, 0f, fw, fh, s.rim, RIM_POSITIONS, Shader.TileMode.CLAMP)
        c.drawPath(shape, p)
        p.shader = null

        // 7. Línea interior oscura: el grosor del vidrio.
        val gap = 1.6f * density
        val inner = RectF(box).apply { inset(gap, gap) }
        p.color = s.innerRim
        p.strokeWidth = 0.8f * density
        c.drawRoundRect(inner, (r - gap).coerceAtLeast(0f), (r - gap).coerceAtLeast(0f), p)

        // 8. Destellos especulares en dos esquinas.
        if (r > 4f * density) {
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 1.5f * density
            p.maskFilter = BlurMaskFilter(1.1f * density, BlurMaskFilter.Blur.NORMAL)
            p.color = s.glint
            c.drawArc(RectF(box.left, box.top, box.left + 2 * r, box.top + 2 * r), 196f, 58f, false, p)
            p.color = Color.argb(Color.alpha(s.glint) / 2, 255, 255, 255)
            c.drawArc(RectF(box.right - 2 * r, box.bottom - 2 * r, box.right, box.bottom), 16f, 58f, false, p)
            p.maskFilter = null
        }
        return bmp
    }
}
