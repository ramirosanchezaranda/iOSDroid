package com.ramiro.iosdroid

import android.content.Context

enum class GlassStyle(val key: String) {
    CLEAR("clear"), FROSTED("frosted"), SMOKED("smoked");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: CLEAR
    }
}

enum class TextTone(val key: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

enum class TempUnit(val key: String, val apiName: String) {
    CELSIUS("c", "celsius"), FAHRENHEIT("f", "fahrenheit");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: CELSIUS
    }
}

/** Lugar del que se muestra el clima. [auto] = viene del GPS/red, no de una búsqueda. */
data class Place(val name: String, val lat: Double, val lon: Double, val auto: Boolean)

data class Weather(
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val max: Double,
    val min: Double,
    val unit: TempUnit,
    val updatedAt: Long,
)

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("glass_widget", Context.MODE_PRIVATE)

    var style: GlassStyle
        get() = GlassStyle.from(sp.getString("style", null))
        set(v) = sp.edit().putString("style", v.key).apply()

    var textTone: TextTone
        get() = TextTone.from(sp.getString("text_tone", null))
        set(v) = sp.edit().putString("text_tone", v.key).apply()

    var unit: TempUnit
        get() = TempUnit.from(sp.getString("unit", null))
        set(v) = sp.edit().putString("unit", v.key).apply()

    var place: Place?
        get() {
            val lat = sp.getString("lat", null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString("lon", null)?.toDoubleOrNull() ?: return null
            return Place(sp.getString("city", "") ?: "", lat, lon, sp.getBoolean("auto", true))
        }
        set(v) {
            sp.edit().apply {
                if (v == null) {
                    remove("lat"); remove("lon"); remove("city"); remove("auto")
                } else {
                    putString("lat", v.lat.toString())
                    putString("lon", v.lon.toString())
                    putString("city", v.name)
                    putBoolean("auto", v.auto)
                }
            }.apply()
        }

    var weather: Weather?
        get() {
            if (!sp.contains("wx_updated")) return null
            return Weather(
                temp = sp.getString("wx_temp", null)?.toDoubleOrNull() ?: return null,
                code = sp.getInt("wx_code", 0),
                isDay = sp.getBoolean("wx_day", true),
                max = sp.getString("wx_max", null)?.toDoubleOrNull() ?: return null,
                min = sp.getString("wx_min", null)?.toDoubleOrNull() ?: return null,
                unit = TempUnit.from(sp.getString("wx_unit", null)),
                updatedAt = sp.getLong("wx_updated", 0L),
            )
        }
        set(v) {
            sp.edit().apply {
                if (v == null) {
                    listOf("wx_temp", "wx_code", "wx_day", "wx_max", "wx_min", "wx_unit", "wx_updated").forEach(::remove)
                } else {
                    putString("wx_temp", v.temp.toString())
                    putInt("wx_code", v.code)
                    putBoolean("wx_day", v.isDay)
                    putString("wx_max", v.max.toString())
                    putString("wx_min", v.min.toString())
                    putString("wx_unit", v.unit.key)
                    putLong("wx_updated", v.updatedAt)
                }
            }.apply()
        }

    /** Última vez que se pidió una actualización inmediata (para no encolar trabajo en bucle). */
    var lastRefreshRequest: Long
        get() = sp.getLong("last_refresh_request", 0L)
        set(v) = sp.edit().putLong("last_refresh_request", v).apply()
}
