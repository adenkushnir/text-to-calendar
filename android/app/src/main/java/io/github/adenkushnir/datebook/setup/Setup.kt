// Where each setup step stands, the free Claude key check, and the sample prompt shown at the end of setup
package io.github.adenkushnir.datebook.setup
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.places.hasBackgroundLocation
import io.github.adenkushnir.datebook.places.hasLocationAccess
import io.github.adenkushnir.datebook.capture.hasNotificationAccess
import io.github.adenkushnir.datebook.data.suggestionOf
import io.github.adenkushnir.datebook.pipeline.Event
import io.github.adenkushnir.datebook.pipeline.IncomingText
import io.github.adenkushnir.datebook.pipeline.classifyError
import io.github.adenkushnir.datebook.pipeline.makeClient
import io.github.adenkushnir.datebook.prompts.canNotify
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
private const val PREFS_NAME = "datebook-setup"
private const val FINISHED = "finished"
private const val SAMPLE_TEXT = "dinner fri 7pm at joes"

// Where each step stands; the first three are required before Home opens, the rest can be done later in Settings
// (location is only asked for in Settings → Places & travel)
data class SetupStatus(
    val access: Boolean = false,
    val notifications: Boolean = false,
    val key: Boolean = false,
    val battery: Boolean = false,
    val calendar: Boolean = false,
    val mapsKey: Boolean = false,
    val location: Boolean = false,
    val backgroundLocation: Boolean = false,
) {
    val requiredDone = access && notifications && key
}

// Read where every setup step stands right now
fun readSetupStatus(context: Context, graph: AppGraph): SetupStatus {
    val power = context.getSystemService(PowerManager::class.java)
    val calendarPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
    return SetupStatus(
        access = hasNotificationAccess(context),
        notifications = canNotify(context),
        key = graph.keyStore.load() != null,
        battery = power.isIgnoringBatteryOptimizations(context.packageName),
        calendar = calendarPermission == PackageManager.PERMISSION_GRANTED,
        mapsKey = graph.mapsKeyStore.load() != null,
        location = hasLocationAccess(context),
        backgroundLocation = hasBackgroundLocation(context),
    )
}

// Return true once setup has been finished on this phone
fun setupFinished(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(FINISHED, false)
}

// Remember that setup was finished, so it isn't shown again
fun markSetupFinished(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(FINISHED, true).apply()
}

// Check a Claude key by asking Anthropic for its list of models (free); returns null if it works, or the problem
suspend fun checkClaudeKey(apiKey: String): String? {
    // Ask off the main thread, and describe any failure in words
    try {
        withContext(Dispatchers.IO) { makeClient(apiKey.trim()).models().list() }
        return null
    } catch (error: Exception) {  // a rejected key, no internet, or anything else
        return classifyError(error).line.removePrefix("date found, ")
    }
}

// Save a sample "Dinner at Joe's" suggestion for this Friday at 7 PM and show its prompt, without calling Claude
suspend fun showSamplePrompt(context: Context) {
    val graph = AppGraph.get(context)
    val zone = ZoneId.systemDefault()
    val friday = LocalDate.now(zone).with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
    val event = Event("Dinner at Joe's (sample)", "${friday}T19:00", location = "Joe's", phoneTimezone = zone.id)
    val incoming = IncomingText("Datebook sample", SAMPLE_TEXT, LocalDateTime.now(zone), "sample")
    val suggestion = suggestionOf(event, incoming, System.currentTimeMillis())
    val id = graph.dao.saveSuggestion(suggestion)
    graph.notifier.show(suggestion.copy(id = id), SAMPLE_TEXT)
}
