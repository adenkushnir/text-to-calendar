// Weather for Home from Open-Meteo (free, no key): the forecast where the phone is (else the home address), read into
// today's high, low and rain chance and each hour's temperature, rain chance and sky; the last forecast is kept on
// the phone and refreshed at most once an hour, and only the place, rounded to about a kilometre, is sent
package io.github.adenkushnir.datebook.weather
import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.adenkushnir.datebook.places.TravelSettings
import io.github.adenkushnir.datebook.scenery.SunPlace
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
const val SKY_CLEAR = "clear"
const val SKY_NIGHT = "night"
const val SKY_CLOUDY = "cloudy"
const val SKY_RAIN = "rain"
const val SKY_SNOW = "snow"
const val RAIN_LIKELY = 50  // a rain chance from this percent up counts as "rain after 4 PM"
const val RAIN_SHOWN = 30  // a row shows its rain chance from this percent up
private const val HOUR_MILLIS = 60 * 60 * 1000L
private val MAPPER = ObjectMapper()
private val HOUR_CLOCK = DateTimeFormatter.ofPattern("h a", Locale.US)
private val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM")

// One hour of the forecast: when it starts, the temperature, the rain chance in percent, the weather code and whether
// it's daytime
data class HourWeather(val time: Long, val temp: Int, val rain: Int, val code: Int, val daytime: Boolean)

// One day of the forecast: its date, high, low and highest rain chance
data class DayWeather(val date: LocalDate, val high: Int, val low: Int, val rain: Int)

// A forecast: the weather right now, each hour, and each day
data class Forecast(val now: HourWeather?, val hours: List<HourWeather>, val days: List<DayWeather>)

// Return true if temperatures should be in Fahrenheit, as in the US
fun usesFahrenheit(locale: Locale = Locale.getDefault()): Boolean {
    return locale.country in FAHRENHEIT_COUNTRIES
}

// The Open-Meteo address for 3 days of forecast at a place, rounded to 2 decimals (about a kilometre)
fun forecastUrl(lat: Double, lng: Double, fahrenheit: Boolean): String {
    val place = String.format(Locale.US, "latitude=%.2f&longitude=%.2f", lat, lng)
    val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""  // Celsius is the default
    return "https://api.open-meteo.com/v1/forecast?$place" +
        "&current=temperature_2m,weather_code,is_day" +
        "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=auto&timeformat=unixtime&forecast_days=3$unit"
}

// Read an Open-Meteo reply into a forecast, or null if it can't be read
fun parseForecast(json: String): Forecast? {
    // A broken or unexpected reply gives no forecast
    return try {
        val root = MAPPER.readTree(json)
        val offset = root.path("utc_offset_seconds").asLong(0)
        val hourly = root.path("hourly")
        val hours = hourly.path("time").mapIndexed { index, time ->
            HourWeather(time.asLong() * 1000, hourly.path("temperature_2m")[index].asDouble().roundToInt(),
                hourly.path("precipitation_probability")[index]?.asInt(0) ?: 0,
                hourly.path("weather_code")[index]?.asInt(0) ?: 0, hourly.path("is_day")[index]?.asInt(1) != 0)
        }
        val daily = root.path("daily")
        val days = daily.path("time").mapIndexed { index, time ->
            val date = Instant.ofEpochSecond(time.asLong() + offset).atOffset(ZoneOffset.UTC).toLocalDate()
            DayWeather(date, daily.path("temperature_2m_max")[index].asDouble().roundToInt(),
                daily.path("temperature_2m_min")[index].asDouble().roundToInt(),
                daily.path("precipitation_probability_max")[index]?.asInt(0) ?: 0)
        }
        val now = currentOf(root.path("current"))
        if (hours.isEmpty() && days.isEmpty()) null else Forecast(now, hours, days)  // an empty reply is no forecast
    } catch (error: Exception) {  // not JSON, or missing numbers
        Log.w("Datebook", "weather reply unreadable: ${error.message}")
        null
    }
}

// The weather right now from the reply's "current" part, or null when it's missing
private fun currentOf(current: JsonNode): HourWeather? {
    // An old or partial reply may have no current weather
    if (!current.has("temperature_2m")) {
        return null
    }
    return HourWeather(current.path("time").asLong() * 1000, current.path("temperature_2m").asDouble().roundToInt(),
        0, current.path("weather_code").asInt(0), current.path("is_day").asInt(1) != 0)
}

