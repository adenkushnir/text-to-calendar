// Rutgers campus buses: the timetable (TripShot's free GTFS feed, downloaded weekly), the best bus between two spots
// within a 15-minute walk of its stops, swapping a trip's walk for a bus when it lets you leave later or get there
// sooner, the other buses for a picker, and the live times (TripShot's GTFS-realtime trip updates)
package io.github.adenkushnir.datebook.buses
import android.content.Context
import android.util.Log
import io.github.adenkushnir.datebook.journeys.Journey
import io.github.adenkushnir.datebook.journeys.JourneyStep
import io.github.adenkushnir.datebook.journeys.STEP_ARRIVE
import io.github.adenkushnir.datebook.journeys.STEP_BUS
import io.github.adenkushnir.datebook.journeys.STEP_SUBWAY
import io.github.adenkushnir.datebook.journeys.STEP_TRAIN
import io.github.adenkushnir.datebook.journeys.STEP_WALK
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
private const val TIMETABLE_URL = "https://rutgers.tripshot.com/v1/gtfs.zip"
private const val LIVE_URL = "https://rutgers.tripshot.com/v1/gtfs/realtime/tripUpdate"
private const val REFRESH_MILLIS = 7 * 24 * 3600_000L  // the timetable is fetched again after a week
private const val LIVE_MILLIS = 30_000L  // live times are kept this long
const val MAX_WALK_MINUTES = 15  // the farthest walk to or from a stop
private const val WALK_METERS_PER_MINUTE = 80.0
private const val DETOUR = 1.25  // streets aren't straight lines
private const val MIN_GAIN_MINUTES = 3  // a bus has to save at least this much to replace a walk
private const val SEARCH_MILLIS = 2 * 3600_000L  // how far around a time buses are looked for
private const val CHOICE_MILLIS = 40 * 60_000L  // the picker's other buses, this far either side of yours
const val SCHEDULED = "Scheduled"
private val KEPT_FILES = setOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar.txt",
    "calendar_dates.txt", "frequencies.txt")
val CAMPUS_ZONE: ZoneId = ZoneId.of("America/New_York")
private val DAY = DateTimeFormatter.BASIC_ISO_DATE
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// A bus stop: its id, name and where it is
data class BusStop(val id: String, val name: String, val lat: Double, val lon: Double)

// A route: its id, short name ("LX"), full name and colour ("CB99FE")
data class BusRoute(val id: String, val short: String, val name: String, val color: String)

// One run of a route: its stops in order with the seconds after midnight it arrives and leaves each, its route, the
// days it runs (service) and where it's headed
class BusTrip(val id: String, val route: String, val service: String, val headsign: String, val stops: List<String>,
    val arrives: IntArray, val departs: IntArray)

// A frequency-based run: the trip's stop times repeat every headway seconds from start to end
data class Headway(val start: Int, val end: Int, val every: Int)

// The days a service runs: its weekdays (Monday first) between two dates, plus dates added and taken away
class Service(val weekdays: BooleanArray, val from: LocalDate, val to: LocalDate) {
    val added = mutableSetOf<LocalDate>()
    val removed = mutableSetOf<LocalDate>()

    // Return true if it runs on a date
    fun runs(date: LocalDate): Boolean {
        // Dates added or taken away win over the weekly pattern
        if (date in added) {
            return true
        }

        if (date in removed) {
            return false
        }
        return !date.isBefore(from) && !date.isAfter(to) && weekdays[date.dayOfWeek.value - 1]
    }
}

// The whole timetable
class Timetable(val stops: Map<String, BusStop>, val routes: Map<String, BusRoute>, val trips: List<BusTrip>,
    val services: Map<String, Service>, val headways: Map<String, List<Headway>>)

// A bus ride from near one spot to near another: the run, its route, the stops you get on and off at (their place in
// the run), when it leaves and arrives (milliseconds), the walks to and from it (minutes) and how many stops it goes
data class BusRide(val trip: BusTrip, val route: BusRoute, val board: BusStop, val alight: BusStop, val departs: Long,
    val arrives: Long, val walkTo: Int, val walkFrom: Int, val stopCount: Int) {
    val leave: Long get() = departs - walkTo * 60_000L
    val there: Long get() = arrives + walkFrom * 60_000L
}

