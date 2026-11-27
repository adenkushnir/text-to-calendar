// Leave-by alerts: for each timed calendar event with a place, work out the travel time and, 10 minutes before you
// need to leave (travel time plus 5 spare minutes before the start), post "Leave by 6:37 PM" with Open in Maps and
// buttons to switch the travel mode; Deliberate's rules can set the travel time, extra early minutes, an earlier
// alert, the mode and things to bring, or turn an event's alerts off, and it can pause the alerts. Each plan can have
// its own mode and early minutes; flights aim to get you to the airport 60 minutes early (2 hours abroad); a plan
// with a picked NJ Transit train leaves by the train (its time less getting to the station)
package io.github.adenkushnir.datebook.leaveby
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.deliberate.EventEffects
import io.github.adenkushnir.datebook.nestor.postNotification
import io.github.adenkushnir.datebook.deliberate.PAUSE_LEAVE_BY
import io.github.adenkushnir.datebook.deliberate.effectsFor
import io.github.adenkushnir.datebook.deliberate.mergeBring
import io.github.adenkushnir.datebook.deliberate.planRuleReminders
import io.github.adenkushnir.datebook.extras.BRING_LABEL
import io.github.adenkushnir.datebook.extras.bringOf
import io.github.adenkushnir.datebook.flights.FlightInfo
import io.github.adenkushnir.datebook.flights.airportName
import io.github.adenkushnir.datebook.flights.boardingLead
import io.github.adenkushnir.datebook.flights.boardingMinutes
import io.github.adenkushnir.datebook.pipeline.zoneOf
import io.github.adenkushnir.datebook.flights.flightEarlyMinutes
import io.github.adenkushnir.datebook.flights.flightOfTitle
import io.github.adenkushnir.datebook.journeys.Journey
import io.github.adenkushnir.datebook.journeys.JourneyStore
import io.github.adenkushnir.datebook.journeys.ORDER_MINUTES
import io.github.adenkushnir.datebook.journeys.PICKUP_MINUTES
import io.github.adenkushnir.datebook.journeys.planJourney
import io.github.adenkushnir.datebook.places.TRAVEL_BIKE
import io.github.adenkushnir.datebook.places.TRAVEL_BUS
import io.github.adenkushnir.datebook.places.TRAVEL_UBER
import io.github.adenkushnir.datebook.places.uberIntent
import io.github.adenkushnir.datebook.places.TRAVEL_DRIVE
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.TRAVEL_WALK
import io.github.adenkushnir.datebook.places.mapsIntent
import io.github.adenkushnir.datebook.places.travelMinutes
import io.github.adenkushnir.datebook.places.travelMode
import io.github.adenkushnir.datebook.prompts.canNotify
import io.github.adenkushnir.datebook.trains.Station
import io.github.adenkushnir.datebook.trains.TrainPick
import io.github.adenkushnir.datebook.trains.TrainStore
import io.github.adenkushnir.datebook.trains.arriveBefore
import io.github.adenkushnir.datebook.trains.rideLine
import io.github.adenkushnir.datebook.trains.stillMakesIt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
const val LEAVE_BY_CHANNEL = "leave_by"
const val BUFFER_MINUTES = 5
const val HEADS_UP_MINUTES = 10
const val NOTE_SWAP = "swap"  // a plan's train stopped working and another took its place
const val NOTE_NONE = "none"  // no train gets you there any more
const val NETWORK_NONE = 0  // how a leave plan may look things up: only what's remembered (for Nestor's snapshot)
const val NETWORK_IF_NEEDED = 1  // what's remembered, else ask Google (booking the alerts)
const val NETWORK_FRESH = 2  // ask Google again, else what's remembered (sending an alert)
private const val WALK_LIMIT_MINUTES = 20  // a longer walk to the station is timed as a drive
private const val MINUTE = 60_000L
private const val LOOK_AHEAD_MILLIS = 4 * 60 * MINUTE
private const val PLAN_WORK = "datebook-leave-by-plan"
private const val ALERT_WORK = "datebook-leave-by-"
private const val EXTRA_KEY = "key"
private const val EXTRA_EVENT = "event"
private const val EXTRA_BEGIN = "begin"
private const val EXTRA_MODE = "mode"
private const val ACTION_MODE = "io.github.adenkushnir.datebook.LEAVE_BY_MODE"
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// The key for one time of one event, like "1794600000000|dinner", so a plan on its card and the same event on the
// calendar share a travel mode
fun eventKey(title: String, begin: Long): String {
    return "$begin|${title.trim().lowercase()}"
}

