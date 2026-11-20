// Creates the app's shared pieces once: database, key stores, app and calendar switches, summary and travel settings,
// flight details, the NJ Transit account and trains, prompts, Claude client, the offline date finder, the pipeline
// and Deliberate
package io.github.adenkushnir.datebook
import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import java.time.Duration
import io.github.adenkushnir.datebook.automatic.GroupChats
import io.github.adenkushnir.datebook.calendar.CalendarSwitches
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.capture.AppSwitches
import io.github.adenkushnir.datebook.data.DatebookDatabase
import io.github.adenkushnir.datebook.deliberate.DELIBERATE_TOOLS
import io.github.adenkushnir.datebook.deliberate.Deliberate
import io.github.adenkushnir.datebook.deliberate.DeliberateSettings
import io.github.adenkushnir.datebook.deliberate.askClaude
import io.github.adenkushnir.datebook.deliberate.habitsOf
import io.github.adenkushnir.datebook.deliberate.ignoredByRules
import io.github.adenkushnir.datebook.email.promptQuote
import io.github.adenkushnir.datebook.flights.FlightStore
import io.github.adenkushnir.datebook.flights.lookupFlight
import io.github.adenkushnir.datebook.alerts.clearProblemAlert
import io.github.adenkushnir.datebook.alerts.showProblemAlert
import io.github.adenkushnir.datebook.followups.bookNextFollowUp
import io.github.adenkushnir.datebook.offline.OfflineDates
import io.github.adenkushnir.datebook.pipeline.ApiKeyStore
import io.github.adenkushnir.datebook.pipeline.TextPipeline
import io.github.adenkushnir.datebook.pipeline.extractEvents
import io.github.adenkushnir.datebook.pipeline.makeClient
import io.github.adenkushnir.datebook.places.TravelSettings
import io.github.adenkushnir.datebook.lists.showPackageMoved
import io.github.adenkushnir.datebook.places.findPlace
import io.github.adenkushnir.datebook.prompts.SuggestionNotifier
import io.github.adenkushnir.datebook.settings.ModelChoice
import io.github.adenkushnir.datebook.trains.RailDataClient
import io.github.adenkushnir.datebook.trains.TrainSettings
import io.github.adenkushnir.datebook.trains.TrainStore
import io.github.adenkushnir.datebook.summary.SummarySettings
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Holds the shared pieces; get one with AppGraph.get(context)
class AppGraph private constructor(context: Context) {
    val dao = DatebookDatabase.get(context).dao()
    val deliberateDao = DatebookDatabase.get(context).deliberateDao()
    val deliberateSettings = DeliberateSettings(context)
    val keyStore = ApiKeyStore(context)
    val mapsKeyStore = ApiKeyStore(context, "maps")
    val appSwitches = AppSwitches(context)
    val notifier = SuggestionNotifier(context)
    val modelChoice = ModelChoice(context)
    val calendarSwitches = CalendarSwitches(context)
    val summarySettings = SummarySettings(context)
    val travelSettings = TravelSettings(context)
    val flightsKeyStore = ApiKeyStore(context, "flights")
    val flightStore = FlightStore(context)
    val njtUserStore = ApiKeyStore(context, "njt-user")
    val njtPasswordStore = ApiKeyStore(context, "njt-password")
    private val njtTokenStore = ApiKeyStore(context, "njt-token")
    val trainSettings = TrainSettings(context)
    val trainStore = TrainStore(context)
    val groupChats = GroupChats(context)
    val offlineDates by lazy { OfflineDates() }
    val railData = RailDataClient({ njtUserStore.load() }, { njtPasswordStore.load() }, { njtTokenStore.load() },
        { njtTokenStore.save(it) })
    private var client: Pair<String, AnthropicClient>? = null
    private var webClient: Pair<String, AnthropicClient>? = null
    val pipeline = TextPipeline(
        dao = dao,
        apiKey = { keyStore.load() },
        extract = { key, incoming, zone ->
            val habits = habitsOf(deliberateDao.rulesNow())  // Deliberate's habits, like "dinners last 2 hours"
            extractEvents(clientFor(key), incoming.copy(habits = habits), zone, modelChoice.get())
        },
        onRetrySaved = { scheduleRetry(context, it) },
        onSuggested = { suggestion, incoming ->
            if (incoming.onScreen) notifier.showQuiet(suggestion) else notifier.show(suggestion, promptQuote(incoming))
        },
        calendar = { from, to -> calendarEvents(context, from, to, calendarSwitches.switchedOff()) },
        findPlace = { place -> findPlace(context, mapsKeyStore.load(), travelSettings.home(), place) },
        lookupFlight = { flight, date -> lookupFlight(flightsKeyStore.load(), flight, date)?.also {
            flightStore.save(date, it)  // remembered for the card and the checks
        } },
        onWithdrawn = { notifier.cancel(it) },
        onPackageMoved = { title, newDay, oldDay -> showPackageMoved(context, title, newDay, oldDay) },
        guessTimes = { text, receivedAt, zone -> offlineDates.find(text, receivedAt, ZoneId.of(zone)) },
        onFollowUpsSaved = { CoroutineScope(Dispatchers.IO).launch { bookNextFollowUp(context) } },
        // Show a problem's quiet alert when it reaches its limit, and clear it once it's fixed
        onAlert = { kind, showing ->
            if (showing) showProblemAlert(context, kind) else clearProblemAlert(context, kind)
        },
        keepTexts = { deliberateSettings.keepTexts() },
        ignored = { sender, group, title, kind -> ignoredByRules(deliberateDao.rules(), sender, group, title, kind) },
    )
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)  // work that outlives the screen it began on

    // Run a Settings page's long task on the app's scope, so it keeps going after you leave the page; the page still
    // gets the result while it's open
    suspend fun <T> keepGoing(task: suspend () -> T): T {
        return appScope.async { task() }.await()
    }
    val deliberate by lazy {
        Deliberate(context) { system, turns ->
            askClaude(webClientFor(keyStore.load().orEmpty()), modelChoice.get(), system, DELIBERATE_TOOLS, turns)
        }
    }

    // Return a Claude client for the key, reusing it while the key stays the same
    @Synchronized
    private fun clientFor(key: String): AnthropicClient {
        val current = client

        // Reuse the client made for this same key
        if (current != null && current.first == key) {
            return current.second
        }
        val made = makeClient(key)
        client = Pair(key, made)
        return made
    }

    // Return a Claude client that waits up to 3 minutes (Deliberate's web searches take a while), reusing it while the
    // key stays the same
    @Synchronized
    private fun webClientFor(key: String): AnthropicClient {
        val current = webClient

        // Reuse the client made for this same key
        if (current != null && current.first == key) {
            return current.second
        }
        val made = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofMinutes(3)).build()
        webClient = Pair(key, made)
        return made
    }

    // Keeps the one shared AppGraph
    companion object {
        @Volatile private var instance: AppGraph? = null

        // Return the shared AppGraph, creating it the first time
        fun get(context: Context): AppGraph {
            return instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
        }
    }
}