// Split one line of a GTFS file into its fields (quoted fields can hold commas)
fun csvFields(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0

    // Walk the line a character at a time
    while (index < line.length) {
        val character = line[index]

        // Quotes open and close a field ("" is a quote inside one); commas outside quotes end a field
        if (character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"') {
            field.append('"')
            index++
        } else if (character == '"') {
            quoted = !quoted
        } else if (character == ',' && !quoted) {
            fields.add(field.toString())
            field.clear()
        } else {
            field.append(character)
        }
        index++
    }
    fields.add(field.toString())
    return fields
}

// A GTFS file's rows as maps from column name to value
fun csvRows(text: String): List<Map<String, String>> {
    val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }

    // An empty file has no rows
    if (lines.isEmpty()) {
        return emptyList()
    }
    val names = csvFields(lines.first()).map { it.trim() }
    return lines.drop(1).map { line -> names.zip(csvFields(line)).toMap() }
}

// "08:05:30" (hours can pass 24 for runs after midnight) as seconds after midnight, or -1 when blank
private fun secondsOf(text: String): Int {
    val parts = text.trim().split(":")
    return if (parts.size == 3) parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt() else -1
}

// Read the timetable from its GTFS files (name → text)
fun parseTimetable(files: Map<String, String>): Timetable {
    val stops = csvRows(files["stops.txt"].orEmpty()).mapNotNull { row ->
        val lat = row["stop_lat"]?.toDoubleOrNull()
        val lon = row["stop_lon"]?.toDoubleOrNull()
        if (lat == null || lon == null) null else BusStop(row["stop_id"].orEmpty(), row["stop_name"].orEmpty().trim(),
            lat, lon)
    }.associateBy { it.id }
    val routes = csvRows(files["routes.txt"].orEmpty()).map { row ->
        BusRoute(row["route_id"].orEmpty(), row["route_short_name"].orEmpty().ifBlank { row["route_long_name"]
            .orEmpty() }, row["route_long_name"].orEmpty(), row["route_color"].orEmpty())
    }.associateBy { it.id }
    val times = csvRows(files["stop_times.txt"].orEmpty()).groupBy { it["trip_id"].orEmpty() }
    val trips = csvRows(files["trips.txt"].orEmpty()).mapNotNull { row ->
        val id = row["trip_id"].orEmpty()
        val stopTimes = times[id]?.sortedBy { it["stop_sequence"]?.toIntOrNull() ?: 0 } ?: return@mapNotNull null
        val arrives = stopTimes.map { secondsOf(it["arrival_time"].orEmpty()) }
        val departs = stopTimes.map { secondsOf(it["departure_time"].orEmpty()) }
        BusTrip(id, row["route_id"].orEmpty(), row["service_id"].orEmpty(), row["trip_headsign"].orEmpty(),
            stopTimes.map { it["stop_id"].orEmpty() }, arrives.mapIndexed { i, at -> if (at < 0) departs[i] else at }
                .toIntArray(), departs.mapIndexed { i, at -> if (at < 0) arrives[i] else at }.toIntArray())
    }.filter { trip -> trip.departs.all { it >= 0 } }
    val services = mutableMapOf<String, Service>()
    val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    // Each service's weekly pattern
    for (row in csvRows(files["calendar.txt"].orEmpty())) {
        services[row["service_id"].orEmpty()] = Service(weekdays.map { row[it] == "1" }.toBooleanArray(),
            LocalDate.parse(row["start_date"].orEmpty(), DAY), LocalDate.parse(row["end_date"].orEmpty(), DAY))
    }

    // Dates added (1) or taken away (2), making a service with no weekly pattern when needed
    for (row in csvRows(files["calendar_dates.txt"].orEmpty())) {
        val service = services.getOrPut(row["service_id"].orEmpty()) {
            Service(BooleanArray(7), LocalDate.MIN, LocalDate.MIN)
        }
        val date = LocalDate.parse(row["date"].orEmpty(), DAY)
        // Add or take away the date
        when (row["exception_type"]) {
            "1" -> service.added.add(date)
            "2" -> service.removed.add(date)
        }
    }
    val headways = csvRows(files["frequencies.txt"].orEmpty()).groupBy({ it["trip_id"].orEmpty() }) {
        Headway(secondsOf(it["start_time"].orEmpty()), secondsOf(it["end_time"].orEmpty()),
            it["headway_secs"]?.toIntOrNull() ?: 0)
    }.mapValues { (_, list) -> list.filter { it.every > 0 } }
    return Timetable(stops, routes, trips, services, headways)
}

