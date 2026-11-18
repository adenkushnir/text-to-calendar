// Finds men's foil tournaments with Claude's web search: every morning at 7 AM it searches the next 3 weeks when it's
// Monday (or the last search is a week old), then checks today's streams for events on today and books a quiet
// "Streaming now" notification for each; the results are kept on the phone for Home and Deliberate
package io.github.adenkushnir.datebook.fencing
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.prompts.canNotify
import io.github.adenkushnir.datebook.summary.delayUntilNext
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
const val FENCING_CHANNEL = "fencing"
private const val MORNING_HOUR = 7
private const val WORK = "datebook-fencing"
private const val STREAM_WORK = "datebook-fencing-stream-"
private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
private const val MAX_PAUSES = 4  // how many times a long web search may be continued
private const val NOTIFICATION_BASE = 1_096_000_000
private const val EXTRA_KEY = "key"
private const val SYSTEM = "You find men's foil fencing competitions on the web for someone who follows and watches " +
    "them. Use web search and web fetch, and always look at FencingTV's schedule on fencing.tv for streams. Be " +
    "exact: only events you found, with dates and links from the pages you read. End with the JSON the user asks for."
private val searching = Mutex()

// Remembers whether fencing is on, what the last search found, and when it ran
class FencingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-fencing", Context.MODE_PRIVATE)

    // Return true if Datebook looks for tournaments (off to start, since the searches use your Claude key)
    fun isOn(): Boolean {
        return prefs.getBoolean("on", false)
    }

    // Turn the searches on or off
    fun setOn(on: Boolean) {
        prefs.edit().putBoolean("on", on).apply()
    }

    // The tournaments found
    fun tournaments(): List<Tournament> {
        return tournamentsFromJson(prefs.getString("tournaments", "").orEmpty())
    }

    // Save the tournaments found
    fun setTournaments(list: List<Tournament>) {
        prefs.edit().putString("tournaments", tournamentsToJson(list)).apply()
    }

    // When the last full search ran (0 for never), and what it said
    fun lastSearch(): Long {
        return prefs.getLong("searched", 0)
    }

    // What the last search said, like "Found 3 events" or its problem
    fun lastResult(): String {
        return prefs.getString("result", "").orEmpty()
    }

    // Remember a search
    fun setSearched(millis: Long, result: String) {
        prefs.edit().putLong("searched", millis).putString("result", result).apply()
    }
}

// A Claude client that waits up to 5 minutes, since a web search can take a while
private fun searchClient(key: String): AnthropicClient {
    return AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofMinutes(5)).build()
}

// Ask Claude with web search and web fetch, continuing a long search when it pauses; returns its answer's text
fun askWithWeb(client: AnthropicClient, model: String, prompt: String): String {
    val request = MessageCreateParams.builder()
        .model(model)
        .maxTokens(8000L)
        .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        .system(SYSTEM)
        .addTool(BetaWebSearchTool20260209.builder().maxUses(8L).build())
        .addTool(BetaWebFetchTool20260209.builder().maxUses(6L).maxContentTokens(20000L).build())
        .addUserMessage(prompt)
    var response = client.beta().messages().create(request.build())

    // Continue a paused search where it stopped
    for (pause in 1..MAX_PAUSES) {
        // Only a paused answer needs another round
        if (response.stopReason().orElse(null) != BetaStopReason.PAUSE_TURN) {
            break
        }
        request.addMessage(response)
        response = client.beta().messages().create(request.build())
    }
    return response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n")
}

// Search the next 3 weeks now and save what was found; returns what happened in words
suspend fun searchTournaments(context: Context, nowMillis: Long = System.currentTimeMillis()): String =
    withContext(Dispatchers.IO) {
        searching.withLock {
            val graph = AppGraph.get(context)
            val settings = FencingSettings(context)
            val key = graph.keyStore.load()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)

            // The search needs a Claude key
            if (key.isNullOrBlank()) {
                return@withLock "Add your Claude API key first."
            }
            val result = try {
                val answer = askWithWeb(searchClient(key), graph.modelChoice.get(), searchPrompt(today, zone))
                val events = answerJson(answer)?.path("events") ?: throw IllegalStateException("no JSON in the answer")
                val found = mergeSearch(settings.tournaments(), tournamentsFrom(events), today)
                settings.setTournaments(found)
                val count = if (found.size == 1) "1 event" else "${found.size} events"
                "Found $count in the next 3 weeks."
            } catch (error: Exception) {  // no internet, a rejected key, or an answer that can't be read
                Log.w("Datebook", "fencing search failed: ${error.message}")
                "The search didn't work. It tries again tomorrow morning."
            }
            settings.setSearched(nowMillis, result)
            result
        }
    }