// Remembers each event's travel mode, its last travel time, when its alert is booked, and whether it was sent; entries
// are dropped a day after the event
class LeaveByStore(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-leave-by", Context.MODE_PRIVATE)

    // Return the travel mode chosen for an event, or null to use the default
    fun mode(key: String): String? {
        return prefs.getString("mode:$key", null)
    }

    // Choose an event's travel mode, forgetting its old travel time
    fun setMode(key: String, mode: String) {
        prefs.edit().putString("mode:$key", mode).remove("minutes:$key").apply()
    }

    // Return the last travel time worked out for an event by a mode, in minutes, or null
    fun minutes(key: String, mode: String): Int? {
        val sameMode = prefs.getString("minutesMode:$key", mode) == mode
        return if (prefs.contains("minutes:$key") && sameMode) prefs.getInt("minutes:$key", 0) else null
    }

    // Remember an event's travel time by a mode
    fun setMinutes(key: String, minutes: Int, mode: String) {
        prefs.edit().putInt("minutes:$key", minutes).putString("minutesMode:$key", mode).apply()
    }

    // Return how many minutes before an Uber leaves you're reminded to order it, chosen for that event, or null for
    // the usual 10
    fun orderMinutes(key: String): Int? {
        return if (prefs.contains("order:$key")) prefs.getInt("order:$key", ORDER_MINUTES) else null
    }

    // Choose how many minutes before an event's Uber leaves to be reminded to order it (null goes back to 10)
    fun setOrderMinutes(key: String, minutes: Int?) {
        // Forget the choice, or keep the new one
        if (minutes == null) {
            prefs.edit().remove("order:$key").apply()
        } else {
            prefs.edit().putInt("order:$key", minutes).apply()
        }
    }

    // Return how early you mean to be at an event, chosen for it alone, or null to follow the rules and defaults
    fun early(key: String): Int? {
        return if (prefs.contains("early:$key")) prefs.getInt("early:$key", 0) else null
    }

    // Choose how early to be at an event (null goes back to the rules and defaults)
    fun setEarly(key: String, minutes: Int?) {
        val editor = prefs.edit()

        // Forget the choice, or keep the new one
        if (minutes == null) {
            editor.remove("early:$key")
        } else {
            editor.putInt("early:$key", minutes)
        }
        editor.apply()
    }

    // Put an event's travel mode back to what it was (null for the default), for Undo
    fun putMode(key: String, mode: String?) {
        // Forget the choice, or set it again
        if (mode == null) {
            prefs.edit().remove("mode:$key").remove("minutes:$key").apply()
        } else {
            setMode(key, mode)
        }
    }

    // Return the note about an event's train, as its kind (NOTE_SWAP or NOTE_NONE) and words, or null
    fun trainNote(key: String): Pair<String, String>? {
        val saved = prefs.getString("note:$key", null) ?: return null
        return Pair(saved.substringBefore("|"), saved.substringAfter("|"))
    }

    // Remember a note about an event's train (a blank kind forgets it)
    fun setTrainNote(key: String, kind: String, text: String) {
        // Forget it, or keep the new one
        if (kind.isBlank()) {
            prefs.edit().remove("note:$key").apply()
        } else {
            prefs.edit().putString("note:$key", "$kind|$text").apply()
        }
    }

    // Return the last trip worked out to an event's train station, as the station's code, the mode and the minutes
    fun stationTrip(key: String): Triple<String, String, Int>? {
        val parts = prefs.getString("station:$key", null)?.split("|") ?: return null
        val minutes = parts.getOrNull(2)?.toIntOrNull() ?: return null
        return Triple(parts[0], parts[1], minutes)
    }

    // Remember the trip to an event's train station
    fun setStationTrip(key: String, code: String, mode: String, minutes: Int) {
        prefs.edit().putString("station:$key", "$code|$mode|$minutes").apply()
    }

    // Return when an event's alert is booked for, or null
    fun bookedFor(key: String): Long? {
        return if (prefs.contains("booked:$key")) prefs.getLong("booked:$key", 0) else null
    }

    // Remember when an event's alert is booked for
    fun setBookedFor(key: String, millis: Long) {
        prefs.edit().putLong("booked:$key", millis).apply()
    }

    // Return true once an event's alert was sent
    fun alerted(key: String): Boolean {
        return prefs.getBoolean("alerted:$key", false)
    }

    // Remember that an event's alert was sent
    fun setAlerted(key: String) {
        prefs.edit().putBoolean("alerted:$key", true).apply()
    }

    // Drop what's remembered about events that started over a day ago
    fun prune(nowMillis: Long) {
        val editor = prefs.edit()

        // Check each remembered entry's event time
        for (name in prefs.all.keys) {
            val begin = name.substringAfter(":").substringBefore("|").toLongOrNull() ?: continue

            // Drop entries for events that started over a day ago
            if (begin < nowMillis - 24 * 60 * MINUTE) {
                editor.remove(name)
            }
        }
        editor.apply()
    }
}

