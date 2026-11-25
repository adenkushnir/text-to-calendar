// Journeys: the whole trip to a place, step by step, from Google's Routes API: walks, each train or bus (its line,
// where it's headed, the stops you get on and off at, and when), transfers, or a drive; with the time you leave and
// arrive. A plan's journey is worked out when its card opens and kept for next time
package io.github.adenkushnir.datebook.journeys
import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.adenkushnir.datebook.places.TRAVEL_DRIVE
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.TRAVEL_WALK
import io.github.adenkushnir.datebook.places.currentLocation
import io.github.adenkushnir.datebook.places.postJson
import io.github.adenkushnir.datebook.places.routeBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
const val STEP_WALK = "walk"
const val STEP_TRAIN = "train"
const val STEP_BUS = "bus"
const val STEP_SUBWAY = "subway"
const val STEP_DRIVE = "drive"
const val STEP_ARRIVE = "arrive"
const val STEP_BOARD = "board"  // a flight's boarding
const val STEP_FLIGHT = "flight"  // a flight leaving
private const val CHOICE_GAP_MINUTES = 30L  // the other trains are looked for this much earlier and just after
private const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
private const val JOURNEY_FIELDS = "routes.duration,routes.legs.steps.travelMode,routes.legs.steps.staticDuration," +
    "routes.legs.steps.transitDetails"
private val MAPPER = jacksonObjectMapper()
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// One step of a journey: its kind (walk, train, bus, subway, drive, arrive, board or flight), what it is, a line under
// it, the line's short name and colour (for transit), the train's number when known, when it starts and ends
// (milliseconds), and where it starts and ends (empty for home), for Maps
data class JourneyStep(
    val kind: String = STEP_WALK,
    val title: String = "",
    val under: String = "",
    val line: String = "",
    val color: String = "",
    val trainId: String = "",
    val starts: Long = 0,
    val ends: Long = 0,
    val fromName: String = "",
    val toName: String = "",
)

// A journey: its steps (ending with arriving), when you leave and arrive, how long it takes, and the travel mode
data class Journey(
    val steps: List<JourneyStep> = emptyList(),
    val leave: Long = 0,
    val arrive: Long = 0,
    val minutes: Int = 0,
    val mode: String = TRAVEL_TRANSIT,
)

// A length of time from Google, like "360s", in whole seconds (0 when unreadable)
private fun seconds(text: String): Long {
    return text.removeSuffix("s").toDoubleOrNull()?.toLong() ?: 0
}

