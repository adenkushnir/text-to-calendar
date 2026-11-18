// Checks Canvas's calendar feed every 6 hours, when Datebook opens and when the Canvas app opens: new assignments,
// quizzes and exams go to Due soon, moved or removed ones are updated with a notification each, and class events are
// offered as Add to calendar? prompts (3 or more at once come as one review list)
package io.github.adenkushnir.datebook.canvas
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.firstNudge
import io.github.adenkushnir.datebook.followups.bookNextFollowUp
import io.github.adenkushnir.datebook.pipeline.ApiKeyStore
import io.github.adenkushnir.datebook.prompts.canNotify
import io.github.adenkushnir.datebook.share.EXTRA_REVIEW_IDS
import io.github.adenkushnir.datebook.share.ShareActivity
import io.github.adenkushnir.datebook.widgets.updateWidgets
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
const val CANVAS_CHANNEL = "canvas"
const val REVIEW_SIZE = 3  // this many new class events at once come as one review list
private const val WORK = "datebook-canvas"
private const val NOW_WORK = "datebook-canvas-now"
private const val CHECK_HOURS = 6L
private const val SOON_MILLIS = 10 * 60_000L  // an open-triggered check is skipped within 10 minutes of the last
private const val TIMEOUT_MILLIS = 20_000
private const val NOTIFICATION_BASE = 1_095_000_000
private val DUE = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val checking = Mutex()

// What a check found, for Settings → School: how many assignments are open, how many class events were offered, and
// what went wrong (empty when nothing did)
data class CanvasResult(val assignments: Int, val classEvents: Int, val problem: String = "")

// Remembers the feed link (encrypted, since anyone with it can read your schedule), when it was last checked, and
// what that check found
class CanvasSettings(context: Context) {
    private val link = ApiKeyStore(context, "canvas")
    private val prefs = context.getSharedPreferences("datebook-canvas", Context.MODE_PRIVATE)

    // The saved feed link, or null without one
    fun link(): String? {
        return link.load()?.takeIf { it.isNotBlank() }
    }

    // Save the feed link
    fun setLink(feed: String) {
        link.save(feed.trim())
    }

    // Forget the feed link and what the checks found
    fun remove() {
        link.clear()
        prefs.edit().clear().apply()
    }

    // When the feed was last checked (0 for never)
    fun lastChecked(): Long {
        return prefs.getLong("checked", 0)
    }

    // What the last check said, like "12 assignments due · 3 class events offered", or its problem
    fun lastResult(): String {
        return prefs.getString("result", "").orEmpty()
    }

    // Remember a check
    fun setChecked(millis: Long, result: String) {
        prefs.edit().putLong("checked", millis).putString("result", result).apply()
    }
}

// A due day and time in words: "Wed Nov 25, 11:59 PM", or the day alone
fun dueText(date: String, time: String): String {
    val day = runCatching { LocalDate.parse(date).format(DUE) }.getOrDefault(date)
    val clock = runCatching { LocalTime.parse(time).format(CLOCK) }.getOrNull()
    return if (clock == null) day else "$day, $clock"
}

// What a check said, in words for Settings → School
fun resultText(result: CanvasResult): String {
    // A problem wins over the counts
    if (result.problem.isNotBlank()) {
        return result.problem
    }
    val due = if (result.assignments == 1) "1 assignment due" else "${result.assignments} assignments due"
    return if (result.classEvents == 0) due else "$due · ${result.classEvents} new class events offered"
}

// Download the feed, or throw with a reason in words
fun downloadFeed(link: String): String {
    val connection = URL(feedUrl(link)).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MILLIS
    connection.readTimeout = TIMEOUT_MILLIS
    connection.instanceFollowRedirects = true

    // Always close the connection
    try {
        val code = connection.responseCode

        // Canvas answers 200 with the calendar, anything else means the link is wrong or expired
        if (code != 200) {
            throw IllegalStateException("Canvas said no ($code). Copy the Calendar Feed link again.")
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }

        // Make sure it's a calendar
        if (!text.contains("BEGIN:VCALENDAR")) {
            throw IllegalStateException("That link didn't give a Canvas calendar.")
        }
        return text
    } finally {
        connection.disconnect()
    }
}