// When to leave: the start, less the travel time, 5 spare minutes and any minutes a rule says to arrive early
fun leaveTime(begin: Long, minutes: Int, early: Int = 0): Long {
    return begin - (minutes + BUFFER_MINUTES + early) * MINUTE
}

// When to send the alert: 10 minutes before the leave time, or earlier when a rule says so
fun alertTime(begin: Long, minutes: Int, early: Int = 0, earlier: Int = 0): Long {
    return leaveTime(begin, minutes, early) - (HEADS_UP_MINUTES + earlier) * MINUTE
}

// The events that get leave-by alerts: timed ones with a place (flights included), starting within the next 4 hours
fun leaveByCandidates(events: List<CalendarEvent>, nowMillis: Long): List<CalendarEvent> {
    return events.filter {
        !it.allDay && it.location.isNotBlank() && it.begin > nowMillis && it.begin <= nowMillis + LOOK_AHEAD_MILLIS
    }
}

// The trip in words, like "18 min drive", "35 min by transit", "12 min walk" or "20 min bike ride"
fun tripText(minutes: Int, mode: String): String {
    // Pick the words for the mode
    return when (travelMode(mode).id) {
        TRAVEL_TRANSIT -> "$minutes min by transit"
        TRAVEL_WALK -> "$minutes min walk"
        TRAVEL_BIKE -> "$minutes min bike ride"
        TRAVEL_BUS -> "$minutes min by campus bus"
        TRAVEL_UBER -> "$minutes min Uber, pickup included"
        else -> "$minutes min drive"
    }
}

// The alert's title, like "Leave by 6:37 PM", or "Leave now" when the alert comes after the leave time (an event
// added or moved at the last minute)
fun leaveByTitle(begin: Long, minutes: Int, zone: ZoneId, nowMillis: Long = System.currentTimeMillis(),
    early: Int = 0): String {
    val leave = leaveTime(begin, minutes, early)
    return if (nowMillis > leave) "Leave now" else "Leave by ${Instant.ofEpochMilli(leave).atZone(zone).format(CLOCK)}"
}

// The two other modes offered as buttons on the alert (there's room for two next to Open in Maps)
fun otherModes(mode: String): List<String> {
    return listOf(TRAVEL_DRIVE, TRAVEL_TRANSIT, TRAVEL_WALK).filter { it != travelMode(mode).id }.take(2)
}

// Work out an event's travel time now, from where the phone is (or home), by its mode, arriving when you mean to be
// there
private fun freshMinutes(context: Context, event: CalendarEvent, mode: String, early: Int = 0): Int? {
    val graph = AppGraph.get(context)
    return travelMinutes(context, graph.mapsKeyStore.load(), graph.travelSettings.home(), event.location, mode,
        Instant.ofEpochMilli(event.begin - early * MINUTE))
}

// How to get to an event: when to leave and when the alert goes, the trip in words (like "18 min drive" or "12 min
// walk to New Brunswick"), the event's travel mode and how the timed part of the trip goes, its minutes, how early you
// mean to be there, the train to take (null without one) and the station it leaves from, a note about the train ("NJT
// 3863 was cancelled"), whether no train gets you there any more, and for flights the flight's last-known details
data class LeavePlan(
    val begin: Long,
    val leaveAt: Long,
    val alertAt: Long,
    val travel: String,
    val mode: String,
    val tripMode: String,
    val minutes: Int,
    val early: Int,
    val train: TrainPick? = null,
    val station: Station? = null,
    val trainNote: String = "",
    val noTrain: Boolean = false,
    val isFlight: Boolean = false,
    val flight: FlightInfo? = null,
    val boarding: Int = 0,
    val boardingReal: Boolean = false,
)

