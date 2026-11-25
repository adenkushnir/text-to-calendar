// Live flights from AeroDataBox (through RapidAPI): a flight's times, status, terminal and gate by flight number and
// date, sending only the flight number and date; also what Datebook remembers about each flight between checks
package io.github.adenkushnir.datebook.flights
import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.adenkushnir.datebook.pipeline.Event
import io.github.adenkushnir.datebook.pipeline.zoneOf
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
private const val FLIGHTS_HOST = "aerodatabox.p.rapidapi.com"
private const val TIMEOUT_MILLIS = 10000
private val MAPPER = ObjectMapper()
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val LOCAL_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
const val FLIGHT_EARLY_MINUTES = 60  // how early to be at the airport for a flight when nothing else says
const val INTERNATIONAL_EARLY_MINUTES = 120
private val FLIGHT_TITLE = Regex("^Flight ([A-Z0-9]{2}[0-9]{1,4})( |$)")

// One flight as AeroDataBox describes it: the number, its status, both airports (code, city, name, time zone), the
// scheduled and best-known departure and arrival times, the departure terminal and gate, and both airports' countries
data class FlightInfo(
    val number: String,
    val status: String,
    val fromCode: String,
    val fromCity: String,
    val fromName: String,
    val fromZone: String,
    val toCode: String,
    val toCity: String,
    val departScheduled: Instant?,
    val departBest: Instant?,
    val arriveScheduled: Instant?,
    val arriveBest: Instant?,
    val terminal: String,
    val gate: String,
    val fromCountry: String = "",
    val toCountry: String = "",
)

// Read a time from AeroDataBox, like "2037-11-13 23:45Z" or "2037-11-13 18:45-05:00" (null when missing or unreadable)
fun flightTime(text: String?): Instant? {
    // Missing times stay unknown
    if (text.isNullOrBlank()) {
        return null
    }

    // Times that can't be read stay unknown
    return try {
        OffsetDateTime.parse(text.trim().replace(' ', 'T')).toInstant()
    } catch (error: Exception) {  // an unexpected format
        null
    }
}

// The best-known time of a departure or arrival: the actual (runway) time, else the revised, predicted or scheduled one
private fun bestTime(movement: JsonNode): Instant? {
    val order = listOf("runwayTime", "revisedTime", "predictedTime", "scheduledTime")
    return order.firstNotNullOfOrNull { flightTime(movement.path(it).path("utc").asText(null)) }
}

// Every leg in AeroDataBox's reply that departs on the given local date (all of them when none does); one flight
// number can fly several legs a day, like Fort Lauderdale → Newark, then Newark → Boston
fun parseFlights(json: String, date: String): List<FlightInfo> {
    val flights = MAPPER.readTree(json)

    // An empty reply means the flight wasn't found
    if (!flights.isArray || flights.isEmpty) {
        return emptyList()
    }
    val onDay = flights.filter { it.path("departure").path("scheduledTime").path("local").asText("").startsWith(date) }
    return onDay.ifEmpty { flights.toList() }.map { infoOf(it) }
}

// Return true if a leg lands where it says: an airport code ("BOS"), a city ("Boston"), or a route ending in one
// ("EWR-BOS"); any leg matches when nothing is said
fun legMatches(info: FlightInfo, leg: String): Boolean {
    val wanted = leg.trim().split("-", "→", ">").last().trim()
    return wanted.isBlank() || (info.toCode.isNotBlank() && info.toCode.equals(wanted, ignoreCase = true)) ||
        (info.toCity.isNotBlank() && (info.toCity.contains(wanted, ignoreCase = true) ||
            wanted.contains(info.toCity, ignoreCase = true)))
}

// The leg meant (see legMatches), else the first one; null when there are none
fun pickLeg(legs: List<FlightInfo>, leg: String): FlightInfo? {
    return legs.firstOrNull { legMatches(it, leg) } ?: legs.firstOrNull()
}