// Check the feed now and apply what changed; firstCheck stays quiet about moves (nothing was there before); returns
// what it found; this waits on the network, so it runs on the IO threads
suspend fun checkCanvas(context: Context, nowMillis: Long = System.currentTimeMillis()): CanvasResult =
    withContext(Dispatchers.IO) {
        checking.withLock {
            val settings = CanvasSettings(context)
            val link = settings.link() ?: return@withLock CanvasResult(0, 0, "No Canvas link saved.")
            val result = try {
                applyFeed(context, parseFeed(downloadFeed(link), ZoneId.systemDefault()), nowMillis)
            } catch (error: Exception) {  // no internet, a wrong or expired link, or a page that isn't a calendar
                Log.w("Datebook", "canvas check failed: ${error.message}")
                val reason = if (error is IllegalStateException) error.message.orEmpty()
                    else "Couldn't reach Canvas. Check your internet."
                CanvasResult(0, 0, reason)
            }
            settings.setChecked(nowMillis, resultText(result))
            result
        }
    }

// Apply a feed: save new assignments, update moved or renamed ones (telling you about moves), drop removed ones
// (telling you), offer new class events and quietly move waiting ones
private suspend fun applyFeed(context: Context, feed: List<FeedItem>, nowMillis: Long): CanvasResult {
    val graph = AppGraph.get(context)
    val dao = graph.dao
    val zone = ZoneId.systemDefault()
    val plan = planCheck(feed, dao.followUpsFrom(CANVAS_APP), dao.suggestionsFrom(CANVAS_APP), nowMillis, zone)

    // New assignments go to Due soon with their reminders booked
    for (item in plan.newAssignments) {
        val date = item.date.toString()
        dao.saveFollowUp(FollowUp(kind = KIND_ASSIGNMENT, title = assignmentTitle(item), date = date, amount = "",
            sender = "Canvas", app = CANVAS_APP, conversation = item.course, status = FollowUp.OPEN,
            createdAt = nowMillis, nextNudge = firstNudge(KIND_ASSIGNMENT, date, nowMillis, zone), nudges = 0,
            time = item.time, sourceId = item.uid))
    }

    // Moved or renamed assignments are updated; every move gets a notification
    for ((old, item) in plan.changed) {
        val date = item.date.toString()
        val moved = old.date != date || old.time != item.time
        val nextNudge = if (moved) firstNudge(KIND_ASSIGNMENT, date, nowMillis, zone) else old.nextNudge
        dao.updateFollowUp(old.copy(title = assignmentTitle(item), date = date, time = item.time,
            conversation = item.course, nextNudge = nextNudge, nudges = if (moved) 0 else old.nudges))

        // Tell you about a new due date
        if (moved) {
            notify(context, old.id.toInt(), "Due date moved: ${assignmentTitle(item)}",
                "Now due ${dueText(date, item.time)} (was ${dueText(old.date, old.time)})")
        }
    }

    // Assignments gone from Canvas leave Due soon, with a notification each
    for (gone in plan.removed) {
        dao.setFollowUpStatus(gone.id, FollowUp.DROPPED)
        notify(context, gone.id.toInt(), "Removed from Canvas: ${gone.title}",
            "It was due ${dueText(gone.date, gone.time)}. It's off your Due soon list.")
    }

    // Waiting class events follow Canvas's new time quietly
    for ((old, item) in plan.movedClassEvents) {
        dao.updateSuggestion(old.copy(start = planStart(item), end = planEnd(item, zone), allDay = item.time.isBlank(),
            previousStart = old.start, location = item.location.ifBlank { old.location }))
    }
    offerClassEvents(context, plan.newClassEvents, nowMillis)

    // Book the reminders and redraw the widgets when anything changed
    if (plan.newAssignments.isNotEmpty() || plan.changed.isNotEmpty() || plan.removed.isNotEmpty()) {
        bookNextFollowUp(context)
        updateWidgets(context)
    }
    val open = dao.followUpsFrom(CANVAS_APP).count { it.status == FollowUp.OPEN }
    return CanvasResult(open, plan.newClassEvents.size)
}