// How long before an event's start you mean to be there: the early minutes, plus for a flight the time boarding
// starts before departure (its real time when known, else the usual guess; a flight's early minutes count from
// boarding)
fun leadMinutes(early: Int, isFlight: Boolean, flight: FlightInfo?, boarding: Int? = null): Int {
    return early + if (isFlight) boarding ?: boardingMinutes(flight) else 0
}

// How long before a flight's departure boarding starts, and whether that's its real time (from a boarding pass, an
// email or you) rather than the usual 35 minutes (45 abroad)
fun boardingFor(context: Context, title: String, begin: Long, flight: FlightInfo?): Pair<Int, Boolean> {
    val number = flightOfTitle(title) ?: return Pair(boardingMinutes(flight), false)
    val date = Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val zone = zoneOf(flight?.fromZone) ?: ZoneId.systemDefault()
    val real = AppGraph.get(context).flightStore.boarding(number, date)?.let { boardingLead(begin, it.first, zone) }
    return if (real != null) Pair(real, true) else Pair(boardingMinutes(flight), false)
}

// How early to be there: chosen for this plan, else a rule's, else a flight's default, else right on time
fun chooseEarly(plan: Int?, rule: Int?, isFlight: Boolean, flight: FlightInfo?): Int {
    return plan ?: rule ?: if (isFlight) flightEarlyMinutes(flight) else 0
}

// When to leave to catch a train: when it really leaves (with its delay), less getting to the station and 5 spare
// minutes
fun trainLeaveTime(pick: TrainPick, toStationMinutes: Int): Long {
    return pick.departs + pick.secondsLate * 1000L - (toStationMinutes + BUFFER_MINUTES) * MINUTE
}

// How to get to the station: walking when it takes up to 20 minutes (or when there's no drive time), else driving
fun stationMode(walkMinutes: Int?, driveMinutes: Int?): String {
    return if (walkMinutes != null && (walkMinutes <= WALK_LIMIT_MINUTES || driveMinutes == null)) TRAVEL_WALK
        else TRAVEL_DRIVE
}

// Whether an event is a flight ("Flight B61234 EWR → BOS"), and its last-known details (null when never looked up)
fun flightFor(context: Context, title: String, begin: Long): Pair<Boolean, FlightInfo?> {
    val number = flightOfTitle(title) ?: return Pair(false, null)
    val date = Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    return Pair(true, AppGraph.get(context).flightStore.info(number, date))
}

// How long before an event's start you mean to be there (see leadMinutes): what trains and leave times aim for
suspend fun leadFor(context: Context, title: String, location: String, begin: Long): Int {
    val (isFlight, flight) = flightFor(context, title, begin)
    return leadMinutes(earlyFor(context, title, location, begin), isFlight, flight,
        boardingFor(context, title, begin, flight).first)
}

// How early you mean to be at an event (for a flight, before boarding): this plan's choice, a rule, a flight's
// default, or on time
suspend fun earlyFor(context: Context, title: String, location: String, begin: Long): Int {
    val rules = AppGraph.get(context).deliberateDao.rules()
    val (isFlight, flight) = flightFor(context, title, begin)
    return chooseEarly(LeaveByStore(context).early(eventKey(title, begin)), effectsFor(rules, title, location).early,
        isFlight, flight)
}

// The trip to a train's station: the mode and minutes, remembered or (as allowed) asked of Google, walking when it's
// short and driving otherwise; null when it can't be worked out
private fun stationTrip(context: Context, key: String, station: Station, pick: TrainPick, network: Int):
    Pair<String, Int>? {
    val store = LeaveByStore(context)
    val saved = store.stationTrip(key)?.takeIf { it.first == station.code }?.let { Pair(it.second, it.third) }

    // Use what's remembered when that's allowed or enough
    if (network == NETWORK_NONE || (network == NETWORK_IF_NEEDED && saved != null)) {
        return saved
    }
    val graph = AppGraph.get(context)
    val mapsKey = graph.mapsKeyStore.load()
    val home = graph.travelSettings.home()
    val spot = Pair(station.lat, station.lng)
    val departs = Instant.ofEpochMilli(pick.departs)
    val walk = travelMinutes(context, mapsKey, home, station.name, TRAVEL_WALK, departs, spot)
    val drive = if (walk == null || walk > WALK_LIMIT_MINUTES) travelMinutes(context, mapsKey, home, station.name,
        TRAVEL_DRIVE, departs, spot) else null
    val mode = stationMode(walk, drive)
    val minutes = (if (mode == TRAVEL_WALK) walk else drive) ?: return saved  // no route: keep the last one
    store.setStationTrip(key, station.code, mode, minutes)
    return Pair(mode, minutes)
}

