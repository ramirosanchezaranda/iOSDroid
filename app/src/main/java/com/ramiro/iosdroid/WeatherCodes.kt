package com.ramiro.iosdroid

/** Códigos WMO que devuelve Open-Meteo → ícono y texto. */
object WeatherCodes {

    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0 -> if (isDay) R.drawable.ic_wx_sun else R.drawable.ic_wx_moon
        1, 2 -> if (isDay) R.drawable.ic_wx_partly_day else R.drawable.ic_wx_partly_night
        3 -> R.drawable.ic_wx_cloud
        45, 48 -> R.drawable.ic_wx_fog
        51, 53, 55, 56, 57 -> R.drawable.ic_wx_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_wx_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_wx_snow
        95, 96, 99 -> R.drawable.ic_wx_thunder
        else -> R.drawable.ic_wx_cloud
    }

    fun label(code: Int): Int = when (code) {
        0 -> R.string.wx_clear
        1 -> R.string.wx_mainly_clear
        2 -> R.string.wx_partly
        3 -> R.string.wx_overcast
        45, 48 -> R.string.wx_fog
        51, 53, 55 -> R.string.wx_drizzle
        56, 57 -> R.string.wx_freezing_drizzle
        61 -> R.string.wx_rain_light
        63 -> R.string.wx_rain
        65 -> R.string.wx_rain_heavy
        66, 67 -> R.string.wx_freezing_rain
        71, 73, 75 -> R.string.wx_snow
        77 -> R.string.wx_snow_grains
        80, 81 -> R.string.wx_showers
        82 -> R.string.wx_showers_heavy
        85, 86 -> R.string.wx_snow_showers
        95 -> R.string.wx_thunder
        96, 99 -> R.string.wx_thunder_hail
        else -> R.string.wx_overcast
    }
}
