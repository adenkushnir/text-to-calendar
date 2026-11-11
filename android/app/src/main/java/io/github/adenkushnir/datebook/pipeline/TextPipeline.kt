// Runs a text through Datebook: date check, Claude, already-started check, then saves suggestions or a retry
package io.github.adenkushnir.datebook.pipeline
import io.github.adenkushnir.datebook.data.DatebookDao
import io.github.adenkushnir.datebook.data.RetryText
import io.github.adenkushnir.datebook.data.SeenText
import io.github.adenkushnir.datebook.data.suggestionOf
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
const val RETRY_MILLIS = 60L * 1000
const val GIVE_UP_MILLIS = 24L * 60 * 60 * 1000
const val REPEAT_MILLIS = 60L * 60 * 1000
const val KEEP_SEEN_MILLIS = 7L * 24 * 60 * 60 * 1000
const val KEEP_SUGGESTIONS_MILLIS = 365L * 24 * 60 * 60 * 1000

// One text's result: events to offer, their saved suggestion ids, the lines describing what happened, and retry
data class Outcome(
    val events: List<Event>,
    val lines: List<String>,
    val retry: Boolean,
    val suggestionIds: List<Long> = emptyList(),
)

// The text pipeline; the Claude call, key, zone and clock are passed in so tests can replace them
class TextPipeline(
    private val dao: DatebookDao,
    private val apiKey: () -> String?,
    private val extract: (apiKey: String, incoming: IncomingText, phoneZone: String) -> List<Event>,
    private val phoneZone: () -> String = { ZoneId.systemDefault().id },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onRetrySaved: (atMillis: Long) -> Unit = {},
) {
    private val health = Health(dao)

    // Return true the first time a text is seen, or when the same sender and text come back after an hour
    suspend fun isNew(incoming: IncomingText): Boolean {
        val hash = fingerprint(incoming)
        val lastSeen = dao.seenAt(hash)
        val time = now()

        // Skip a message seen before: always when it has its own timestamp, otherwise within the last hour
        if (lastSeen != null && (incoming.exactTime || time - lastSeen < REPEAT_MILLIS)) {
            return false
        }
        dao.saveSeen(SeenText(hash, time))
        return true
    }

    // Handle a text from a notification: texts with no date are dropped before anything is saved (no trace), texts
    // already handled are skipped, and the rest are handled; returns null when the text was dropped or skipped
    suspend fun receive(incoming: IncomingText): Outcome? {
        // Drop texts with no date before the fingerprint is saved
        if (!passesDateCheck(incoming)) {
            return null
        }

        // Skip texts already handled
        if (!isNew(incoming)) {
            return null
        }
        return handle(incoming)
    }

    // Work out one text: the events to offer, the lines describing what happened, and whether to try again later
    suspend fun describe(incoming: IncomingText): Outcome {
        // Texts with no date never leave the phone
        if (!passesDateCheck(incoming)) {
            return Outcome(emptyList(), listOf("no date"), false)
        }
        val key = apiKey()

        // Without an API key we can only say a date was found
        if (key.isNullOrBlank()) {
            return Outcome(emptyList(), listOf("date found, no API key"), false)
        }
        val zone = phoneZone()
        val lines = mutableListOf<String>()

        // Ask Claude off the main thread, and turn errors into a log line, a retry and an alert count
        val found = try {
            withContext(Dispatchers.IO) { extract(key, incoming, zone) }
        } catch (error: Exception) {  // any failure from the API, the network or reading the reply
            val failure = classifyError(error)

            // Count the problem toward its alert, and note in the log when the alert appears
            if (failure.problem != null && health.failed(failure.problem)) {
                lines.add("alert: ${ALERT_TITLES.getValue(failure.problem)}")
            }
            return Outcome(emptyList(), listOf(failure.line) + lines, failure.retry)
        }
        clearApiAlerts(lines)

        // Say so when Claude found no events
        if (found.isEmpty()) {
            return Outcome(emptyList(), listOf("date found, no event") + lines, false)
        }
        val toOffer = mutableListOf<Event>()

        // Keep each event that hasn't started yet, and describe every one
        for (claudeEvent in found) {
            val event = nameGroupBirthday(claudeEvent, incoming)

            // Don't offer events that have already started
            if (hasStarted(event, now())) {
                lines.add("already started, skipped: ${eventDetails(event)}")
                continue
            }
            toOffer.add(event)
            lines.add("event: ${eventDetails(event)}")
        }
        return Outcome(toOffer, lines, false)
    }

    // Handle a new text: describe it, then save its suggestions, or save it to try again when the phone is online
    suspend fun handle(incoming: IncomingText): Outcome {
        val outcome = describe(incoming)

        // Save texts that failed for a reason that may clear up, like no internet
        if (outcome.retry) {
            val time = now()
            dao.saveRetry(RetryText(sender = incoming.sender, text = incoming.text,
                receivedAt = incoming.receivedAt.toString(), app = incoming.app, conversation = incoming.group,
                savedAt = time,
                nextTry = time + RETRY_MILLIS))
            onRetrySaved(time + RETRY_MILLIS)
            return outcome.copy(lines = outcome.lines + "will try again every minute once online")
        }
        return outcome.copy(suggestionIds = saveSuggestions(outcome.events, incoming))
    }

    // Try the saved texts that are due, giving up on any saved over a day ago; returns what happened to each
    suspend fun retryDue(): List<Outcome> {
        val results = mutableListOf<Outcome>()

        // Try each saved text that is due
        for (retry in dao.dueRetries(now())) {
            val receivedAt = LocalDateTime.parse(retry.receivedAt)
            val incoming = IncomingText(retry.sender, retry.text, receivedAt, retry.app, retry.conversation)
            val outcome = describe(incoming)

            // Wait for the next round if it still fails and isn't too old yet
            if (outcome.retry && now() - retry.savedAt < GIVE_UP_MILLIS) {
                dao.rescheduleRetry(retry.id, now() + RETRY_MILLIS)
                continue
            }
            dao.deleteRetry(retry.id)
            val lines = if (outcome.retry) outcome.lines.map { "gave up ($it)" } else outcome.lines  // still failing
            results.add(outcome.copy(lines = lines, suggestionIds = saveSuggestions(outcome.events, incoming)))
        }
        return results
    }

    // Delete fingerprints older than a week and suggestions older than a year
    suspend fun cleanUp() {
        dao.deleteSeenBefore(now() - KEEP_SEEN_MILLIS)
        dao.deleteSuggestionsBefore(now() - KEEP_SUGGESTIONS_MILLIS)
    }

    // Save each event as a waiting suggestion and return their ids
    private suspend fun saveSuggestions(events: List<Event>, incoming: IncomingText): List<Long> {
        return events.map { dao.saveSuggestion(suggestionOf(it, incoming, now())) }  // one each
    }

    // After Claude answers, clear both API alert counts, and note in the log when an alert goes away
    private suspend fun clearApiAlerts(lines: MutableList<String>) {
        // Reset each API problem kind
        for (kind in listOf(PROBLEM_API_KEY, PROBLEM_API)) {
            // Note when an alert that was showing goes away
            if (health.succeeded(kind)) {
                lines.add("alert cleared: ${ALERT_TITLES.getValue(kind)}")
            }
        }
    }
}