// A plan's journey with a campus bus for its alert: the one kept from its card when it's for the same mode and still
// gets there in time, else (for the Bus choice, when the network allows) planned again; null when it has no campus
// bus
private fun campusJourney(context: Context, key: String, event: CalendarEvent, mode: String, early: Int,
    network: Int): Journey? {
    val graph = AppGraph.get(context)
    val store = JourneyStore(context)
    val by = event.begin - early * MINUTE
    val now = System.currentTimeMillis()
    val kept = store.journey(key)?.first?.takeIf { it.mode == mode && it.arrive <= by && it.leave > now }
    val journey = kept ?: if (mode == TRAVEL_BUS && network != NETWORK_NONE) {
        planJourney(context, graph.mapsKeyStore.load(), graph.travelSettings.home(), event.location,
            event.location.substringBefore(","), mode, null, by)?.also { store.save(key, it, now) }
    } else {
        null
    }
    return journey?.takeIf { found -> found.steps.any { it.busTrip.isNotBlank() } }
}

// Plan the trip to an event: by its picked train when it goes by transit and the train still makes it, by its campus
// bus when its journey has one, else by Google's door-to-door time (driving when no train makes it any more; an Uber
// adds 5 minutes for pickup and reminds you to order it, 10 minutes before unless you said); null when there's no
// travel time
suspend fun leavePlan(context: Context, event: CalendarEvent, effects: EventEffects, network: Int): LeavePlan? {
    val graph = AppGraph.get(context)
    val store = LeaveByStore(context)
    val key = eventKey(event.title, event.begin)
    val (isFlight, flight) = flightFor(context, event.title, event.begin)
    val (boards, real) = if (isFlight) boardingFor(context, event.title, event.begin, flight) else Pair(0, false)
    val early = leadMinutes(chooseEarly(store.early(key), effects.early, isFlight, flight), isFlight, flight, boards)
    val boarding = boards
    val mode = store.mode(key) ?: effects.mode ?: graph.travelSettings.mode()
    val note = store.trainNote(key)
    val pick = TrainStore(context).pick(key)?.takeIf {
        mode == TRAVEL_TRANSIT && stillMakesIt(it, event.begin, arriveBefore(early))
    }
    val station = pick?.let { train -> graph.trainSettings.stations().firstOrNull { it.code == train.fromCode } }
    val toStation = if (pick != null && station != null) stationTrip(context, key, station, pick, network) else null

    // By train: leave in time to get to its station
    if (pick != null && station != null && toStation != null) {
        val leaveAt = trainLeaveTime(pick, toStation.second)
        return LeavePlan(event.begin, leaveAt, leaveAt - (HEADS_UP_MINUTES + effects.alertEarlier) * MINUTE,
            "${tripText(toStation.second, toStation.first)} to ${station.name}", mode, toStation.first,
            toStation.second, early, pick, station, note?.takeIf { it.first == NOTE_SWAP }?.second.orEmpty(),
            isFlight = isFlight, flight = flight, boarding = boarding, boardingReal = real)
    }
    val campus = if (mode == TRAVEL_BUS || mode == TRAVEL_TRANSIT) campusJourney(context, key, event, mode, early,
        network) else null

    // By campus bus: leave when its journey does
    if (campus != null) {
        val bus = campus.steps.first { it.busTrip.isNotBlank() }
        val at = Instant.ofEpochMilli(bus.starts).atZone(ZoneId.systemDefault()).format(CLOCK)
        return LeavePlan(event.begin, campus.leave, campus.leave - (HEADS_UP_MINUTES + effects.alertEarlier) * MINUTE,
            "🚌 ${bus.line} bus $at from ${bus.under.substringAfterLast("from ")}", mode, mode, campus.minutes, early,
            isFlight = isFlight, flight = flight, boarding = boarding, boardingReal = real)
    }
    val noTrain = mode == TRAVEL_TRANSIT && note?.first == NOTE_NONE
    val tripMode = if (noTrain) TRAVEL_DRIVE else mode
    val saved = store.minutes(key, tripMode)
    // A rule's time, else what's remembered or Google's, as the caller allows
    val minutes = effects.minutes ?: when (network) {
        NETWORK_NONE -> saved
        NETWORK_IF_NEEDED -> saved ?: freshMinutes(context, event, tripMode, early)
        else -> freshMinutes(context, event, tripMode, early) ?: saved
    } ?: return null
    store.setMinutes(key, minutes, tripMode)
    val uber = tripMode == TRAVEL_UBER
    val total = if (uber) minutes + PICKUP_MINUTES else minutes
    val leaveAt = leaveTime(event.begin, total, early)
    val alertAt = if (uber) leaveAt - (store.orderMinutes(key) ?: ORDER_MINUTES) * MINUTE
        else alertTime(event.begin, total, early, effects.alertEarlier)
    return LeavePlan(event.begin, leaveAt, alertAt, tripText(total, tripMode), mode, tripMode,
        total, early, trainNote = if (noTrain) note?.second.orEmpty() else "", noTrain = noTrain,
        isFlight = isFlight, flight = flight, boarding = boarding, boardingReal = real)
}