// The distance between two spots in meters
fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val radians = Math.PI / 180
    val a = sin((lat2 - lat1) * radians / 2).let { it * it } +
        cos(lat1 * radians) * cos(lat2 * radians) * sin((lon2 - lon1) * radians / 2).let { it * it }
    return 2 * 6_371_000 * asin(sqrt(a))
}

// About how many minutes it takes to walk between two spots
fun walkMinutes(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    return ceil(meters(lat1, lon1, lat2, lon2) * DETOUR / WALK_METERS_PER_MINUTE).toInt()
}

// The stops within a 15-minute walk of a spot, with the walk in minutes
private fun stopsNear(table: Timetable, lat: Double, lon: Double): Map<String, Int> {
    return table.stops.values.associate { it.id to walkMinutes(lat, lon, it.lat, it.lon) }
        .filterValues { it <= MAX_WALK_MINUTES }
}

// Every bus ride from near one spot to near another leaving (from its stop) between two times: one per run, getting on
// and off where the whole trip, walks included, is shortest
fun busRides(table: Timetable, from: Pair<Double, Double>, to: Pair<Double, Double>, earliest: Long, latest: Long,
    zone: ZoneId = CAMPUS_ZONE): List<BusRide> {
    val nearFrom = stopsNear(table, from.first, from.second)
    val nearTo = stopsNear(table, to.first, to.second)
    val rides = mutableListOf<BusRide>()

    // Nothing to ride when either end has no stop nearby
    if (nearFrom.isEmpty() || nearTo.isEmpty()) {
        return rides
    }
    val firstDay = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate().minusDays(1)  // runs past midnight
    val lastDay = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate()
    var day = firstDay

    // Each service day the window touches
    while (!day.isAfter(lastDay)) {
        val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()

        // Each run on that day that passes a stop near the start
        for (trip in table.trips) {
            val route = table.routes[trip.route] ?: continue

            // Only runs that run that day and stop near the start
            if (table.services[trip.service]?.runs(day) != true || trip.stops.none { it in nearFrom }) {
                continue
            }
            val first = trip.departs.first()
            val offsets = table.headways[trip.id]?.flatMap { headway ->
                (headway.start until headway.end step headway.every).map { it - first }
            } ?: listOf(0)

            // Each time the run leaves (once, or every headway)
            for (offset in offsets) {
                var best: BusRide? = null

                // Each stop near the start, then each later stop near the end
                for (i in trip.stops.indices) {
                    val walkTo = nearFrom[trip.stops[i]] ?: continue
                    val departs = midnight + (trip.departs[i] + offset) * 1000L

                    // Only times in the window
                    if (departs < earliest || departs > latest) {
                        continue
                    }

                    // Each stop after it near the end
                    for (j in i + 1 until trip.stops.size) {
                        val walkFrom = nearTo[trip.stops[j]] ?: continue
                        val arrives = midnight + (trip.arrives[j] + offset) * 1000L
                        val ride = BusRide(trip, route, table.stops.getValue(trip.stops[i]),
                            table.stops.getValue(trip.stops[j]), departs, arrives, walkTo, walkFrom, j - i)

                        // Keep the shortest door to door
                        if (best == null || ride.there - ride.leave < best.there - best.leave) {
                            best = ride
                        }
                    }
                }
                best?.let { rides.add(it) }
            }
        }
        day = day.plusDays(1)
    }
    return rides
}

// The bus that lets you leave latest and still get there by a time, or null
fun latestBus(table: Timetable, from: Pair<Double, Double>, to: Pair<Double, Double>, arriveBy: Long,
    zone: ZoneId = CAMPUS_ZONE): BusRide? {
    return busRides(table, from, to, arriveBy - SEARCH_MILLIS, arriveBy, zone).filter { it.there <= arriveBy }
        .maxByOrNull { it.leave }
}

// The bus that gets you there soonest leaving after a time, or null
fun soonestBus(table: Timetable, from: Pair<Double, Double>, to: Pair<Double, Double>, leaveAfter: Long,
    zone: ZoneId = CAMPUS_ZONE): BusRide? {
    return busRides(table, from, to, leaveAfter, leaveAfter + SEARCH_MILLIS, zone).filter { it.leave >= leaveAfter }
        .minByOrNull { it.there }
}