// A time from Google, like "2026-11-25T19:20:00Z", in milliseconds (null when missing)
private fun timeOf(node: JsonNode): Long? {
    val text = node.asText("")
    return if (text.isBlank()) null else runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

// What a transit vehicle is called on a step: train, bus or subway
private fun vehicleKind(type: String): String {
    // Group Google's many vehicle types
    return when (type) {
        "BUS", "INTERCITY_BUS", "TROLLEYBUS" -> STEP_BUS
        "SUBWAY", "METRO_RAIL", "TRAM", "MONORAIL", "LIGHT_RAIL" -> STEP_SUBWAY
        else -> STEP_TRAIN
    }
}

// One piece of Google's route before times are filled in: a walk or drive of some seconds, or a transit ride with
// its own times
private data class Piece(val mode: String, val seconds: Long, val ride: JsonNode? = null)

// Read Google's route into a journey to a place: walking steps are joined, each ride keeps its line, headsign, stops
// and times, walks before and between rides end when the next ride leaves, and a drive or walk with no rides is
// timed from when you leave (or back from when you mean to arrive); null when there's no route
fun parseJourney(json: String, placeName: String, mode: String, leaveAt: Long?, arriveBy: Long?, zone: ZoneId):
    Journey? {
    val route = MAPPER.readTree(json).path("routes").path(0)

    // No route at all
    if (route.isMissingNode) {
        return null
    }
    val pieces = mutableListOf<Piece>()

    // Each of Google's steps, joining walks (and drives) that follow each other
    for (step in route.path("legs").flatMap { it.path("steps") }) {
        val stepMode = step.path("travelMode").asText("")
        val length = seconds(step.path("staticDuration").asText(""))

        // A ride stands alone; a walk or drive joins the one before it
        if (stepMode == "TRANSIT") {
            pieces.add(Piece(stepMode, length, step.path("transitDetails")))
        } else if (pieces.lastOrNull()?.mode == stepMode) {
            pieces[pieces.size - 1] = pieces.last().copy(seconds = pieces.last().seconds + length)
        } else {
            pieces.add(Piece(stepMode, length))
        }
    }
    val total = seconds(route.path("duration").asText(""))
    val firstRide = pieces.indexOfFirst { it.ride != null }
    val clock = { millis: Long -> Instant.ofEpochMilli(millis).atZone(zone).format(CLOCK) }

    // Without rides: one walk or drive, timed from leaving (or back from arriving)
    if (firstRide < 0) {
        val leave = arriveBy?.let { it - total * 1000 } ?: leaveAt ?: System.currentTimeMillis()
        val arrive = leave + total * 1000
        val minutes = ((total + 59) / 60).toInt()
        val kind = if (mode == TRAVEL_DRIVE) STEP_DRIVE else STEP_WALK
        val word = if (kind == STEP_DRIVE) "drive" else "walk"
        return Journey(listOf(JourneyStep(kind, "Leave · $minutes min $word", "to $placeName", starts = leave,
            ends = arrive, toName = placeName), JourneyStep(STEP_ARRIVE, "Arrive · $placeName", starts = arrive,
            ends = arrive)), leave, arrive, minutes, mode)
    }
    val steps = mutableListOf<JourneyStep>()
    var at = timeOf(pieces[firstRide].ride!!.path("stopDetails").path("departureTime")) ?: return null

    // The walk to the first ride ends when it leaves
    for (index in pieces.indices) {
        val piece = pieces[index]
        val ride = piece.ride

        // Walks are timed from what's around them; rides carry their own times
        if (ride == null) {
            val minutes = ((piece.seconds + 59) / 60).toInt()
            val next = pieces.drop(index + 1).firstOrNull { it.ride != null }?.ride
            val toward = next?.path("stopDetails")?.path("departureStop")?.path("name")?.asText("").orEmpty()
                .ifBlank { placeName }
            val starts = if (index < firstRide) at - piece.seconds * 1000 else at
            val ends = starts + piece.seconds * 1000
            val title = if (index == 0) "Leave · $minutes min walk" else "$minutes min walk"
            val from = pieces.take(index).lastOrNull { it.ride != null }?.ride?.path("stopDetails")
                ?.path("arrivalStop")?.path("name")?.asText("").orEmpty()
            steps.add(JourneyStep(STEP_WALK, title, "to $toward", starts = starts, ends = ends, fromName = from,
                toName = toward))
            at = maxOf(at, ends)
        } else {
            val stops = ride.path("stopDetails")
            val departs = timeOf(stops.path("departureTime")) ?: at
            val arrives = timeOf(stops.path("arrivalTime")) ?: (departs + piece.seconds * 1000)
            val line = ride.path("transitLine")
            val kind = vehicleKind(line.path("vehicle").path("type").asText(""))
            val short = line.path("nameShort").asText("").ifBlank { line.path("name").asText("") }
            val headsign = ride.path("headsign").asText("")
            val number = ride.path("tripShortText").asText("")
            val word = when (kind) {
                STEP_BUS -> "Bus"
                STEP_SUBWAY -> line.path("name").asText("Subway")
                else -> if (number.isNotBlank()) "Train $number" else "Train"
            }
            val count = ride.path("stopCount").asInt(0)
            val rideMinutes = ((arrives - departs) / 60_000).toInt()
            val title = (if (kind == STEP_BUS && short.isNotBlank()) "Bus $short" else word) +
                (if (headsign.isNotBlank()) " toward $headsign" else "")
            val under = listOfNotNull("$rideMinutes min", if (count > 0) "$count stops" else null,
                "from ${stops.path("departureStop").path("name").asText("")}",
                "off at ${stops.path("arrivalStop").path("name").asText("")}").joinToString(" · ")
            steps.add(JourneyStep(kind, title, under, short, line.path("color").asText(""), number, departs, arrives,
                stops.path("departureStop").path("name").asText(""), stops.path("arrivalStop").path("name").asText("")))
            at = arrives
        }
    }
    val leave = steps.first().starts
    val arrive = steps.last().ends
    steps.add(JourneyStep(STEP_ARRIVE, "Arrive · $placeName", "at ${clock(arrive)}", starts = arrive, ends = arrive))
    return Journey(steps, leave, arrive, ((arrive - leave) / 60_000).toInt(), mode)
}

// The request for a journey: the route body (see routeBody) leaving at a time or arriving by one, and for transit the
// kinds of vehicle allowed ("TRAIN", "BUS"; all when empty)
fun journeyBody(from: Pair<Double, Double>?, home: String, place: String, mode: String, leaveAt: Long?, arriveBy: Long?,
    allowed: List<String>): Map<String, Any>? {
    val body = routeBody(from, home, place, mode, Instant.ofEpochMilli(arriveBy ?: leaveAt ?: 0))?.toMutableMap()
        ?: return null

    // Transit plans to a time: arriving by it, or leaving at it; it can be limited to trains or buses
    if (mode == TRAVEL_TRANSIT) {
        body.remove("arrivalTime")

        // Arriving by a time, leaving at one, or leaving now
        if (arriveBy != null) {
            body["arrivalTime"] = Instant.ofEpochMilli(arriveBy).toString()
        } else if (leaveAt != null) {
            body["departureTime"] = Instant.ofEpochMilli(leaveAt).toString()
        }

        if (allowed.isNotEmpty()) {
            body["transitPreferences"] = mapOf("allowedTravelModes" to allowed)
        }
    }
    return body
}

// Ask Google for the journey to a place by a mode, from where the phone is (or home); null without a key, a route or
// on any failure; this waits on the network, so call it off the main thread
fun planJourney(context: Context, key: String?, home: String, place: String, placeName: String, mode: String,
    leaveAt: Long?, arriveBy: Long?, allowed: List<String> = emptyList()): Journey? {
    // Nothing to ask without a key or a place
    if (key.isNullOrBlank() || place.isBlank()) {
        return null
    }
    val walking = if (mode == TRAVEL_TRANSIT || mode == TRAVEL_DRIVE) mode else TRAVEL_WALK
    val body = journeyBody(currentLocation(context), home, place, walking, leaveAt, arriveBy, allowed) ?: return null

    // Any network or reply problem means no journey
    return try {
        parseJourney(postJson(context, ROUTES_URL, key, JOURNEY_FIELDS, body), placeName, walking, leaveAt, arriveBy,
            ZoneId.systemDefault())
    } catch (error: Exception) {  // no internet, a rejected key, or an unreadable reply
        Log.w("Datebook", "journey failed: ${error.message}")
        null
    }
}

// Other ways to make the same trip for a train picker: the journey arriving by the time (as planned), an earlier one
// (arriving 30 minutes sooner) and the next one after the current first ride; one per first ride, earliest first
fun journeyChoices(context: Context, key: String?, home: String, place: String, placeName: String, mode: String,
    arriveBy: Long, current: Journey): List<Journey> {
    val firstRide = current.steps.firstOrNull { it.kind in setOf(STEP_TRAIN, STEP_BUS, STEP_SUBWAY) }
    val asked = listOfNotNull(Pair(null, arriveBy), Pair(null, arriveBy - CHOICE_GAP_MINUTES * 60_000),
        firstRide?.let { Pair(it.starts + 60_000, null) })
    val found = listOf(current) + asked.mapNotNull { (leaveAt, by) ->
        planJourney(context, key, home, place, placeName, mode, leaveAt, by)
    }
    return found.distinctBy { journey -> journey.steps.firstOrNull { it.kind == STEP_TRAIN || it.kind == STEP_BUS ||
        it.kind == STEP_SUBWAY }?.starts ?: journey.leave }.sortedBy { it.leave }
}

// A flight plan's journey with boarding and the flight after arriving at the airport: "Boarding about 3:35 PM" (the
// terminal and gate under it) and "UA2433 departs" (when it lands)
fun withFlightSteps(journey: Journey, number: String, departs: Long, boarding: Int, gate: String, lands: Long?,
    toCity: String, zone: ZoneId): Journey {
    val clock = { millis: Long -> Instant.ofEpochMilli(millis).atZone(zone).format(CLOCK) }
    val boards = departs - boarding * 60_000L
    val board = JourneyStep(STEP_BOARD, "Boarding about ${clock(boards)}",
        listOf(gate, "$boarding min before departure").filter { it.isNotBlank() }.joinToString(" · "),
        starts = boards, ends = boards)
    val leaving = JourneyStep(STEP_FLIGHT, "${number.ifBlank { "Flight" }} departs",
        lands?.let { "Lands ${clock(it)}" + if (toCity.isNotBlank()) " in $toCity" else "" }.orEmpty(),
        starts = departs, ends = departs)
    return journey.copy(steps = journey.steps + board + leaving)
}

// A journey's steps with the picked NJ Transit train's live state added to its ride (same train number), like
// "3 min late" or "Track 2"
fun withLiveTrain(journey: Journey, trainId: String, track: String, minutesLate: Int, cancelled: Boolean): Journey {
    val live = listOfNotNull(if (cancelled) "Cancelled" else if (minutesLate > 0) "$minutesLate min late" else "On time",
        if (track.isNotBlank()) "Track $track" else null).joinToString(" · ")
    return journey.copy(steps = journey.steps.map { step ->
        if (step.kind == STEP_TRAIN && step.trainId == trainId) step.copy(under = "$live · ${step.under}") else step
    })
}

// Remembers each plan's journey (by the plan's key, like leave-by alerts), so a card opens with it straight away
class JourneyStore(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-journeys", Context.MODE_PRIVATE)

    // Return a plan's kept journey, and when it was worked out (null when there's none or it can't be read)
    fun journey(key: String): Pair<Journey, Long>? {
        val saved = prefs.getString("journey:$key", null) ?: return null
        val journey = runCatching { MAPPER.readValue<Journey>(saved) }.getOrNull() ?: return null
        return Pair(journey, prefs.getLong("at:$key", 0))
    }

    // Keep a plan's journey, noting when it was worked out and whether you picked it (a picked one keeps its trains)
    fun save(key: String, journey: Journey, nowMillis: Long, picked: Boolean = false) {
        prefs.edit().putString("journey:$key", MAPPER.writeValueAsString(journey)).putLong("at:$key", nowMillis)
            .putBoolean("picked:$key", picked).apply()
    }

    // Return true if you picked a plan's journey (another train), so it's refreshed leaving at the same time
    fun picked(key: String): Boolean {
        return prefs.getBoolean("picked:$key", false)
    }
}