// What a leave-by alert says: its title, the line under it, and the lines below that
data class AlertText(val title: String, val text: String, val lines: List<String>)

// A small picture for the way you get to the station
private fun tripIcon(mode: String): String {
    return if (mode == TRAVEL_WALK) "🚶" else "🚗"
}

// A leave-by alert's words. Flights put the airport first ("Leave by 4:35 PM for your flight" / "Be at Newark
// Airport by 5:35 PM · B61234 departs 6:05 PM" / how to get there); other events keep "Leave by 6:37 PM" / "Dinner
// · 18 min drive" with the train under it; a train that stopped working or no train at all is said first
fun alertText(title: String, location: String, plan: LeavePlan, nowMillis: Long, zone: ZoneId): AlertText {
    val clock = { millis: Long -> Instant.ofEpochMilli(millis).atZone(zone).format(CLOCK) }
    val leave = if (nowMillis > plan.leaveAt) "Leave now" else "Leave by ${clock(plan.leaveAt)}"
    val train = plan.train
    val ride = train?.let { "🚆 ${rideLine(it)}" }

    // An Uber: order it now, with when it should leave
    if (plan.tripMode == TRAVEL_UBER) {
        val name = if (plan.isFlight) "your flight" else title.ifBlank { "Event" }
        val car = if (nowMillis > plan.leaveAt) "now" else "for ${clock(plan.leaveAt)}"
        return AlertText("Order your Uber $car", "$name · ${plan.travel}", emptyList())
    }

    // Other events: the trip, then the train (with what happened to the last one), or why there's no train
    if (!plan.isFlight) {
        val name = title.ifBlank { "Event" }
        // The train under the trip (with what happened to the last one), or why there is none
        val lines = when {
            ride != null && plan.trainNote.isNotBlank() -> listOf("$ride (${plan.trainNote})")
            ride != null -> listOf(ride)
            plan.noTrain -> listOf("🚆 ${plan.trainNote}, and no other train gets you there in time")
            else -> emptyList()
        }
        return AlertText(leave, "$name · ${plan.travel}", lines)
    }
    val airport = plan.flight?.let { airportName(it) } ?: location.ifBlank { "the airport" }
    val number = plan.flight?.number ?: flightOfTitle(title).orEmpty()
    val beThere = "Be at $airport by ${clock(plan.begin - plan.early * MINUTE)}"
    val about = if (plan.boardingReal) "" else "about "
    val departs = if (plan.boarding > 0) "boarding $about${clock(plan.begin - plan.boarding * MINUTE)}"
        else "$number departs ${clock(plan.begin)}".trim()

    // Flights: a swapped train, no train, by train, or Google's door-to-door time
    return when {
        train != null && plan.trainNote.isNotBlank() -> AlertText("$leave for your flight",
            "${plan.trainNote} · take NJT ${train.trainId} at ${clock(train.departs + train.secondsLate * 1000L)}",
            listOf("${tripIcon(plan.tripMode)} ${plan.travel}", ride!!, "✈️ $beThere"))
        train != null -> AlertText("$leave for your flight", "$beThere · $departs",
            listOf("${tripIcon(plan.tripMode)} ${plan.travel}", ride!!))
        plan.noTrain -> AlertText("$leave for your flight",
            "No train gets you to $airport by ${clock(plan.begin - arriveBefore(plan.early))}",
            listOf("🚗 ${plan.travel} to ${location.ifBlank { airport }}", plan.trainNote))
        else -> AlertText("$leave for your flight", "$beThere · $departs", listOf(plan.travel))
    }
}

