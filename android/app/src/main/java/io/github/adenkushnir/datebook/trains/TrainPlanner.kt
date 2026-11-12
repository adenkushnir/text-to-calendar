// Suggested NJ Transit trains: on the day of a plan you're taking transit to (from about 3 hours before), pick the
// train from the stations nearest you and the plan, show it on the card and the leave-by alert, and alert when it's
// delayed, cancelled or changes track; also the Next trains card for the saved trip
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
const val ON_THE_DAY = "Train suggested on the day"
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
        val here = currentLocation(context)
        val homeCode = graph.trainSettings.trip()?.first
        val origins = if (here != null) nearestStations(here.first, here.second, stations)
            else stations.filter { it.code == homeCode }  // fall back to the saved home station
        val destinations = nearestStations(plan.placeLat!!, plan.placeLng!!, stations)
        val boards = origins.associateWith { graph.railData.departures(it.code) }
        val pick = pickTrain(boards, destinations, start) ?: continue
        val key = eventKey(plan.title, start)
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
