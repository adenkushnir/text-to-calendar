// NJ Transit trains for plans: on the day of a plan you're taking transit to, the card lists the live board from your
// nearest station (or one you switch to) toward the plan, marking the best train and yours; from 3 hours before, the
// train you chose (or the best) goes on the leave-by alert and you're alerted when it's delayed, cancelled or changes
// track; also the Next trains card for the saved trip
package io.github.adenkushnir.datebook.trains
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.flights.showFlightChange
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.currentLocation
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
const val ON_THE_DAY = "Trains show on the day of the plan"
private const val MINUTE = 60_000L
private const val PLAN_AHEAD_MILLIS = 3 * 60 * MINUTE
private const val ARRIVE_BEFORE_MILLIS = 10 * MINUTE
private const val TRAIN_WORK = "datebook-trains"
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val MAPPER = ObjectMapper()

// The train picked for a plan: its number and line, where and when it leaves and arrives, its track, delay and status
data class TrainPick(
    val trainId: String,
    val line: String,
    val fromName: String,
    val toName: String,
    val departs: Long,
    val arrives: Long,
    val track: String,
    val secondsLate: Int,
    val status: String,
)

// A time as the app shows it, in New Jersey time
private fun clock(millis: Long): String {
    return Instant.ofEpochMilli(millis).atZone(NJ_ZONE).format(CLOCK)
}

// The train in words, like "Take NJT 3851 at 5:12 PM, New Brunswick → New York Penn · Track 2 · 3 min late"
fun pickLine(pick: TrainPick): String {
    val track = if (pick.track.isNotBlank()) " · Track ${pick.track}" else ""  // once it's known

    // Say if it's cancelled or late
    val late = when {
        pick.status.contains("cancel", ignoreCase = true) -> " · Cancelled"
        pick.secondsLate >= 60 -> " · ${pick.secondsLate / 60} min late"
        else -> ""
    }
    return "Take NJT ${pick.trainId} at ${clock(pick.departs)}, ${pick.fromName} → ${pick.toName}$track$late"
}

// Pick the train for a plan from the departure boards of the stations near you: among trains that stop at a station
// near the plan at least 10 minutes before it starts, the one that leaves last (the least waiting); null if none fits
fun pickTrain(boards: Map<Station, List<Train>>, destinations: List<Station>, startMillis: Long): TrainPick? {
    val options = mutableListOf<TrainPick>()

    // Look at every train leaving each nearby station
    for ((from, trains) in boards) {
        // Keep each train that reaches a station near the plan in time
        for (train in trains) {
            val departs = train.departs ?: continue
            val (to, arrives) = destinations.firstNotNullOfOrNull { station ->
                arrivalAt(train, station.code)?.let { Pair(station, it) }
            } ?: continue
            val late = train.secondsLate * 1000L

            // Skip cancelled trains and ones that would get there too late
            if (isCancelled(train) || arrives.toEpochMilli() + late > startMillis - ARRIVE_BEFORE_MILLIS) {
                continue
            }
            options.add(TrainPick(train.id, train.line, from.name, to.name, departs.toEpochMilli(),
                arrives.toEpochMilli(), train.track, train.secondsLate, train.status))
        }
    }
    return options.maxByOrNull { it.departs }
}

// One train in a plan's list: the train as picked if you chose it, when it leaves and arrives, its track and state, and
// whether it's the best one, gets there late, or is yours
data class TrainRow(
    val pick: TrainPick,
    val leaves: String,
    val state: String,
    val arrives: String,
    val best: Boolean,
    val late: Boolean,
    val mine: Boolean,
)

// A plan's trains: the station you leave from (and the other nearby ones to switch to), the station near the plan, the
// list, and a note when there's no list
data class TrainChoices(
    val from: Station?,
    val origins: List<Station>,
    val to: Station?,
    val rows: List<TrainRow>,
    val note: String = "",
)

