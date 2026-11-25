// NJ Transit trains for plans: on the day of a plan you're taking transit to, the card lists the live board from your
// nearest station (or one you switch to) toward the plan, marking the best train and yours; from 4 hours before, the
// train you chose (or the best) goes on the leave-by alert and you're alerted when it's delayed, cancelled or changes
// track (once the leave-by alert went out, the alert itself is updated, with a new train when yours no longer makes
// it); also the Next trains card for the saved trip
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
import io.github.adenkushnir.datebook.deliberate.effectsFor
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.NOTE_NONE
import io.github.adenkushnir.datebook.leaveby.NOTE_SWAP
import io.github.adenkushnir.datebook.leaveby.leadFor
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.leaveby.reAlert
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.currentLocation
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
private const val LEFT_EARLY_MILLIS = 5L * 60 * 1000  // a train gone from the board this close to its time left
private const val SEEN_FOR_MILLIS = 3L * 60 * 60 * 1000  // how long seen trains are remembered
const val ON_THE_DAY = "Trains show on the day of the plan"
private const val MINUTE = 60_000L
private const val PLAN_AHEAD_MILLIS = 4 * 60 * MINUTE
private const val ARRIVE_BEFORE_MILLIS = 10 * MINUTE
private const val SPARE_MINUTES = 5  // the train gets there this much before you mean to be there
private const val TRAIN_WORK = "datebook-trains"
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val MAPPER = ObjectMapper()

// The train picked for a plan: its number and line, where and when it leaves and arrives, its track, delay and status,
// and the code of the station it leaves from (empty for picks saved before 1.3.0)
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
    val fromCode: String = "",
)

// How long before a plan's start its train must get there: 5 minutes before you mean to be there (the minutes early),
// and never less than 10
fun arriveBefore(earlyMinutes: Int): Long {
    return maxOf(ARRIVE_BEFORE_MILLIS, (earlyMinutes + SPARE_MINUTES) * MINUTE)
}

// Return true if a picked train still gets you there in time: not cancelled, and arriving (with its delay) by the
// time it must
fun stillMakesIt(pick: TrainPick, startMillis: Long, beforeMillis: Long): Boolean {
    return !pick.status.contains("cancel", ignoreCase = true) &&
        pick.arrives + pick.secondsLate * 1000L <= startMillis - beforeMillis
}

// What went wrong with a train that no longer works, like "NJT 3863 was cancelled" or "NJT 3863 is 20 min late"
fun trainProblem(pick: TrainPick): String {
    return if (pick.status.contains("cancel", ignoreCase = true)) "NJT ${pick.trainId} was cancelled"
        else "NJT ${pick.trainId} is ${pick.secondsLate / 60} min late"
}

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

// The train on a leave-by alert, like "NJT 3863 at 4:52 PM · Track 2 · arrives Newark Airport 5:21 PM", with its
// delay added to both times
fun rideLine(pick: TrainPick): String {
    val delay = pick.secondsLate * 1000L
    val track = if (pick.track.isNotBlank()) " · Track ${pick.track}" else ""  // once it's known
    val late = if (pick.secondsLate >= 60) " · ${pick.secondsLate / 60} min late" else ""
    return "NJT ${pick.trainId} at ${clock(pick.departs + delay)}$track$late · arrives ${pick.toName} " +
        clock(pick.arrives + delay)
}