// Group texts reach Claude under the group's name, so "it's my birthday" comes back as "<Group>'s birthday";
// put the sender's name back in, on the phone, so it reads "Jake's birthday"
fun nameGroupBirthday(event: Event, incoming: IncomingText): Event {
    // Only group texts need the fix
    if (incoming.group.isBlank() || event.title != "${incoming.group}'s birthday") {
        return event
    }
    return event.copy(title = "${incoming.sender}'s birthday")
}

// Return true if a text passes the on-phone date check: group chats need a clear date or time
fun passesDateCheck(incoming: IncomingText): Boolean {
    return if (incoming.group.isNotBlank()) hasClearDate(incoming.text) else hasDateHint(incoming.text)  // stricter
}

// Describe one event for the log, like: Dinner | 2026-11-13T19:00 to ? | Joe's | zone America/Los_Angeles
fun eventDetails(event: Event): String {
    val end = event.end.ifBlank { "?" }  // unknown end
    val details = "${event.title} | ${event.start} to $end | ${event.location.ifBlank { "no location" }}"
    val repeat = if (event.repeat.isNotBlank()) " | repeat ${event.repeat}" else ""  // only for repeating events
    val zone = if (event.timezone.isNotBlank()) " | zone ${event.timezone}" else ""  // only when the text named one
    return details + repeat + zone
}

// Make a fingerprint of a text from its app, group, sender and text (and its own timestamp when it has one), so the
// same message is only handled once even when a conversation notification shows it again
fun fingerprint(incoming: IncomingText): String {
    val time = if (incoming.exactTime) incoming.receivedAt.toString() else ""  // only messages with their own time
    val key = "${incoming.app}|${incoming.group}|${incoming.sender}|${incoming.text}|$time"
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }  // each byte as two hex digits
}