// Read the flights in AeroDataBox's reply, keeping the leg meant (or the first) departing on the given local date
fun parseFlight(json: String, date: String, leg: String = ""): FlightInfo? {
    return pickLeg(parseFlights(json, date), leg)
}

// One leg of AeroDataBox's reply as a flight's details
private fun infoOf(chosen: JsonNode): FlightInfo {
    val departure = chosen.path("departure")
    val arrival = chosen.path("arrival")
    return FlightInfo(
        number = chosen.path("number").asText("").replace(" ", ""),
        status = chosen.path("status").asText("Unknown"),
        fromCode = departure.path("airport").path("iata").asText(""),
        fromCity = departure.path("airport").path("municipalityName").asText(""),
        fromName = departure.path("airport").path("name").asText(""),
        fromZone = departure.path("airport").path("timeZone").asText(""),
        toCode = arrival.path("airport").path("iata").asText(""),
        toCity = arrival.path("airport").path("municipalityName").asText(""),
        departScheduled = flightTime(departure.path("scheduledTime").path("utc").asText(null)),
        departBest = bestTime(departure),
        arriveScheduled = flightTime(arrival.path("scheduledTime").path("utc").asText(null)),
        arriveBest = bestTime(arrival),
        terminal = departure.path("terminal").asText(""),
        gate = departure.path("gate").asText(""),
        fromCountry = departure.path("airport").path("countryCode").asText(""),
        toCountry = arrival.path("airport").path("countryCode").asText(""),
    )
}

// Look up a flight by number and local date, keeping the leg meant (see legMatches, else the first); null without a
// key, when it isn't found, or on any failure; this waits on the network, so call it off the main thread
fun lookupFlight(key: String?, flight: String, date: String, leg: String = ""): FlightInfo? {
    return pickLeg(lookupFlights(key, flight, date), leg)
}

// Look up every leg a flight number flies on a local date; empty without a key, when it isn't found, or on any
// failure; this waits on the network, so call it off the main thread
fun lookupFlights(key: String?, flight: String, date: String): List<FlightInfo> {
    // Nothing to ask without a key or a flight
    if (key.isNullOrBlank() || flight.isBlank() || date.length < 10) {
        return emptyList()
    }
    val url = "https://$FLIGHTS_HOST/flights/number/$flight/${date.take(10)}" +
        "?withAircraftImage=false&withLocation=false&dateLocalRole=Departure"
    val connection = URL(url).openConnection() as HttpURLConnection

    // Any network or reply problem means no flight data
    return try {
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("x-rapidapi-key", key)
        connection.setRequestProperty("x-rapidapi-host", FLIGHTS_HOST)
        val code = connection.responseCode
        val reply = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(code in 200..299) { "flight data error $code: ${reply.take(200)}" }
        if (code == 204 || reply.isBlank()) emptyList() else parseFlights(reply, date.take(10))  // 204: not found
    } catch (error: Exception) {  // no internet, a rejected key, or an unreadable reply
        Log.w("Datebook", "flight lookup failed: ${error.message}")
        emptyList()
    } finally {
        connection.disconnect()
    }
}

// A time in a zone the way Datebook writes event times, YYYY-MM-DDTHH:MM
fun zonedText(instant: Instant, zone: ZoneId): String {
    return LocalDateTime.ofInstant(instant, zone).format(LOCAL_MINUTES)
}

// Return true if a calendar event is a flight plan, like "Flight UA1234 EWR → SFO"
fun isFlightTitle(title: String): Boolean {
    return FLIGHT_TITLE.containsMatchIn(title)
}

// The flight number in a flight plan's title, like UA1234 from "Flight UA1234 EWR → SFO" (null for other titles)
fun flightOfTitle(title: String): String? {
    return FLIGHT_TITLE.find(title)?.groupValues?.get(1)
}

