package com.ramiro.iosdroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Cliente mínimo de Open-Meteo (gratis, sin API key). */
object OpenMeteo {

    suspend fun forecast(lat: Double, lon: Double, unit: TempUnit): Weather = withContext(Dispatchers.IO) {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=6&temperature_unit=%s",
            lat, lon, unit.apiName,
        )
        parseForecast(get(url), unit)
    }

    fun parseForecast(body: String, unit: TempUnit, now: Long = System.currentTimeMillis()): Weather {
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.optJSONArray("weather_code")
        val maxes = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        val days = (0 until dates.length()).mapNotNull { i ->
            // Open-Meteo devuelve null en los días que todavía no tienen datos.
            if (maxes.isNull(i) || mins.isNull(i)) return@mapNotNull null
            DayForecast(
                date = dates.getString(i),
                code = codes?.optInt(i, 3) ?: 3,
                max = maxes.getDouble(i),
                min = mins.getDouble(i),
                precip = rain?.takeUnless { it.isNull(i) }?.optInt(i) ?: 0,
            )
        }
        return Weather(
            temp = current.getDouble("temperature_2m"),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            max = daily.getJSONArray("temperature_2m_max").getDouble(0),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0),
            unit = unit,
            updatedAt = now,
            days = days,
        )
    }

    data class CityResult(val place: Place, val detail: String)

    suspend fun searchCity(query: String): List<CityResult> = withContext(Dispatchers.IO) {
        val lang = Locale.getDefault().language.ifEmpty { "es" }
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
            "&language=$lang&name=" + URLEncoder.encode(query.trim(), "UTF-8")
        parseCities(get(url))
    }

    fun parseCities(body: String): List<CityResult> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val name = r.getString("name")
            val detail = listOf(r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() && it != name }
                .joinToString(", ")
            CityResult(Place(name, r.getDouble("latitude"), r.getDouble("longitude"), auto = false), detail)
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "iOSDroid-GlassWidget/1.0")
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
