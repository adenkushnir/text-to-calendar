// Creates the app's shared pieces once: database, key store, app switches, Claude client and the text pipeline
package io.github.adenkushnir.datebook
import android.content.Context
import com.anthropic.client.AnthropicClient
import io.github.adenkushnir.datebook.capture.AppSwitches
import io.github.adenkushnir.datebook.data.DatebookDatabase
import io.github.adenkushnir.datebook.pipeline.ApiKeyStore
import io.github.adenkushnir.datebook.pipeline.DEFAULT_MODEL
import io.github.adenkushnir.datebook.pipeline.TextPipeline
import io.github.adenkushnir.datebook.pipeline.extractEvents
import io.github.adenkushnir.datebook.pipeline.makeClient

// Holds the shared pieces; get one with AppGraph.get(context)
class AppGraph private constructor(context: Context) {
    val dao = DatebookDatabase.get(context).dao()
    val keyStore = ApiKeyStore(context)
    val appSwitches = AppSwitches(context)
    private var client: Pair<String, AnthropicClient>? = null
    val pipeline = TextPipeline(
        dao = dao,
        apiKey = { keyStore.load() },
        extract = { key, incoming, zone -> extractEvents(clientFor(key), incoming, zone, DEFAULT_MODEL) },
        onRetrySaved = { scheduleRetry(context, it) },
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