// Update a leave-by alert that went out already (its train changed): find the event again and post it with a fresh
// plan
suspend fun reAlert(context: Context, key: String) {
    val graph = AppGraph.get(context)
    val now = System.currentTimeMillis()
    val events = calendarEvents(context, now, now + LOOK_AHEAD_MILLIS, graph.calendarSwitches.switchedOff())
    val event = events.firstOrNull { eventKey(it.title, it.begin) == key } ?: return  // moved, deleted or started
    val effects = effectsFor(graph.deliberateDao.rules(), event.title, event.location)

    // Not when its alerts are off or paused
    if (effects.quiet || graph.deliberateSettings.paused(PAUSE_LEAVE_BY, now)) {
        return
    }
    val plan = leavePlan(context, event, effects, NETWORK_FRESH) ?: return
    showLeaveBy(context, event, plan, effects)
    Log.i("Datebook", "leave-by alert updated: ${event.title} · ${plan.travel}")
}

// Post (or update) an event's leave-by alert from its plan (see alertText), with what to bring when the event's notes
// or Deliberate's rules say; Open in Maps leads to the train's station when going by train
fun showLeaveBy(context: Context, event: CalendarEvent, plan: LeavePlan, effects: EventEffects = EventEffects()) {
    // Nothing can be shown without permission to notify
    if (!canNotify(context)) {
        return
    }
    val channel = NotificationChannel(LEAVE_BY_CHANNEL, "Leave-by alerts", NotificationManager.IMPORTANCE_HIGH)
    channel.description = "When to leave for events with a place"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val key = eventKey(event.title, event.begin)
    val id = notificationId(key)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val openHome = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val station = plan.station
    val mapsTo = if (plan.train != null && station != null) "${station.lat},${station.lng}" else event.location
    val openMaps = mapsIntent(mapsTo, plan.tripMode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val words = alertText(event.title, event.location, plan, System.currentTimeMillis(), ZoneId.systemDefault())
    val bring = mergeBring(bringOf(event.notes), effects.bring)
    val place = if (plan.isFlight) null else event.location  // a flight's airport is already in its words
    val builder = NotificationCompat.Builder(context, LEAVE_BY_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle(words.title)
        .setContentText(words.text)
        .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(words.text) + words.lines +
            listOfNotNull(bring.ifBlank { null }?.let { "🎒 $BRING_LABEL$it" }, place)).filter { it.isNotBlank() }
            .joinToString("\n")))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, openHome, flags))

    // An Uber opens Uber; everything else opens Maps
    if (plan.tripMode == TRAVEL_UBER) {
        val openUber = uberIntent(event.location).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        builder.addAction(0, "Open Uber", PendingIntent.getActivity(context, id + 1, openUber, flags))
    } else {
        builder.addAction(0, "Open in Maps", PendingIntent.getActivity(context, id + 1, openMaps, flags))
    }

    // Offer the other travel modes as buttons
    for ((i, other) in otherModes(if (plan.noTrain) plan.tripMode else plan.mode).withIndex()) {
        val change = Intent(context, LeaveByModeReceiver::class.java).setAction(ACTION_MODE)
            .putExtra(EXTRA_KEY, key).putExtra(EXTRA_EVENT, event.eventId).putExtra(EXTRA_BEGIN, event.begin)
            .putExtra(EXTRA_MODE, other)
        builder.addAction(0, travelMode(other).label, PendingIntent.getBroadcast(context, id + 2 + i, change, flags))
    }
    postNotification(context, id, builder.build())
}

// A notification id for an event's alert, far above suggestion ids and the summary (each alert uses id to id + 3 for
// its buttons)
private fun notificationId(key: String): Int {
    return 1_100_000_000 + (key.hashCode() and 0x0FFFFFFF) % 50_000_000 * 4
}

// Find an event again on the switched-on calendars by its id and start (null if it was moved or deleted)
private fun findEvent(context: Context, eventId: Long, begin: Long): CalendarEvent? {
    val switchedOff = AppGraph.get(context).calendarSwitches.switchedOff()
    return calendarEvents(context, begin - MINUTE, begin + MINUTE, switchedOff)
        .firstOrNull { it.eventId == eventId && it.begin == begin }
}