// A spot as Maps reads it, "40.5012,-74.4521"
private fun spot(lat: Double, lon: Double): String {
    return "$lat,$lon"
}

// The steps for a bus ride in place of a walk: the walk to its stop (when there is one), the ride ("LX bus to
// Livingston Student Center", scheduled until live times come in) and the walk from its stop to where the old walk
// ended; first says the walk is where you leave
fun rideSteps(ride: BusRide, walk: JourneyStep, first: Boolean, zone: ZoneId = CAMPUS_ZONE): List<JourneyStep> {
    val minutes = ((ride.arrives - ride.departs) / 60_000).toInt()
    val bus = JourneyStep(STEP_BUS, "${ride.route.short} bus to ${ride.alight.name}",
        "$SCHEDULED · $minutes min · ${ride.stopCount} stop${if (ride.stopCount == 1) "" else "s"} · from " +
            ride.board.name, ride.route.short, if (ride.route.color.isBlank()) "" else "#${ride.route.color}",
        starts = ride.departs, ends = ride.arrives, fromName = spot(ride.board.lat, ride.board.lon),
        toName = spot(ride.alight.lat, ride.alight.lon), fromLat = ride.board.lat, fromLon = ride.board.lon,
        toLat = ride.alight.lat, toLon = ride.alight.lon, busTrip = ride.trip.id, boardStop = ride.board.id,
        alightStop = ride.alight.id, scheduled = ride.departs)
    val steps = mutableListOf<JourneyStep>()

    // The walk to the stop, when it's more than a step away
    if (ride.walkTo > 0) {
        steps.add(JourneyStep(STEP_WALK, if (first) "Leave · ${ride.walkTo} min walk" else "${ride.walkTo} min walk",
            "to ${ride.board.name}", starts = ride.leave, ends = ride.departs, fromName = walk.fromName,
            toName = spot(ride.board.lat, ride.board.lon), fromLat = walk.fromLat, fromLon = walk.fromLon,
            toLat = ride.board.lat, toLon = ride.board.lon))
    }
    steps.add(bus)

    // The walk from the stop, when there is one
    if (ride.walkFrom > 0) {
        steps.add(JourneyStep(STEP_WALK, "${ride.walkFrom} min walk", walk.under, starts = ride.arrives,
            ends = ride.there, fromName = spot(ride.alight.lat, ride.alight.lon), toName = walk.toName,
            fromLat = ride.alight.lat, fromLon = ride.alight.lon, toLat = walk.toLat, toLon = walk.toLon))
    }
    return steps
}

// Return true if a step is a ride (train, bus or subway)
private fun isRide(step: JourneyStep): Boolean {
    return step.kind == STEP_TRAIN || step.kind == STEP_BUS || step.kind == STEP_SUBWAY
}

// A journey with its leave and arrive times (and its arriving step) put right after its steps changed
private fun settled(journey: Journey, steps: List<JourneyStep>, zone: ZoneId): Journey {
    val arrive = steps.takeWhile { it.kind != STEP_ARRIVE }.lastOrNull()?.ends ?: journey.arrive  // not boarding
    val fixed = steps.map { step ->
        if (step.kind == STEP_ARRIVE) step.copy(under = "at ${Instant.ofEpochMilli(arrive).atZone(zone).format(CLOCK)}",
            starts = arrive, ends = arrive) else step
    }
    val leave = fixed.first().starts
    return journey.copy(steps = fixed, leave = leave, arrive = arrive, minutes = ((arrive - leave) / 60_000).toInt())
}