// Check today's streams for the tournaments on today, save them, and book their "Streaming now" notifications
suspend fun checkToday(context: Context, nowMillis: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
    searching.withLock {
        val graph = AppGraph.get(context)
        val settings = FencingSettings(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val onToday = settings.tournaments().filter { isOn(it, today) }
        val key = graph.keyStore.load()

        // Only on days with fencing, and with a key
        if (onToday.isEmpty() || key.isNullOrBlank()) {
            return@withLock
        }

        // A failed check keeps what the Monday search found
        try {
            val answer = askWithWeb(searchClient(key), graph.modelChoice.get(), todayPrompt(today, zone, onToday))
            val events = answerJson(answer)?.path("events")

            // Save today's details when the answer could be read
            if (events != null) {
                settings.setTournaments(mergeToday(settings.tournaments(), tournamentsFrom(events), today))
            }
        } catch (error: Exception) {  // no internet, a rejected key, or an answer that can't be read
            Log.w("Datebook", "fencing today check failed: ${error.message}")
        }
        bookStreamAlerts(context, settings.tournaments(), nowMillis)
    }
}

// Book a "Streaming now" notification for each tournament on today that has a stream link, at its stream's start
// (right away when it's already live)
fun bookStreamAlerts(context: Context, tournaments: List<Tournament>, nowMillis: Long) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)

    // One notification per tournament streaming today
    for (tournament in tournaments.filter { it.streamUrl.isNotBlank() }) {
        val window = streamWindow(tournament, today, zone) ?: continue

        // Skip streams already over
        if (window.second <= nowMillis) {
            continue
        }
        val key = tournamentKey(tournament)
        val request = OneTimeWorkRequestBuilder<StreamAlertWorker>().setInputData(workDataOf(EXTRA_KEY to key))
            .setInitialDelay((window.first - nowMillis).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("$STREAM_WORK$key|$today", ExistingWorkPolicy.REPLACE,
            request)
    }
}

// Every morning at 7 AM: the weekly search on Mondays (or when the last one is a week old), then today's streams,
// then the next morning is booked
class FencingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Search and check while fencing is on
    override suspend fun doWork(): Result {
        val settings = FencingSettings(applicationContext)
        val now = System.currentTimeMillis()

        // Nothing to do while fencing is off
        if (settings.isOn()) {
            val monday = LocalDate.now().dayOfWeek == DayOfWeek.MONDAY

            // The full search on Mondays, or when the last one is too old
            if (monday || now - settings.lastSearch() > WEEK_MILLIS) {
                searchTournaments(applicationContext, now)
            }
            checkToday(applicationContext, now)
        }
        scheduleFencing(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

// At a stream's start: the quiet "Streaming now" notification, with Watch and Results
class StreamAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Post it, unless fencing was turned off or the tournament is gone
    override suspend fun doWork(): Result {
        val settings = FencingSettings(applicationContext)
        val key = inputData.getString(EXTRA_KEY) ?: return Result.success()
        val tournament = settings.tournaments().firstOrNull { tournamentKey(it) == key }

        // Only while fencing is on and the tournament is still saved
        if (settings.isOn() && tournament != null) {
            showStreamAlert(applicationContext, tournament)
        }
        return Result.success()
    }
}

// Post "Streaming now: Junior Men's Foil" with the meet and stage, Watch and Results
private fun showStreamAlert(context: Context, tournament: Tournament) {
    // Nothing can be shown without permission to notify
    if (!canNotify(context)) {
        return
    }
    val channel = NotificationChannel(FENCING_CHANNEL, "Fencing streams", NotificationManager.IMPORTANCE_LOW)
    channel.description = "When a men's foil stream you follow goes live"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val id = NOTIFICATION_BASE + (tournamentKey(tournament).hashCode() and 0xFFFFF) * 3
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val details = listOf(tournament.meet, tournament.place, tournament.todayNote, tournament.streamName)
        .filter { it.isNotBlank() }.joinToString(" · ")
    val builder = NotificationCompat.Builder(context, FENCING_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle("Streaming now: ${tournament.name}")
        .setContentText(details)
        .setSilent(true)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, open, flags))
    val watch = Intent(Intent.ACTION_VIEW, Uri.parse(tournament.streamUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    builder.addAction(0, "Watch", PendingIntent.getActivity(context, id + 1, watch, flags))

    // Results only when there's a page for them
    if (tournament.resultsUrl.isNotBlank()) {
        val results = Intent(Intent.ACTION_VIEW, Uri.parse(tournament.resultsUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        builder.addAction(0, "Results", PendingIntent.getActivity(context, id + 2, results, flags))
    }
    NotificationManagerCompat.from(context).notify(id, builder.build())
}

// Book the 7 AM run (KEEP leaves a booked one alone, used when the app opens)
fun scheduleFencing(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
    val delay = delayUntilNext(ZonedDateTime.now(), MORNING_HOUR, 0)
    val request = OneTimeWorkRequestBuilder<FencingWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
}

// Search right away (when fencing is turned on, or from Search now), then check today
suspend fun searchNow(context: Context): String {
    val result = searchTournaments(context)
    checkToday(context)
    return result
}