// Look at the next 4 hours of events and book each alert for 10 minutes before its leave time, working out travel
// times once per event (and again if its mode changes); a rule's travel time is used instead of Google's, so those
// events work without a Maps key; runs every 15 minutes and when the app opens, and also books Deliberate's rule
// reminders
suspend fun planLeaveBy(context: Context) {
    val graph = AppGraph.get(context)
    val store = LeaveByStore(context)
    val now = System.currentTimeMillis()
    val rules = graph.deliberateDao.rules()
    planRuleReminders(context, rules, now)

    // Paused by Deliberate ("no leave-by alerts this weekend")
    if (graph.deliberateSettings.paused(PAUSE_LEAVE_BY, now)) {
        return
    }
    store.prune(now)
    val events = calendarEvents(context, now, now + LOOK_AHEAD_MILLIS, graph.calendarSwitches.switchedOff())

    // Book an alert for each event with a place that hasn't had one
    for (event in leaveByCandidates(events, now)) {
        val key = eventKey(event.title, event.begin)
        val effects = effectsFor(rules, event.title, event.location)

        // Skip events already alerted and events a rule keeps quiet
        if (store.alerted(key) || effects.quiet) {
            continue
        }
        val alertAt = leavePlan(context, event, effects, NETWORK_IF_NEEDED)?.alertAt ?: continue  // no travel time

        // Only re-book when the alert time changed
        if (store.bookedFor(key) != alertAt) {
            val data = workDataOf(EXTRA_KEY to key, EXTRA_EVENT to event.eventId, EXTRA_BEGIN to event.begin)
            val request = OneTimeWorkRequestBuilder<LeaveByAlertWorker>().setInputData(data)
                .setInitialDelay((alertAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ALERT_WORK + key, ExistingWorkPolicy.REPLACE, request)
            store.setBookedFor(key, alertAt)
        }
    }
}

// Every 15 minutes: book leave-by alerts for the coming events
class LeaveByPlanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Plan the alerts
    override suspend fun doWork(): Result {
        planLeaveBy(applicationContext)
        return Result.success()
    }
}

// At alert time: check the event is still there and not started, work out the travel time again from where you are
// now, and post the alert
class LeaveByAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Send one event's alert
    override suspend fun doWork(): Result {
        val store = LeaveByStore(applicationContext)
        val graph = AppGraph.get(applicationContext)
        val key = inputData.getString(EXTRA_KEY) ?: return Result.success()
        val event = findEvent(applicationContext, inputData.getLong(EXTRA_EVENT, 0), inputData.getLong(EXTRA_BEGIN, 0))

        // Skip events already alerted, moved, deleted or started, and alerts Deliberate paused
        if (store.alerted(key) || event == null || event.begin <= System.currentTimeMillis() ||
            graph.deliberateSettings.paused(PAUSE_LEAVE_BY)) {
            return Result.success()
        }
        val effects = effectsFor(graph.deliberateDao.rules(), event.title, event.location)

        // A rule turned this event's alerts off since it was booked
        if (effects.quiet) {
            return Result.success()
        }
        val plan = leavePlan(applicationContext, event, effects, NETWORK_FRESH) ?: return Result.success()
        showLeaveBy(applicationContext, event, plan, effects)
        store.setAlerted(key)
        Log.i("Datebook", "leave-by alert: ${event.title} · ${plan.travel}")
        return Result.success()
    }
}

// Handles the travel-mode buttons on an alert: saves the mode for that event and updates the alert with the new time
class LeaveByModeReceiver : BroadcastReceiver() {
    // Switch the mode in the background
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val mode = intent.getStringExtra(EXTRA_MODE) ?: return
        val eventId = intent.getLongExtra(EXTRA_EVENT, 0)
        val begin = intent.getLongExtra(EXTRA_BEGIN, 0)
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // Always tell Android the work is done, even if updating fails
            try {
                LeaveByStore(appContext).setMode(key, mode)
                val event = findEvent(appContext, eventId, begin) ?: return@launch
                val effects = effectsFor(AppGraph.get(appContext).deliberateDao.rules(), event.title, event.location)
                val plan = leavePlan(appContext, event, effects, NETWORK_FRESH) ?: return@launch
                showLeaveBy(appContext, event, plan, effects)
            } finally {
                pending.finish()
            }
        }
    }
}

// Start the 15-minute planning job if it isn't running yet
fun scheduleLeaveBy(context: Context) {
    val request = PeriodicWorkRequestBuilder<LeaveByPlanWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(PLAN_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}