// A journey with one of its walks swapped for a campus bus, when a bus lets you leave later (a walk before any ride)
// or get there sooner (a walk after one) by at least 3 minutes; forced (the Bus travel choice) takes the best bus
// even when it saves nothing. Unchanged when there's no bus near both ends of any walk
fun withCampusBuses(journey: Journey, table: Timetable, forced: Boolean = false, zone: ZoneId = CAMPUS_ZONE): Journey {
    var bestIndex = -1
    var bestRide: BusRide? = null
    var bestGain = Long.MIN_VALUE

    // Each walk that knows where it starts and ends
    for ((index, step) in journey.steps.withIndex()) {
        // Only walks with both ends known
        if (step.kind != STEP_WALK || step.fromLat == 0.0 || step.toLat == 0.0) {
            continue
        }
        val from = Pair(step.fromLat, step.fromLon)
        val to = Pair(step.toLat, step.toLon)
        val afterRide = journey.steps.take(index).any { isRide(it) }
        val ride = (if (afterRide) soonestBus(table, from, to, step.starts, zone) else latestBus(table, from, to,
            step.ends, zone)) ?: continue
        val gain = if (afterRide) step.ends - ride.there else ride.leave - step.starts

        // Keep the walk the bus helps most
        if (gain > bestGain) {
            bestIndex = index
            bestRide = ride
            bestGain = gain
        }
    }
    val ride = bestRide ?: return journey

    // Only a bus that saves enough, unless you asked for the bus
    if (!forced && bestGain < MIN_GAIN_MINUTES * 60_000L) {
        return journey
    }
    val walk = journey.steps[bestIndex]
    val steps = journey.steps.take(bestIndex) + rideSteps(ride, walk, bestIndex == 0, zone) +
        journey.steps.drop(bestIndex + 1)
    return settled(journey, steps, zone)
}

// The other buses for a journey's campus bus, for the picker: the same stops, leaving up to 40 minutes either side,
// each as the whole journey with the walks around the bus moved to fit (ones that miss a train before or after it are
// left out); yours included, earliest first
fun campusChoices(journey: Journey, table: Timetable, zone: ZoneId = CAMPUS_ZONE): List<Journey> {
    val index = journey.steps.indexOfFirst { it.busTrip.isNotBlank() }

    // Only journeys with a campus bus
    if (index < 0) {
        return listOf(journey)
    }
    val bus = journey.steps[index]
    val start = if (index > 0 && journey.steps[index - 1].kind == STEP_WALK) index - 1 else index
    val end = if (index + 1 < journey.steps.size && journey.steps[index + 1].kind == STEP_WALK) index + 1 else index
    val walkTo = ((bus.starts - journey.steps[start].starts) / 60_000).toInt()
    val walkFrom = ((journey.steps[end].ends - bus.ends) / 60_000).toInt()
    val before = journey.steps.take(start).lastOrNull { it.kind != STEP_ARRIVE }?.ends
    val after = journey.steps.drop(end + 1).firstOrNull { isRide(it) }?.starts
    val choices = mutableListOf(journey)
    val board = table.stops[bus.boardStop] ?: return choices
    val alight = table.stops[bus.alightStop] ?: return choices
    val rides = busRides(table, Pair(board.lat, board.lon), Pair(alight.lat, alight.lon), bus.scheduled - CHOICE_MILLIS,
        bus.scheduled + CHOICE_MILLIS, zone).filter { it.board.id == board.id && it.alight.id == alight.id }

    // Each other bus between the same stops, kept when it still fits around the rest of the trip
    for (other in rides) {
        val ride = other.copy(walkTo = walkTo, walkFrom = walkFrom)

        // Skip yours, and ones that miss a ride before or after
        if (ride.departs == bus.scheduled || (before != null && ride.leave < before) ||
            (after != null && ride.there > after)) {
            continue
        }
        val walk = journey.steps[start].copy(toName = journey.steps[end].toName, toLat = journey.steps[end].toLat,
            toLon = journey.steps[end].toLon, under = journey.steps[end].under)
        val steps = journey.steps.take(start) + rideSteps(ride, walk, start == 0, zone) + journey.steps.drop(end + 1)
        choices.add(settled(journey, steps, zone))
    }
    return choices.sortedBy { it.leave }
}