// Pick the train for a plan from the departure boards of the stations near you: among trains that stop at a station
// near the plan in time (by default 10 minutes before it starts), the one that leaves last (the least waiting); null if
// none fits
fun pickTrain(boards: Map<Station, List<Train>>, destinations: List<Station>, startMillis: Long,
    beforeMillis: Long = ARRIVE_BEFORE_MILLIS): TrainPick? {
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
            if (isCancelled(train) || arrives.toEpochMilli() + late > startMillis - beforeMillis) {
                continue
            }
            options.add(TrainPick(train.id, train.line, from.name, to.name, departs.toEpochMilli(),
                arrives.toEpochMilli(), train.track, train.secondsLate, train.status, from.code))
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
// the plan (the first of them any train serves), marking the one that gets there closest to the start but in time (by
// default 10 minutes early) as best, the ones that get there too late, and yours
fun trainRows(from: Station, trains: List<Train>, destinations: List<Station>, startMillis: Long, mine: String?,
    beforeMillis: Long = ARRIVE_BEFORE_MILLIS): Pair<Station?, List<TrainRow>> {
    val to = destinations.firstOrNull { station -> trains.any { arrivalAt(it, station.code) != null } }
        ?: return Pair(null, emptyList())
    val best = pickTrain(mapOf(from to trains), listOf(to), startMillis, beforeMillis)?.trainId
    val rows = trains.mapNotNull { train ->
        val departs = train.departs ?: return@mapNotNull null
        val arrives = arrivalAt(train, to.code) ?: return@mapNotNull null  // doesn't stop near the plan
        val pick = TrainPick(train.id, train.line, from.name, to.name, departs.toEpochMilli(), arrives.toEpochMilli(),
            train.track, train.secondsLate, train.status, from.code)
        val late = isCancelled(train) || arrives.toEpochMilli() + train.secondsLate * 1000L > startMillis - beforeMillis
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
            node.path("status").asText(""), node.path("fromCode").asText(""))
    }

    // Forget the train picked for a plan (when no train makes it any more)
    fun clearPick(key: String) {
        prefs.edit().remove(key).apply()
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

    // Put back the station a plan left from (null for the nearest), for Undo
    fun putOrigin(key: String, code: String?) {
        // Forget the switch, or make it again
        if (code == null) {
            prefs.edit().remove("origin:$key").apply()
        } else {
            setOrigin(key, code)
        }
    }

    // Remember the train picked for a plan
    fun setPick(key: String, pick: TrainPick) {
        val plain = mapOf("trainId" to pick.trainId, "line" to pick.line, "fromName" to pick.fromName,
            "toName" to pick.toName, "departs" to pick.departs, "arrives" to pick.arrives, "track" to pick.track,
            "secondsLate" to pick.secondsLate, "status" to pick.status, "fromCode" to pick.fromCode)
        prefs.edit().putString(key, MAPPER.writeValueAsString(plain)).apply()
    }
}

// Return true if a plan goes by transit (chosen for the plan, by a rule, or the default) and has a known place, so it
// can get a train
suspend fun wantsTrain(context: Context, plan: Suggestion): Boolean {
    val graph = AppGraph.get(context)
    val key = eventKey(plan.title, eventTimes(plan.toEvent()).first)
    val ruled = effectsFor(graph.deliberateDao.rules(), plan.title, plan.location).mode
    val mode = LeaveByStore(context).mode(key) ?: ruled ?: graph.travelSettings.mode()
    return mode == TRAVEL_TRANSIT && plan.placeLat != null && plan.placeLng != null && plan.change.isBlank()
}

// Pick (or re-check) trains for added transit plans starting in the next 4 hours, alerting on changes; also loads
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

        // Only plans starting in the next 4 hours that you're taking transit to
        if (start <= now || start > now + PLAN_AHEAD_MILLIS || !wantsTrain(context, plan)) {
            continue
        }
        val key = eventKey(plan.title, start)
        val rows = trainChoices(context, plan, stations).rows
        val pick = (rows.firstOrNull { it.mine && !it.late } ?: rows.firstOrNull { it.best })?.pick  // yours, or best
        val before = graph.trainStore.pick(key)
        val leaveBy = LeaveByStore(context)
        val alerted = leaveBy.alerted(key)

        // An empty board (often no internet) changes nothing; a board where no train makes it drops the old pick, so
        // the leave-by falls back to Google's time and says why
        if (rows.isEmpty()) {
            continue
        } else if (pick == null) {
            // Only once, when there was a train before
            if (before != null) {
                val problem = rows.firstOrNull { it.pick.trainId == before.trainId }?.pick?.let { trainProblem(it) }
                graph.trainStore.clearPick(key)
                leaveBy.setTrainNote(key, NOTE_NONE, problem ?: "NJT ${before.trainId} no longer gets you there")
                Log.i("Datebook", "no train makes it for ${plan.title} any more")
                reAlertIfSent(context, key, alerted)
            }
            continue
        }
        graph.trainStore.setPick(key, pick)
        Log.i("Datebook", "train for ${plan.title}: ${pickLine(pick)}")
        val old = before?.let { gone -> rows.firstOrNull { it.pick.trainId == gone.trainId }?.pick }
        val inTime = arriveBefore(leadFor(context, plan.title, plan.location, start))
        val swapped = old != null && old.trainId != pick.trainId && !stillMakesIt(old, start, inTime)
        val change = if (before != null && before.trainId == pick.trainId) trainChange(before, pick) else null

        // Your train stopped working and another took its place; the same train changed; or a train makes it again
        if (old != null && swapped) {
            leaveBy.setTrainNote(key, NOTE_SWAP, trainProblem(old))
            reAlertIfSent(context, key, alerted)
        } else if (change != null && alerted) {
            reAlert(context, key)
        } else if (change != null) {
            showFlightChange(context, plan, change, pickLine(pick))
        } else if (leaveBy.trainNote(key)?.first == NOTE_NONE) {
            leaveBy.setTrainNote(key, "", "")
        }
    }
}