// Return true if a flight leaves one country for another (false when either country is unknown)
fun isInternational(info: FlightInfo): Boolean {
    return info.fromCountry.isNotBlank() && info.toCountry.isNotBlank() &&
        !info.fromCountry.equals(info.toCountry, ignoreCase = true)
}

// How early to be at the airport for a flight when you haven't said: 2 hours abroad, else 1 hour
fun flightEarlyMinutes(info: FlightInfo?): Int {
    return if (info != null && isInternational(info)) INTERNATIONAL_EARLY_MINUTES else FLIGHT_EARLY_MINUTES
}

// The departure airport in a few words, like "Newark Airport" (its full name when the city isn't known)
fun airportName(info: FlightInfo): String {
    return if (info.fromCity.isNotBlank()) "${info.fromCity} Airport" else info.fromName.ifBlank { info.fromCode }
}

// Fill a flight plan in from live flight data: its title with the route, its departure and arrival in the departure
// airport's time (the delayed times when it's already delayed, else the scheduled ones), and the airport as its place;
// it stays as written without data
fun withFlight(event: Event, info: FlightInfo?): Event {
    // No data, nothing to fill in
    if (info == null) {
        return event
    }
    val departs = info.departBest ?: info.departScheduled ?: return event  // no times to fill in
    val zone = zoneOf(info.fromZone) ?: zoneOf(event.phoneTimezone) ?: ZoneId.systemDefault()
    val end = (info.arriveBest ?: info.arriveScheduled)?.let { zonedText(it, zone) }.orEmpty()  // empty if unknown
    return event.copy(title = flightTitle(info), start = zonedText(departs, zone), end = end, allDay = false,
        timezone = zone.id, location = info.fromName.ifBlank { event.location })
}

// A flight's status for Nestor's wall: the status line, and whether it's late or cancelled (shown in orange)
fun wallFlight(info: FlightInfo): Map<String, Any> {
    return mapOf("text" to statusLine(info), "late" to (minutesLate(info) >= 5 || info.status.startsWith("Canceled")))
}

// A flight plan's title, like "Flight UA1234 EWR → SFO"
fun flightTitle(info: FlightInfo): String {
    val route = if (info.fromCode.isNotBlank() && info.toCode.isNotBlank()) " ${info.fromCode} → ${info.toCode}" else ""
    return "Flight ${info.number}$route"
}

// How long a flight is late, in whole minutes (0 when on time or unknown)
fun minutesLate(info: FlightInfo): Long {
    val scheduled = info.departScheduled ?: return 0
    val best = info.departBest ?: return 0
    return maxOf(0, (best.toEpochMilli() - scheduled.toEpochMilli()) / 60_000)
}

// The live status line for a flight's card, like "Delayed 25 min · departs 7:10 PM · Terminal C, gate C12" or
// "Cancelled", in the departure airport's time
fun statusLine(info: FlightInfo): String {
    val zone = zoneOf(info.fromZone) ?: ZoneId.systemDefault()
    val departs = info.departBest?.atZone(zone)?.format(CLOCK)
    val gate = listOf(info.terminal.takeIf { it.isNotBlank() }?.let { "Terminal $it" },
        info.gate.takeIf { it.isNotBlank() }?.let { "gate $it" }).filterNotNull().joinToString(", ")
    val late = minutesLate(info)

    // Pick the words for the flight's state
    val state = when {
        info.status.startsWith("Canceled") -> return "Cancelled"
        info.status == "Arrived" -> "Landed"
        info.status == "Departed" || info.status == "EnRoute" || info.status == "Approaching" -> "In the air"
        late >= 5 -> "Delayed $late min"
        else -> "On time"
    }
    val parts = listOf(state, departs?.let { "departs $it" }, gate.ifBlank { null })
    return parts.filterNotNull().joinToString(" · ")
}