// A Google bus step that's really a Rutgers bus (same route name, leaving a stop of the same name within 2 minutes)
// marked as one, so it gets live times and the bus picker
fun markCampusBus(step: JourneyStep, table: Timetable, zone: ZoneId = CAMPUS_ZONE): JourneyStep {
    val routes = table.routes.values.filter { it.short.equals(step.line, ignoreCase = true) }.map { it.id }.toSet()
    val stopName = step.under.substringAfter("from ", "").substringBefore(" · ").trim()

    // Only buses on a Rutgers route
    if (step.kind != STEP_BUS || step.busTrip.isNotBlank() || routes.isEmpty()) {
        return step
    }
    val day = Instant.ofEpochMilli(step.starts).atZone(zone).toLocalDate()
    val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()

    // Each run of the route that day, for the one leaving that stop then
    for (trip in table.trips.filter { it.route in routes && table.services[it.service]?.runs(day) == true }) {
        val at = trip.stops.indices.firstOrNull { i ->
            table.stops[trip.stops[i]]?.name.equals(stopName, ignoreCase = true) &&
                kotlin.math.abs(midnight + trip.departs[i] * 1000L - step.starts) <= 2 * 60_000L
        } ?: continue
        val stop = table.stops.getValue(trip.stops[at])
        val alight = trip.stops.drop(at + 1).firstOrNull { id ->
            table.stops[id]?.let { meters(it.lat, it.lon, step.toLat, step.toLon) < 150 } == true
        }.orEmpty()
        return step.copy(busTrip = trip.id, boardStop = stop.id, alightStop = alight, scheduled = step.starts)
    }
    return step
}

// The minutes a bus is late at its stop ("Live · 2 min late", "Live · on time", "Live · 1 min early"), from its
// predicted time and its scheduled one
fun liveText(predicted: Long, scheduled: Long): String {
    val late = ((predicted - scheduled) / 60_000.0).roundToInt()
    // The words for early, on time or late
    return when {
        late > 0 -> "Live · $late min late"
        late < 0 -> "Live · ${-late} min early"
        else -> "Live · on time"
    }
}

// A journey's campus buses with their live state from the predictions (trip id → stop id → predicted times): the
// line under says how late it is in place of "Scheduled"; a bus with no prediction stays scheduled
fun withCampusLive(journey: Journey, predictions: Map<String, Map<String, List<Long>>>): Journey {
    return journey.copy(steps = journey.steps.map { step ->
        val predicted = predictions[step.busTrip]?.get(step.boardStop)
            ?.filter { kotlin.math.abs(it - step.scheduled) <= 30 * 60_000L }?.minByOrNull {
                kotlin.math.abs(it - step.scheduled)
            }
        // The line under it without its old state ("Scheduled" or "Live · 2 min late")
        val rest = when {
            step.under.startsWith("$SCHEDULED · ") -> step.under.removePrefix("$SCHEDULED · ")
            step.under.startsWith("Live · ") -> step.under.split(" · ").drop(2).joinToString(" · ")
            else -> step.under
        }
        // Only campus buses with a prediction change
        if (step.busTrip.isBlank() || predicted == null) step else step.copy(under = "${liveText(predicted,
            step.scheduled)} · $rest")
    })
}

// Read a protobuf varint from a position, returning it and where the next field starts
private fun varint(data: ByteArray, start: Int): Pair<Long, Int> {
    var result = 0L
    var shift = 0
    var index = start

    // Seven bits at a time until the top bit is clear
    while (true) {
        val byte = data[index++].toInt() and 0xFF
        result = result or ((byte and 0x7F).toLong() shl shift)
        shift += 7

        // The last byte of the number
        if (byte < 0x80) {
            return Pair(result, index)
        }
    }
}

// A protobuf message's fields as (field number, value): numbers for varints, byte arrays for nested messages and
// text, skipped for fixed-width ones
private fun protoFields(data: ByteArray): List<Pair<Int, Any>> {
    val fields = mutableListOf<Pair<Int, Any>>()
    var index = 0

    // Each field in turn
    while (index < data.size) {
        val (key, next) = varint(data, index)
        val number = (key shr 3).toInt()
        index = next
        // Read the value by its wire type
        when ((key and 7).toInt()) {
            0 -> varint(data, index).let { (value, after) -> fields.add(Pair(number, value)); index = after }
            2 -> varint(data, index).let { (length, after) ->
                fields.add(Pair(number, data.copyOfRange(after, after + length.toInt())))
                index = after + length.toInt()
            }
            1 -> index += 8
            5 -> index += 4
            else -> return fields  // a type GTFS-realtime doesn't use: stop reading
        }
    }
    return fields
}

// The nested messages (or text) of one field number
private fun messages(data: ByteArray, number: Int): List<ByteArray> {
    return protoFields(data).filter { it.first == number }.mapNotNull { it.second as? ByteArray }
}

// The number of one field (null when missing)
private fun numberOf(data: ByteArray, number: Int): Long? {
    return protoFields(data).firstOrNull { it.first == number }?.second as? Long
}

