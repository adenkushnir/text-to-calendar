// Runs a text through Datebook: date check, Claude, already-started check, then saves suggestions or a retry
package io.github.adenkushnir.datebook.pipeline
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.alreadyOnCalendar
import io.github.adenkushnir.datebook.calendar.clashText
import io.github.adenkushnir.datebook.calendar.clashesFor
import io.github.adenkushnir.datebook.data.DatebookDao
import io.github.adenkushnir.datebook.data.RetryText
import io.github.adenkushnir.datebook.data.SeenText
import io.github.adenkushnir.datebook.data.Suggestion
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
private const val DAY = 24L * 60 * 60 * 1000

// One text's result: events to offer, their saved suggestion ids, the lines describing what happened, retry, and what
// went wrong asking Claude (null when nothing did)
data class Outcome(
    val events: List<Event>,
    val lines: List<String>,
    val retry: Boolean,
    val suggestionIds: List<Long> = emptyList(),
    val failure: Failure? = null,
)

// What sharing something to Datebook found: the saved suggestions, or a note saying why there are none, whether
// trying again might help, and whether the Claude key needs fixing
data class ShareResult(
    val suggestions: List<Suggestion>,
    val note: String = "",
    val canRetry: Boolean = false,
    val needsKey: Boolean = false,
)

// The text pipeline; the Claude call, key, zone, clock and calendar are passed in so tests can replace them
class TextPipeline(
    private val dao: DatebookDao,
    private val apiKey: () -> String?,
    private val extract: (apiKey: String, incoming: IncomingText, phoneZone: String) -> List<Event>,
    private val phoneZone: () -> String = { ZoneId.systemDefault().id },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onRetrySaved: (atMillis: Long) -> Unit = {},
    private val onSuggested: (suggestion: Suggestion, incoming: IncomingText) -> Unit = { _, _ -> },
    private val calendar: (fromMillis: Long, toMillis: Long) -> List<CalendarEvent> = { _, _ -> emptyList() },
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

    // Work out one text: the events to offer, the lines describing what happened, and whether to try again later;
    // checkDate is false for things you shared on purpose, which always go to Claude
    suspend fun describe(incoming: IncomingText, checkDate: Boolean = true): Outcome {
        // Texts with no date never leave the phone
        if (checkDate && !passesDateCheck(incoming)) {
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
            return Outcome(emptyList(), listOf(failure.line) + lines, failure.retry, failure = failure)
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

            // Don't offer plans that are already on the calendar
            if (alreadyOnCalendar(event, calendarAround(event))) {
                lines.add("already on calendar, skipped: ${eventDetails(event)}")
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

    // Handle something you long-pressed or shared: it skips the date check and the "seen" list, its suggestions are
    // saved as waiting but not announced (the share sheet shows them), and failures aren't queued for a retry
    suspend fun share(incoming: IncomingText): ShareResult {
        // Without a key there is nothing to ask
        if (apiKey().isNullOrBlank()) {
            return ShareResult(emptyList(), "Add your Claude API key in Datebook first.", needsKey = true)
        }
        val outcome = describe(incoming, checkDate = false)
        val failure = outcome.failure

        // Say what went wrong asking Claude
        if (failure != null) {
            return when {
                failure.problem == PROBLEM_API_KEY -> ShareResult(emptyList(),
                    "Claude didn't accept your API key. Check it in Settings.", needsKey = true)
                failure.retry -> ShareResult(emptyList(), "Couldn't reach Claude. Check your internet and try again.",
                    canRetry = true)
                else -> ShareResult(emptyList(), "Something went wrong asking Claude. Try again.", canRetry = true)
            }
        }
        val ids = saveSuggestions(outcome.events, incoming, announce = false)

        // Say why nothing was found: the plans are already on the calendar, are over, or there were none
        if (ids.isEmpty()) {
            val note = when {
                outcome.lines.any { it.startsWith("already on calendar") } -> "That's already on your calendar."
                outcome.lines.any { it.startsWith("already started") } -> "That plan has already started."
                else -> "No plans found in this."
            }
            return ShareResult(emptyList(), note)
        }
        return ShareResult(ids.mapNotNull { dao.suggestion(it) })
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

    // Clear history: delete ignored suggestions and added plans that are already over, keeping anything still waiting
    // or coming up (Next up and Coming up); returns how many were deleted
    suspend fun clearHistory(): Int {
        val ignored = dao.suggestionsWithStatus(Suggestion.STATUS_IGNORED)
        val over = dao.suggestionsWithStatus(Suggestion.STATUS_ADDED).filter { hasStarted(it.toEvent(), now()) }
        dao.deleteWithStatus(Suggestion.STATUS_IGNORED)

        // Delete each added plan that's over
        for (suggestion in over) {
            dao.deleteSuggestion(suggestion.id)
        }
        return ignored.size + over.size
    }

    // Delete fingerprints older than a week and suggestions older than a year
    suspend fun cleanUp() {
        dao.deleteSeenBefore(now() - KEEP_SEEN_MILLIS)
        dao.deleteSuggestionsBefore(now() - KEEP_SUGGESTIONS_MILLIS)
    }

    // Save each event as a waiting suggestion, announce it unless told not to (the app shows its prompt), and return
    // their ids
    private suspend fun saveSuggestions(events: List<Event>, incoming: IncomingText, announce: Boolean = true):
        List<Long> {
        val ids = mutableListOf<Long>()

        // Save and announce each event
        for (event in events) {
            val clash = clashText(clashesFor(event, calendarAround(event)), event.phoneTimezone)
            val suggestion = suggestionOf(event, incoming, now(), clash)
            val id = dao.saveSuggestion(suggestion)

            // Show a prompt for it, except for shared things
            if (announce) {
                onSuggested(suggestion.copy(id = id), incoming)
            }
            ids.add(id)
        }
        return ids
    }

    // Read the calendar events from a day before a plan to a day after it, off the main thread (a day either side
    // covers all-day events, which are stored at midnight UTC)
    private suspend fun calendarAround(event: Event): List<CalendarEvent> {
        val (begin, end) = eventTimes(event)
        return withContext(Dispatchers.IO) { calendar(begin - DAY, end + DAY) }
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
