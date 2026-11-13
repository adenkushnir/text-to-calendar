// Background checks on flights you have plans for: the day before, then 3 hours, 1 hour and 30 minutes before
// departure, and at landing (about 6 lookups a flight); an alert goes out only when something changed
package io.github.adenkushnir.datebook.flights
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.prompts.EXTRA_SUGGESTION_ID
import io.github.adenkushnir.datebook.prompts.canNotify
import java.util.concurrent.TimeUnit
const val CHANGES_CHANNEL = "travel_changes"
private const val CHECK_WORK = "datebook-flight-checks"
private const val MINUTE = 60_000L
private val BEFORE_DEPARTURE = listOf(24 * 60, 3 * 60, 60, 30)  // minutes before departure

// When to check a flight: the day before, 3 h, 1 h and 30 min before departure, and at landing (empty when its
// departure time is unknown)
fun checkTimes(info: FlightInfo): List<Long> {
    val departs = info.departScheduled?.toEpochMilli() ?: return emptyList()
    val lands = (info.arriveBest ?: info.arriveScheduled)?.toEpochMilli()
    return BEFORE_DEPARTURE.map { departs - it * MINUTE } + listOfNotNull(lands)
}

// Run every check that's due for the flights you have plans for (waiting or added), saving what's new and alerting
// when something changed
suspend fun checkFlights(context: Context) {
    val graph = AppGraph.get(context)
    val key = graph.flightsKeyStore.load()
    val now = System.currentTimeMillis()

    // Without a key there's nothing to check
    if (key.isNullOrBlank()) {
        return
    }

    // Look at each flight plan
    for (plan in graph.dao.flightSuggestions()) {
        val date = plan.start.take(10)
        val before = graph.flightStore.info(plan.flight, date) ?: continue  // never found
        val due = checkTimes(before).filter { it <= now && !graph.flightStore.checked(plan.flight, date, it) }

        // Skip flights with no check due
        if (due.isEmpty()) {
            continue
        }
        val after = lookupFlight(key, plan.flight, date) ?: continue  // try again next round
        due.forEach { graph.flightStore.setChecked(plan.flight, date, it) }
        graph.flightStore.save(date, after)
        val change = flightChange(before, after)
        Log.i("Datebook", "flight check: ${plan.flight} · ${statusLine(after)}")

        // Tell you only when something changed
        if (change != null) {
            showFlightChange(context, plan, change, statusLine(after))
        }
    }
}

// Post an alert that a flight changed, like "UA1234 now departs 7:10 PM (25 min late)"; tapping it opens the plan
fun showFlightChange(context: Context, plan: Suggestion, change: String, status: String) {
    // Nothing can be shown without permission to notify
    if (!canNotify(context)) {
        return
    }
    val channel = NotificationChannel(CHANGES_CHANNEL, "Flight and train changes", NotificationManager.IMPORTANCE_HIGH)
    channel.description = "Delays, cancellations and gate or track changes"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val id = 1_300_000_000 + plan.id.toInt()  // far above suggestion, summary and leave-by ids
    val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_SUGGESTION_ID, plan.id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val notification = NotificationCompat.Builder(context, CHANGES_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_teal))
        .setContentTitle(change)
        .setContentText(status)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, open, flags))
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}

// Every 15 minutes: run the flight checks that are due
class FlightCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Check the flights
    override suspend fun doWork(): Result {
        checkFlights(applicationContext)
        return Result.success()
    }
}

// Start the 15-minute flight checks if they aren't running yet
fun scheduleFlightChecks(context: Context) {
    val request = PeriodicWorkRequestBuilder<FlightCheckWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}
