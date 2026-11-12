// Creates the app's shared pieces once: database, key stores, app and calendar switches, summary and travel settings,
// prompts, Claude client and the pipeline
package io.github.adenkushnir.datebook
import android.content.Context
import com.anthropic.client.AnthropicClient
import io.github.adenkushnir.datebook.calendar.CalendarSwitches
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.capture.AppSwitches
import io.github.adenkushnir.datebook.data.DatebookDatabase
import io.github.adenkushnir.datebook.pipeline.ApiKeyStore
import io.github.adenkushnir.datebook.pipeline.TextPipeline
import io.github.adenkushnir.datebook.pipeline.extractEvents
import io.github.adenkushnir.datebook.pipeline.makeClient
import io.github.adenkushnir.datebook.places.TravelSettings
import io.github.adenkushnir.datebook.places.findPlace
import io.github.adenkushnir.datebook.prompts.SuggestionNotifier
import io.github.adenkushnir.datebook.settings.ModelChoice
import io.github.adenkushnir.datebook.summary.SummarySettings

// Holds the shared pieces; get one with AppGraph.get(context)
class AppGraph private constructor(context: Context) {
    val dao = DatebookDatabase.get(context).dao()
    val keyStore = ApiKeyStore(context)
    val mapsKeyStore = ApiKeyStore(context, "maps")
    val appSwitches = AppSwitches(context)
    val notifier = SuggestionNotifier(context)
    val modelChoice = ModelChoice(context)
    val calendarSwitches = CalendarSwitches(context)
    val summarySettings = SummarySettings(context)
    val travelSettings = TravelSettings(context)
    private var client: Pair<String, AnthropicClient>? = null
    val pipeline = TextPipeline(
        dao = dao,
        apiKey = { keyStore.load() },
        extract = { key, incoming, zone -> extractEvents(clientFor(key), incoming, zone, modelChoice.get()) },
        onRetrySaved = { scheduleRetry(context, it) },
        onSuggested = { suggestion, incoming -> notifier.show(suggestion, incoming.text) },
        calendar = { from, to -> calendarEvents(context, from, to, calendarSwitches.switchedOff()) },
        findPlace = { place -> findPlace(context, mapsKeyStore.load(), travelSettings.home(), place) },
    )

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