// The kind of sky for a weather code (Open-Meteo's WMO codes): clear (or night when it's dark), cloudy, rain or snow
fun skyOf(code: Int, daytime: Boolean): String {
    // Group the codes
    return when (code) {
        0, 1 -> if (daytime) SKY_CLEAR else SKY_NIGHT
        in 71..77, 85, 86 -> SKY_SNOW
        in 51..67, in 80..82, in 95..99 -> SKY_RAIN
        else -> SKY_CLOUDY  // partly cloudy, overcast and fog
    }
}

// The forecast hour a moment falls in, or null when the forecast doesn't reach it
fun hourAt(forecast: Forecast, millis: Long): HourWeather? {
    return forecast.hours.firstOrNull { millis >= it.time && millis < it.time + HOUR_MILLIS }
}

// An hour's weather for a Today row, like "47°" or "47° · 70%" when rain is likely enough to mention
fun hourText(hour: HourWeather): String {
    return if (hour.rain >= RAIN_SHOWN) "${hour.temp}° · ${hour.rain}%" else "${hour.temp}°"
}

// Today's weather in words, like "High 52° · Low 41° · Rain 70% after 4 PM", "… · Rain 70% now" or
// "… · No rain expected" (null when the forecast doesn't cover today)
fun dayText(forecast: Forecast, nowMillis: Long, zone: ZoneId): String? {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day = forecast.days.firstOrNull { it.date == today } ?: return null
    val rest = forecast.hours.filter { it.time + HOUR_MILLIS > nowMillis }
        .filter { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() == today }
    val wet = rest.firstOrNull { it.rain >= RAIN_LIKELY }
    val chance = rest.maxOfOrNull { it.rain } ?: 0

    // Say when rain is likely: now, after an hour, or not at all
    val rain = when {
        wet == null -> "No rain expected"
        wet.time <= nowMillis -> "Rain $chance% now"
        else -> "Rain $chance% after ${Instant.ofEpochMilli(wet.time).atZone(zone).format(HOUR_CLOCK)}"
    }
    return "High ${day.high}° · Low ${day.low}° · $rain"
}

// Keeps the last forecast and when it was fetched
class WeatherStore(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-weather", Context.MODE_PRIVATE)

    // The saved forecast, or null before the first one
    fun forecast(): Forecast? {
        return prefs.getString("reply", null)?.let { parseForecast(it) }
    }

    // Return true if the forecast is missing, over an hour old, or for other units
    fun due(nowMillis: Long, fahrenheit: Boolean): Boolean {
        val stale = nowMillis - prefs.getLong("fetched", 0) > HOUR_MILLIS
        return stale || prefs.getBoolean("fahrenheit", !fahrenheit) != fahrenheit
    }

    // Save a new forecast reply
    fun save(reply: String, nowMillis: Long, fahrenheit: Boolean) {
        prefs.edit().putString("reply", reply).putLong("fetched", nowMillis).putBoolean("fahrenheit", fahrenheit)
            .apply()
    }
}

// Download a forecast reply, or null on any failure (logged); this waits on the network, so call it off the main
// thread
private fun download(url: String): String? {
    val connection = URL(url).openConnection() as HttpURLConnection

    // Any network or server problem gives no reply
    return try {
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        check(connection.responseCode in 200..299) { "Open-Meteo error ${connection.responseCode}" }
        connection.inputStream.bufferedReader().use { it.readText() }
    } catch (error: Exception) {  // no internet or a refused request
        Log.w("Datebook", "weather download failed: ${error.message}")
        null
    } finally {
        connection.disconnect()
    }
}

// The forecast for Home: a fresh one when the saved one is over an hour old (and the download works), else the saved
// one; call it off the main thread
fun homeForecast(context: Context, nowMillis: Long, zone: ZoneId): Forecast? {
    val store = WeatherStore(context)
    val fahrenheit = usesFahrenheit()

    // Fetch a new forecast when it's due; keep the old one if that fails
    if (store.due(nowMillis, fahrenheit)) {
        val (lat, lng) = SunPlace(context).find(TravelSettings(context).home(), zone)
        val reply = download(forecastUrl(lat, lng, fahrenheit))

        // Only keep a reply that reads as a forecast
        if (reply != null && parseForecast(reply) != null) {
            store.save(reply, nowMillis, fahrenheit)
        }
    }
    return store.forecast()
}