// Read a GTFS-realtime trip updates feed into the predicted times (milliseconds) at each stop of each run: trip id →
// stop id → times (a run that repeats can have several); departures where given, else arrivals
fun parsePredictions(feed: ByteArray): Map<String, Map<String, List<Long>>> {
    val found = mutableMapOf<String, MutableMap<String, MutableList<Long>>>()

    // Each entity with a trip update
    for (entity in messages(feed, 2)) {
        val update = messages(entity, 3).firstOrNull() ?: continue
        val trip = messages(update, 1).firstOrNull()?.let { messages(it, 1).firstOrNull() }?.decodeToString()
            ?: continue

        // Each stop's predicted time
        for (stopUpdate in messages(update, 2)) {
            val stop = messages(stopUpdate, 4).firstOrNull()?.decodeToString() ?: continue
            val event = messages(stopUpdate, 3).firstOrNull() ?: messages(stopUpdate, 2).firstOrNull() ?: continue
            val time = numberOf(event, 2) ?: continue
            found.getOrPut(trip) { mutableMapOf() }.getOrPut(stop) { mutableListOf() }.add(time * 1000)
        }
    }
    return found
}

// Download a URL's bytes (null on any failure); this waits on the network, so call it off the main thread
private fun download(url: String): ByteArray? {
    // Any network problem means nothing
    return try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.inputStream.use { it.readBytes() }.also { connection.disconnect() }
    } catch (error: Exception) {  // no internet, or the feed is down
        Log.w("Datebook", "campus bus download failed: ${error.message}")
        null
    }
}

// The GTFS files Datebook keeps (not the 40 MB of route shapes) out of the zip, by name
fun unzipTimetable(zip: InputStream): Map<String, String> {
    val files = mutableMapOf<String, String>()
    ZipInputStream(zip).use { stream ->
        var entry = stream.nextEntry

        // Each file in the zip
        while (entry != null) {
            val name = entry.name.substringAfterLast("/")

            // Only the files the timetable needs
            if (name in KEPT_FILES) {
                val bytes = ByteArrayOutputStream()
                stream.copyTo(bytes)
                files[name] = bytes.toString(Charsets.UTF_8.name())
            }
            entry = stream.nextEntry
        }
    }
    return files
}

// Keeps the Rutgers timetable on the phone (fetched again weekly) and live times for half a minute
object CampusBuses {
    private var table: Timetable? = null
    private var live: Pair<Long, Map<String, Map<String, List<Long>>>>? = null

    // The timetable: from memory, else the phone's copy, fetched first when it's missing or a week old; null when it
    // can't be had (call it off the main thread)
    @Synchronized
    fun timetable(context: Context): Timetable? {
        val folder = File(context.filesDir, "rutgers-buses")
        val stamp = File(folder, "fetched")
        val old = !stamp.exists() || System.currentTimeMillis() - stamp.lastModified() > REFRESH_MILLIS

        // Fetch it again when it's missing or old (keeping the old copy if the download fails)
        if (old) {
            download(TIMETABLE_URL)?.let { zip ->
                val files = runCatching { unzipTimetable(zip.inputStream()) }.getOrNull()

                // Only a complete timetable replaces the old one
                if (files != null && "stop_times.txt" in files && "trips.txt" in files) {
                    folder.mkdirs()
                    files.forEach { (name, text) -> File(folder, name).writeText(text) }
                    stamp.writeText(System.currentTimeMillis().toString())
                    table = null
                }
            }
        }

        // Read the phone's copy once
        if (table == null && File(folder, "trips.txt").exists()) {
            table = runCatching {
                parseTimetable(KEPT_FILES.associateWith { File(folder, it).takeIf { file -> file.exists() }
                    ?.readText().orEmpty() })
            }.getOrNull()
        }
        return table
    }

    // The live predicted times, fetched when the last ones are over 30 seconds old; empty when the feed can't be read
    @Synchronized
    fun predictions(): Map<String, Map<String, List<Long>>> {
        val now = System.currentTimeMillis()
        val kept = live

        // Use the last ones while they're fresh
        if (kept != null && now - kept.first < LIVE_MILLIS) {
            return kept.second
        }
        val found = download(LIVE_URL)?.let { runCatching { parsePredictions(it) }.getOrNull() } ?: emptyMap()
        live = Pair(now, found)
        return found
    }
}
