package com.ramiro.iosdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenMeteoParseTest {

    /** Respuesta real de api.open-meteo.com para Buenos Aires (13/10/2026). */
    private val forecast = """
        {"latitude":-34.622143,"longitude":-58.40909,"generationtime_ms":187.97,"utc_offset_seconds":-10800,
         "timezone":"America/Argentina/Buenos_Aires","timezone_abbreviation":"GMT-3","elevation":37.0,
         "current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
         "current":{"time":"2026-10-13T17:00","interval":900,"temperature_2m":16.9,"weather_code":3,"is_day":1},
         "daily_units":{"time":"iso8601","temperature_2m_max":"°C","temperature_2m_min":"°C"},
         "daily":{"time":["2026-10-13"],"temperature_2m_max":[17.9],"temperature_2m_min":[13.1]}}
    """.trimIndent()

    @Test
    fun parsesForecast() {
        val w = OpenMeteo.parseForecast(forecast, TempUnit.CELSIUS, now = 42L)
        assertEquals(16.9, w.temp, 0.001)
        assertEquals(3, w.code)
        assertTrue(w.isDay)
        assertEquals(17.9, w.max, 0.001)
        assertEquals(13.1, w.min, 0.001)
        assertEquals(42L, w.updatedAt)
        assertEquals("17°", WidgetRenderer.deg(w.temp))
        assertEquals(R.drawable.ic_wx_cloud, WeatherCodes.icon(w.code, w.isDay))
    }

    /** Respuesta real con pronóstico de 6 días (13/10/2026, noche en Buenos Aires). */
    private val sixDays = """
        {"latitude":-34.622143,"longitude":-58.40909,"utc_offset_seconds":-10800,"timezone":"America/Argentina/Buenos_Aires",
         "current":{"time":"2026-10-13T20:30","interval":900,"temperature_2m":14.6,"weather_code":3,"is_day":0},
         "daily":{"time":["2026-10-13","2026-10-14","2026-10-15","2026-10-16","2026-10-17","2026-10-18"],
                  "weather_code":[53,51,53,51,3,3],
                  "temperature_2m_max":[18.4,19.3,18.6,16.8,15.3,15.1],
                  "temperature_2m_min":[13.2,12.5,13.2,12.2,6.9,9.2],
                  "precipitation_probability_max":[100,39,87,52,18,10]}}
    """.trimIndent()

    @Test
    fun parsesSixDayForecast() {
        val w = OpenMeteo.parseForecast(sixDays, TempUnit.CELSIUS)
        assertEquals(6, w.days.size)
        assertEquals(DayForecast("2026-10-14", 51, 19.3, 12.5, 39), w.days[1])
        assertEquals(100, w.days[0].precip)
        assertTrue(!w.isDay)
        assertEquals(R.drawable.ic_wx_drizzle, WeatherCodes.icon(w.days[2].code, true))
        // Desde el 14/10, "hoy" ya no aparece.
        val upcoming = Forecast.upcoming(w.days, java.time.LocalDate.of(2026, 10, 14))
        assertEquals("2026-10-14", upcoming.first().date)
        assertEquals(5, upcoming.size)
    }

    @Test
    fun skipsDaysWithoutData() {
        val body = sixDays.replace("[18.4,19.3,", "[18.4,null,").replace("[100,39,", "[100,null,")
        val w = OpenMeteo.parseForecast(body, TempUnit.CELSIUS)
        assertEquals(5, w.days.size)
        assertEquals("2026-10-15", w.days[1].date)
    }

    @Test
    fun parsesCities() {
        val body = """
            {"results":[
              {"id":3860259,"name":"Córdoba","latitude":-31.4135,"longitude":-64.18105,"country":"Argentina","admin1":"Córdoba"},
              {"id":2519240,"name":"Córdoba","latitude":37.89155,"longitude":-4.77275,"country":"España","admin1":"Andalucía"}
            ],"generationtime_ms":0.5}
        """.trimIndent()
        val list = OpenMeteo.parseCities(body)
        assertEquals(2, list.size)
        assertEquals("Córdoba", list[0].place.name)
        assertEquals("Argentina", list[0].detail)
        assertEquals("Andalucía, España", list[1].detail)
        assertEquals(-31.4135, list[0].place.lat, 1e-6)
        assertEquals(false, list[0].place.auto)
    }

    @Test
    fun emptySearchHasNoResults() {
        assertEquals(0, OpenMeteo.parseCities("""{"generationtime_ms":0.3}""").size)
    }
}