// Update a leave-by alert that already went out (one not sent yet shows the news when it goes)
private suspend fun reAlertIfSent(context: Context, key: String, alerted: Boolean) {
    // Only alerts already sent
    if (alerted) {
        reAlert(context, key)
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

// Read a plan's train list now: from the station you switched to (or the nearest), toward the stations near the plan,
// getting there in time for how early you mean to be there; this waits on the network, so call it off the main thread
suspend fun trainChoices(context: Context, plan: Suggestion, stations: List<Station> = AppGraph.get(context)
    .trainSettings.stations()): TrainChoices {
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
    val before = arriveBefore(leadFor(context, plan.title, plan.location, start))
    val (to, rows) = trainRows(from, graph.railData.departures(from.code).orEmpty(), destinations, start,
        graph.trainStore.choice(key), before)
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

// The train board's contents: the trip's station names, up to 3 trains (or a note saying why there are none), the
// train that just left (blank when unknown), and when the next one leaves (0 when unknown)
data class TrainBoard(
    val fromName: String,
    val toName: String,
    val lines: List<String>,
    val note: String = "",
    val last: String = "",
    val nextDeparture: Long = 0,
)

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

// A train seen on a trip's board: its number, when it leaves (with any delay) and how it reads once it has left
data class SeenTrain(val id: String, val leaves: Long, val line: String)

// When a train really leaves: its time plus how late it is
fun leaves(train: Train): Long {
    return (train.departs?.toEpochMilli() ?: 0) + train.secondsLate * 1000L
}

// The train that left in words, like "4:42 PM · Track 1 · Departed"
fun departedLine(train: Train): String {
    return boardLine(train).substringBeforeLast(" · ") + " · Departed"
}

// The board for a trip from its first station's live trains: the next 3 that stop at the trip's end, and the
// latest remembered train that has since left the board (NJ Transit only lists trains still to leave); also returns
// the trains to remember next time (the last 3 hours, never cancelled ones, which don't leave)
fun tripBoard(fromName: String, toName: String, to: String, live: List<Train>, seen: List<SeenTrain>,
    nowMillis: Long): Pair<TrainBoard, List<SeenTrain>> {
    val trip = live.filter { arrivalAt(it, to) != null }
    val next = trip.take(3)
    val onBoard = trip.map { it.id }.toSet()
    val gone = seen.filter { it.id !in onBoard && it.leaves <= nowMillis + LEFT_EARLY_MILLIS }  // left, not dropped
    val last = gone.maxByOrNull { it.leaves }?.line.orEmpty()
    val fresh = trip.filter { !isCancelled(it) }.map { SeenTrain(it.id, leaves(it), departedLine(it)) }
    val remember = (fresh + seen).distinctBy { it.id }.filter { it.leaves > nowMillis - SEEN_FOR_MILLIS }
    val note = if (next.isEmpty()) "No trains found right now." else ""  // nothing on the board toward the trip's end
    val board = TrainBoard(fromName, toName, next.map { boardLine(it) }, note, last,
        next.firstOrNull()?.let { leaves(it) } ?: 0)
    return Pair(board, remember)
}

// Read the train board for a trip: the train that just left and the next 3 from the first station that stop at the
// second, from NJ Transit's live board; this waits on the network, so call it off the main thread
fun nextTrains(graph: AppGraph, from: String, to: String, nowMillis: Long = System.currentTimeMillis()): TrainBoard {
    val settings = graph.trainSettings
    val stations = settings.stations()
    val fromName = stations.firstOrNull { it.code == from }?.name ?: from
    val toName = stations.firstOrNull { it.code == to }?.name ?: to

    // Without an account there's no board to read
    if (graph.njtUserStore.load().isNullOrBlank()) {
        return TrainBoard(fromName, toName, emptyList(),
            "Add your NJ Transit account in Settings → Flights & trains.")
    }
    val live = graph.railData.departures(from)
        ?: return TrainBoard(fromName, toName, emptyList(), "Can't reach NJ Transit right now.")
    val (board, remember) = tripBoard(fromName, toName, to, live, settings.seen(from, to), nowMillis)
    settings.setSeen(from, to, remember)
    return board
}