// Offer new class events: saved as waiting plans, then one review list for 3 or more, or a prompt each for fewer
private suspend fun offerClassEvents(context: Context, items: List<FeedItem>, nowMillis: Long) {
    val graph = AppGraph.get(context)
    val zone = ZoneId.systemDefault()
    val ids = mutableListOf<Long>()
    val saved = mutableListOf<Suggestion>()

    // Save each one as a waiting plan
    for (item in items) {
        val plan = Suggestion(sender = item.course.ifBlank { "Canvas" }, app = CANVAS_APP, title = item.title,
            start = planStart(item), end = planEnd(item, zone), allDay = item.time.isBlank(), location = item.location,
            repeat = "", timezone = "", phoneTimezone = zone.id, createdAt = nowMillis, sourceId = item.uid)
        val id = graph.dao.saveSuggestion(plan)
        ids.add(id)
        saved.add(plan.copy(id = id))
    }

    // Many at once come as one review list; a few come as prompts
    if (ids.size >= REVIEW_SIZE) {
        notifyReview(context, ids)
    } else {
        saved.forEach { graph.notifier.show(it, "From Canvas") }
    }
}

// Post a Canvas notification that opens Datebook
private fun notify(context: Context, key: Int, title: String, text: String) {
    // Nothing can be shown without permission to notify
    if (!canNotify(context)) {
        return
    }
    createChannel(context)
    val id = NOTIFICATION_BASE + (key and 0xFFFFFF) % 4_000_000
    val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val notification = NotificationCompat.Builder(context, CANVAS_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, open, flags))
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}

// Post "Canvas: 24 class events to review", which opens the review list
private fun notifyReview(context: Context, ids: List<Long>) {
    // Nothing can be shown without permission to notify
    if (!canNotify(context)) {
        return
    }
    createChannel(context)
    val id = NOTIFICATION_BASE - 1
    val review = Intent(context, ShareActivity::class.java).putExtra(EXTRA_REVIEW_IDS, ids.toLongArray())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val notification = NotificationCompat.Builder(context, CANVAS_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle("Canvas: ${ids.size} class events to review")
        .setContentText("Tick the ones you want on your calendar.")
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, review, flags))
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}

// The channel for Canvas's moved and removed assignments and class events to review
private fun createChannel(context: Context) {
    val channel = NotificationChannel(CANVAS_CHANNEL, "Canvas", NotificationManager.IMPORTANCE_DEFAULT)
    channel.description = "Moved or removed assignments, and class events to review"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

// Every 6 hours (and when asked): check the feed
class CanvasWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Check, and try again later if Canvas couldn't be reached
    override suspend fun doWork(): Result {
        checkCanvas(applicationContext)
        return Result.success()
    }
}

// Start the 6-hour checks while a link is saved, or stop them without one
fun scheduleCanvas(context: Context) {
    val work = WorkManager.getInstance(context)

    // No link, no checks
    if (CanvasSettings(context).link() == null) {
        work.cancelUniqueWork(WORK)
        return
    }
    val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request = PeriodicWorkRequestBuilder<CanvasWorker>(CHECK_HOURS, TimeUnit.HOURS).setConstraints(online).build()
    work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}

// Check soon, when Datebook or Canvas opens (skipped within 10 minutes of the last check, and without a link)
fun checkCanvasSoon(context: Context) {
    val settings = CanvasSettings(context)

    // Only with a link, and not right after another check
    if (settings.link() == null || System.currentTimeMillis() - settings.lastChecked() < SOON_MILLIS) {
        return
    }
    val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request = OneTimeWorkRequestBuilder<CanvasWorker>().setConstraints(online).build()
    WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
}