// Build a plan's train list from the board at the station you leave from: every train that stops at a station near
// the plan (the first of them any train serves), marking the one that gets there closest to the start but at least
// 10 minutes early as best, the ones that get there too late, and yours
fun trainRows(from: Station, trains: List<Train>, destinations: List<Station>, startMillis: Long, mine: String?):
    Pair<Station?, List<TrainRow>> {
    val to = destinations.firstOrNull { station -> trains.any { arrivalAt(it, station.code) != null } }
        ?: return Pair(null, emptyList())
    val best = pickTrain(mapOf(from to trains), listOf(to), startMillis)?.trainId
    val rows = trains.mapNotNull { train ->
        val departs = train.departs ?: return@mapNotNull null
        val arrives = arrivalAt(train, to.code) ?: return@mapNotNull null  // doesn't stop near the plan
        val pick = TrainPick(train.id, train.line, from.name, to.name, departs.toEpochMilli(), arrives.toEpochMilli(),
            train.track, train.secondsLate, train.status)
        val late = isCancelled(train) ||
            arrives.toEpochMilli() + train.secondsLate * 1000L > startMillis - ARRIVE_BEFORE_MILLIS
        TrainRow(pick, clock(departs.toEpochMilli()), boardLine(train).substringAfter(" · "), clock(
            arrives.toEpochMilli() + train.secondsLate * 1000L), train.id == best, late, train.id == mine)
    }
    return Pair(to, rows)
}

// What changed about the picked train that's worth an alert: cancelled, 5+ more minutes late, or a new track; null
// when nothing did
fun trainChange(before: TrainPick, after: TrainPick): String? {
    // Pick the most important change
    return when {
        after.status.contains("cancel", ignoreCase = true) && !before.status.contains("cancel", ignoreCase = true) ->
            "NJT ${after.trainId} is cancelled"
        after.secondsLate - before.secondsLate >= 300 -> "NJT ${after.trainId} now ${after.secondsLate / 60} min late"
        after.track.isNotBlank() && after.track != before.track -> "NJT ${after.trainId} now on track ${after.track}"
        else -> null
    }
}

// Remembers the train picked for each plan, by the same key leave-by uses
class TrainStore(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-train-picks", Context.MODE_PRIVATE)

    // Return the train picked for a plan, or null
    fun pick(key: String): TrainPick? {
        val node = MAPPER.readTree(prefs.getString(key, null) ?: return null)
        return TrainPick(node.path("trainId").asText(""), node.path("line").asText(""),
            node.path("fromName").asText(""), node.path("toName").asText(""), node.path("departs").asLong(),
            node.path("arrives").asLong(), node.path("track").asText(""), node.path("secondsLate").asInt(),
            node.path("status").asText(""))
    }

    // Return the train you chose for a plan, or null to follow the best one
    fun choice(key: String): String? {
        return prefs.getString("choice:$key", null)
    }

    // Remember the train you chose for a plan
    fun setChoice(key: String, trainId: String) {
        prefs.edit().putString("choice:$key", trainId).apply()
    }

    // Return the station you switched to leaving from for a plan, or null for the nearest
    fun origin(key: String): String? {
        return prefs.getString("origin:$key", null)
    }

    // Remember the station you leave from for a plan, forgetting the train chosen at the old one
    fun setOrigin(key: String, code: String) {
        prefs.edit().putString("origin:$key", code).remove("choice:$key").apply()
    }

    // Remember the train picked for a plan
    fun setPick(key: String, pick: TrainPick) {
        val plain = mapOf("trainId" to pick.trainId, "line" to pick.line, "fromName" to pick.fromName,
            "toName" to pick.toName, "departs" to pick.departs, "arrives" to pick.arrives, "track" to pick.track,
            "secondsLate" to pick.secondsLate, "status" to pick.status)
        prefs.edit().putString(key, MAPPER.writeValueAsString(plain)).apply()
    }
}

// Return true if a plan goes by transit and has a known place, so it can get a train
fun wantsTrain(context: Context, plan: Suggestion): Boolean {
    val graph = AppGraph.get(context)
    val key = eventKey(plan.title, eventTimes(plan.toEvent()).first)
    val mode = LeaveByStore(context).mode(key) ?: graph.travelSettings.mode()
    return mode == TRAVEL_TRANSIT && plan.placeLat != null && plan.placeLng != null && plan.change.isBlank()
}