// Remembers each flight's last-known details between checks, and which checks were done, keyed by flight and date
class FlightStore(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-flights", Context.MODE_PRIVATE)

    // Remember a flight's latest details, and which leg it is (where it lands)
    fun save(date: String, info: FlightInfo) {
        prefs.edit().putString("info:${info.number}|${date.take(10)}", MAPPER.writeValueAsString(toMap(info)))
            .putString("leg:${info.number}|${date.take(10)}", info.toCode).apply()
    }

    // Return which leg of a flight number its plan is, as where it lands ("" when not known)
    fun leg(flight: String, date: String): String {
        return prefs.getString("leg:$flight|${date.take(10)}", "").orEmpty()
    }

    // Remember which leg a plan is before its flight is found (an airport code or city)
    fun setLeg(flight: String, date: String, leg: String) {
        prefs.edit().putString("leg:$flight|${date.take(10)}", leg).apply()
    }

    // Return a flight's last-known details, or null
    fun info(flight: String, date: String): FlightInfo? {
        val saved = prefs.getString("info:$flight|${date.take(10)}", null) ?: return null
        return fromMap(MAPPER.readTree(saved))
    }

    // Return true once a check (named by its time in milliseconds) was done for a flight
    fun checked(flight: String, date: String, at: Long): Boolean {
        return prefs.getBoolean("check:$flight|${date.take(10)}|$at", false)
    }

    // Remember that a check was done
    fun setChecked(flight: String, date: String, at: Long) {
        prefs.edit().putBoolean("check:$flight|${date.take(10)}|$at", true).apply()
    }

    // A flight's details as plain values for saving
    private fun toMap(info: FlightInfo): Map<String, Any?> {
        return mapOf("number" to info.number, "status" to info.status, "fromCode" to info.fromCode,
            "fromCity" to info.fromCity, "fromName" to info.fromName, "fromZone" to info.fromZone,
            "toCode" to info.toCode, "toCity" to info.toCity, "departScheduled" to info.departScheduled?.toString(),
            "departBest" to info.departBest?.toString(), "arriveScheduled" to info.arriveScheduled?.toString(),
            "arriveBest" to info.arriveBest?.toString(), "terminal" to info.terminal, "gate" to info.gate,
            "fromCountry" to info.fromCountry, "toCountry" to info.toCountry)
    }

    // Read saved plain values back into a flight's details
    private fun fromMap(node: JsonNode): FlightInfo {
        // Read one saved time, if there was one
        fun time(name: String): Instant? = node.path(name).asText(null)?.let { Instant.parse(it) }
        return FlightInfo(node.path("number").asText(""), node.path("status").asText(""),
            node.path("fromCode").asText(""), node.path("fromCity").asText(""), node.path("fromName").asText(""),
            node.path("fromZone").asText(""), node.path("toCode").asText(""), node.path("toCity").asText(""),
            time("departScheduled"), time("departBest"), time("arriveScheduled"), time("arriveBest"),
            node.path("terminal").asText(""), node.path("gate").asText(""), node.path("fromCountry").asText(""),
            node.path("toCountry").asText(""))
    }
}

// What changed between two looks at a flight that's worth an alert: cancelled, a new departure time (5+ minutes
// different) or a new gate; null when nothing did
fun flightChange(before: FlightInfo, after: FlightInfo): String? {
    val zone = zoneOf(after.fromZone) ?: ZoneId.systemDefault()
    val moved = before.departBest != null && after.departBest != null &&
        kotlin.math.abs(after.departBest.toEpochMilli() - before.departBest.toEpochMilli()) >= 5 * 60_000

    // Pick the most important change
    return when {
        after.status.startsWith("Canceled") && !before.status.startsWith("Canceled") -> "${after.number} is cancelled"
        moved -> "${after.number} now departs ${after.departBest.atZone(zone).format(CLOCK)}" +
            (if (minutesLate(after) >= 5) " (${minutesLate(after)} min late)" else "")
        after.gate.isNotBlank() && after.gate != before.gate -> "${after.number} now at gate ${after.gate}"
        else -> null
    }
}
