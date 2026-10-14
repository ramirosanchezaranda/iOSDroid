package com.ramiro.iosdroid

import android.graphics.Typeface
import android.os.Build

/**
 * Fuentes para la hora, como el selector de la pantalla de bloqueo de iOS.
 *
 * Un widget solo puede usar fuentes del sistema (el launcher no carga fuentes de otras apps),
 * así que son familias del sistema con distintos pesos. "sans-serif" es la fuente que elegiste
 * en el teléfono: OPPO Sans en ColorOS.
 */
enum class ClockFont(
    val key: String,
    val label: Int,
    val family: String,
    val weight: Int,
    val letterSpacing: Float,
    /** TextClock del widget que usa esta fuente (ver layout/widget_time.xml). */
    val timeId: Int,
) {
    THIN("thin", R.string.font_thin, "sans-serif", 200, -0.02f, R.id.time_thin),
    LIGHT("light", R.string.font_light, "sans-serif", 300, -0.02f, R.id.time_light),
    REGULAR("regular", R.string.font_regular, "sans-serif", 400, -0.02f, R.id.time_regular),
    BOLD("bold", R.string.font_bold, "sans-serif", 700, -0.03f, R.id.time_bold),
    HEAVY("heavy", R.string.font_heavy, "sans-serif", 900, -0.03f, R.id.time_heavy),
    CONDENSED("condensed", R.string.font_condensed, "sans-serif-condensed", 400, -0.01f, R.id.time_condensed),
    SERIF("serif", R.string.font_serif, "serif", 400, 0f, R.id.time_serif),
    MONO("mono", R.string.font_mono, "monospace", 400, -0.04f, R.id.time_mono),
    SCRIPT("script", R.string.font_script, "cursive", 700, 0f, R.id.time_script),
    ;

    fun typeface(): Typeface {
        val base = Typeface.create(family, Typeface.NORMAL)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight, false)
        } else {
            Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

/** Fondo del modo reloj / protector de pantalla. */
enum class SaverBackground(val key: String) {
    BLACK("black"), GLASS("glass");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: BLACK
    }
}

/** Qué pasa con la pantalla cuando el reloj aparece al bloquear el teléfono. */
enum class LockMode(val key: String) {
    /** La pantalla queda encendida con el reloj. */
    ALWAYS("always"),

    /** Encendida solo mientras carga; si no, se apaga sola como siempre. */
    CHARGING("charging"),

    /** El reloj aparece al encender la pantalla, que se apaga sola. */
    NORMAL("normal");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ALWAYS
    }
}