// Pick (or re-check) trains for added transit plans starting in the next 3 hours, alerting on changes; also loads
// the station list when it's due; runs every 15 minutes
suspend fun planTrains(context: Context) {
    val graph = AppGraph.get(context)
    val now = System.currentTimeMillis()

    // Without an NJ Transit account there's nothing to look up
    if (graph.njtUserStore.load().isNullOrBlank()) {
        return
    }
    refreshStations(graph.trainSettings, graph.railData, now)
    val stations = graph.trainSettings.stations()

    // Look at each added plan
    for (plan in graph.dao.suggestionsWithStatus(Suggestion.STATUS_ADDED)) {
        val start = eventTimes(plan.toEvent()).first

        // Only plans starting in the next 3 hours that you're taking transit to
        if (start <= now || start > now + PLAN_AHEAD_MILLIS || !wantsTrain(context, plan)) {
            continue
        }
        val key = eventKey(plan.title, start)
        val choices = trainChoices(context, plan, stations)
        val rows = choices.rows
        val pick = (rows.firstOrNull { it.mine } ?: rows.firstOrNull { it.best })?.pick ?: continue  // yours, or best
        val before = graph.trainStore.pick(key)
        graph.trainStore.setPick(key, pick)
        Log.i("Datebook", "train for ${plan.title}: ${pickLine(pick)}")
        val change = if (before != null && before.trainId == pick.trainId) trainChange(before, pick) else null

        // Tell you only when the same train changed
        if (change != null) {
            showFlightChange(context, plan, change, pickLine(pick))
        }
    }
}

// The stations you could leave from: the 3 nearest to where you are, or your saved home station when your location
// isn't known
fun originsFor(context: Context, stations: List<Station>): List<Station> {
    val here = currentLocation(context)
    val homeCode = AppGraph.get(context).trainSettings.trip()?.first
    return if (here != null) nearestStations(here.first, here.second, stations)
    else stations.filter { it.code == homeCode }  // fall back to the saved home station
}

// Read a plan's train list now: from the station you switched to (or the nearest), toward the stations near the plan;
// this waits on the network, so call it off the main thread
fun trainChoices(context: Context, plan: Suggestion, stations: List<Station> = AppGraph.get(context).trainSettings
    .stations()): TrainChoices {
    val graph = AppGraph.get(context)
    val start = eventTimes(plan.toEvent()).first
    val key = eventKey(plan.title, start)
    val origins = originsFor(context, stations)
    val from = stations.firstOrNull { it.code == graph.trainStore.origin(key) } ?: origins.firstOrNull()
        ?: return TrainChoices(null, origins, null, emptyList(), "No NJ Transit station near you.")
    val destinations = nearestStations(plan.placeLat ?: 0.0, plan.placeLng ?: 0.0, stations)

    // Without a station near the plan there's no train to take
    if (destinations.isEmpty()) {
        return TrainChoices(from, origins, null, emptyList(), "No NJ Transit station near this plan.")
    }
    val (to, rows) = trainRows(from, graph.railData.departures(from.code), destinations, start,
        graph.trainStore.choice(key))
    val note = if (rows.isEmpty()) "No trains from ${from.name} toward this plan right now." else ""  // board empty
    return TrainChoices(from, (origins + from).distinct(), to, rows, note)
}

// Every 15 minutes: pick and re-check trains
class TrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Plan the trains
    override suspend fun doWork(): Result {
        planTrains(applicationContext)
        return Result.success()
    }
}

// Start the 15-minute train job if it isn't running yet
fun scheduleTrains(context: Context) {
    val request = PeriodicWorkRequestBuilder<TrainWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(TRAIN_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}

// The Next trains card's contents: the trip's station names and up to 3 trains, or a note saying why there are none
data class TrainBoard(val fromName: String, val toName: String, val lines: List<String>, val note: String = "")

// One train on the Next trains card, like "5:12 PM · Track 2 · On time" or "5:42 PM · 3 min late"
fun boardLine(train: Train): String {
    val time = train.departs?.let { clock(it.toEpochMilli()) } ?: "?"
    val track = if (train.track.isNotBlank()) " · Track ${train.track}" else ""  // once it's known

    // Say if it's cancelled, late or on time
    val state = when {
        isCancelled(train) -> "Cancelled"
        train.secondsLate >= 60 -> "${train.secondsLate / 60} min late"
        else -> "On time"
    }
    return "$time$track · $state"
}

// Read the Next trains card for a trip: the next 3 trains from the first station that stop at the second; this waits
// on the network, so call it off the main thread
fun nextTrains(graph: AppGraph, from: String, to: String): TrainBoard {
    val stations = graph.trainSettings.stations()
    val fromName = stations.firstOrNull { it.code == from }?.name ?: from
    val toName = stations.firstOrNull { it.code == to }?.name ?: to
    val trains = graph.railData.departures(from).filter { arrivalAt(it, to) != null }.take(3)
    val note = if (trains.isEmpty()) "No trains found right now." else ""  // the board was empty or unreachable
    return TrainBoard(fromName, toName, trains.map { boardLine(it) }, note)
}
