// Tests for Home's weather: the Open-Meteo address, reading a reply, the kinds of sky, and the words on Today
package io.github.adenkushnir.datebook.weather
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks the weather on Tue Nov 10 2037 in New York
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherTest {
    private val newYork = ZoneId.of("America/New_York")

    // Seconds since 1970 for a time in New York
    private fun seconds(hour: Int): Long {
        return LocalDateTime.of(2037, 11, 10, hour, 0).atZone(newYork).toEpochSecond()
    }

    // A small reply like Open-Meteo's: the weather now, three hours (dry at 2 PM, rain from 4 PM) and one day
    private fun reply(): String {
        val midnight = seconds(0)
        return """{"utc_offset_seconds": -18000,
            "current": {"time": ${seconds(14)}, "temperature_2m": 49.4, "weather_code": 3, "is_day": 1},
            "hourly": {"time": [${seconds(14)}, ${seconds(16)}, ${seconds(19)}],
              "temperature_2m": [49.4, 48.0, 46.6], "precipitation_probability": [10, 60, 70],
              "weather_code": [3, 61, 63], "is_day": [1, 1, 0]},
            "daily": {"time": [$midnight], "temperature_2m_max": [52.2], "temperature_2m_min": [40.6],
              "precipitation_probability_max": [70]}}"""
    }

    // The address rounds the place to about a kilometre and asks for Fahrenheit only in the US
    @Test
    fun address() {
        val url = forecastUrl(40.49663, -74.44552, fahrenheit = true)
        assertTrue(url.contains("latitude=40.50&longitude=-74.45"))
        assertTrue(url.contains("temperature_unit=fahrenheit"))
        assertFalse(forecastUrl(51.5, -0.12, fahrenheit = false).contains("temperature_unit"))
        assertTrue(usesFahrenheit(Locale.US))
        assertFalse(usesFahrenheit(Locale.UK))
    }

    // A reply reads into the weather now, each hour and each day; a broken one gives nothing
    @Test
    fun reading() {
        val forecast = parseForecast(reply())!!
        assertEquals(49, forecast.now!!.temp)
        assertEquals(listOf(10, 60, 70), forecast.hours.map { it.rain })
        assertEquals(LocalDate.of(2037, 11, 10), forecast.days.single().date)
        assertEquals(52, forecast.days.single().high)
        assertEquals(41, forecast.days.single().low)
        assertNull(parseForecast("not json"))
    }

    // Today's line says when rain starts, the hour of an event shows its rain chance once it's likely enough, and
    // weather codes become kinds of sky
    @Test
    fun words() {
        val forecast = parseForecast(reply())!!
        val two = seconds(14) * 1000
        assertEquals("High 52° · Low 41° · Rain 70% after 4 PM", dayText(forecast, two, newYork))
        assertEquals("High 52° · Low 41° · Rain 70% now", dayText(forecast, seconds(16) * 1000 + 60_000, newYork))
        assertEquals("49°", hourText(hourAt(forecast, two + 30 * 60_000)!!))
        assertEquals("47° · 70%", hourText(hourAt(forecast, seconds(19) * 1000)!!))
        assertNull(hourAt(forecast, seconds(23) * 1000))
        assertEquals(SKY_CLEAR, skyOf(0, daytime = true))
        assertEquals(SKY_NIGHT, skyOf(1, daytime = false))
        assertEquals(SKY_RAIN, skyOf(63, daytime = true))
        assertEquals(SKY_SNOW, skyOf(73, daytime = true))
        assertEquals(SKY_CLOUDY, skyOf(45, daytime = true))
    }
}
