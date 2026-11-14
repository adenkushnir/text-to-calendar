// Runs a text through Datebook: date check, Claude, already-started check, then saves suggestions, or when Claude
// can't read it, a retry and an offline guess that Claude refines later
package io.github.adenkushnir.datebook.pipeline
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.alreadyOnCalendar
import io.github.adenkushnir.datebook.calendar.clashText
import io.github.adenkushnir.datebook.calendar.clashesFor
import io.github.adenkushnir.datebook.data.DatebookDao
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_LOOSE
import io.github.adenkushnir.datebook.data.firstNudge
import io.github.adenkushnir.datebook.data.RetryText
import io.github.adenkushnir.datebook.data.SeenText
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.suggestionOf
import io.github.adenkushnir.datebook.flights.FlightInfo
import io.github.adenkushnir.datebook.flights.flightTitle
import io.github.adenkushnir.datebook.flights.zonedText
import io.github.adenkushnir.datebook.offline.GuessedTime
import io.github.adenkushnir.datebook.offline.guessTitle
import io.github.adenkushnir.datebook.places.FoundPlace
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
const val RETRY_MILLIS = 60L * 1000
const val GIVE_UP_MILLIS = 24L * 60 * 60 * 1000
const val KEY_RETRY_MILLIS = 60L * 60 * 1000  // key, credit and other problems that don't clear up by themselves
const val REPEAT_MILLIS = 60L * 60 * 1000
const val KEEP_SEEN_MILLIS = 7L * 24 * 60 * 60 * 1000
const val KEEP_SUGGESTIONS_MILLIS = 365L * 24 * 60 * 60 * 1000
private const val DAY = 24L * 60 * 60 * 1000
private const val CHANGE_WINDOW = 14 * DAY
private const val MAX_LISTED = 40
private const val MAX_GUESSES = 3
private val TITLE_WORDS = Regex("[a-z]{4,}")
private val COMMON_WORDS = setOf("with", "plan", "plans", "about", "some", "time", "sometime")
private val LOCAL_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

// One text's result: events to offer, their saved suggestion ids, the lines describing what happened, retry, what
// went wrong asking Claude (null when nothing did), whether Claude couldn't read it at all (any failure, or no key),
// and the loose plans and bills it found, which become follow-ups instead of plans
data class Outcome(
    val events: List<Event>,
    val lines: List<String>,
    val retry: Boolean,
    val suggestionIds: List<Long> = emptyList(),
    val failure: Failure? = null,
    val unread: Boolean = false,
    val followUps: List<Event> = emptyList(),
)

// What sharing something to Datebook found: the saved suggestions, or a note saying why there are none, whether
// trying again might help, and whether the Claude key needs fixing
data class ShareResult(
    val suggestions: List<Suggestion>,
    val note: String = "",
    val canRetry: Boolean = false,
    val needsKey: Boolean = false,
)

// The text pipeline; the Claude call, key, zone, clock, calendar, place lookup, flight lookup and offline date finder
// are passed in so tests can replace them
class TextPipeline(
    private val dao: DatebookDao,
    private val apiKey: () -> String?,
    private val extract: (apiKey: String, incoming: IncomingText, phoneZone: String) -> List<Event>,
    private val phoneZone: () -> String = { ZoneId.systemDefault().id },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onRetrySaved: (atMillis: Long) -> Unit = {},
    private val onSuggested: (suggestion: Suggestion, incoming: IncomingText) -> Unit = { _, _ -> },
    private val calendar: (fromMillis: Long, toMillis: Long) -> List<CalendarEvent> = { _, _ -> emptyList() },
    private val findPlace: (place: String) -> FoundPlace? = { null },
    private val lookupFlight: (flight: String, date: String) -> FlightInfo? = { _, _ -> null },
    private val onWithdrawn: (suggestionId: Long) -> Unit = {},
    private val guessTimes: (text: String, receivedAt: LocalDateTime, zone: String) -> List<GuessedTime> =
        { _, _, _ -> emptyList() },
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
        if (!worthAsking(incoming)) {
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
        if (checkDate && !worthAsking(incoming)) {
            return Outcome(emptyList(), listOf("no date"), false)
        }
        val key = apiKey()

        // Without an API key we can only say a date was found
        if (key.isNullOrBlank()) {
            return Outcome(emptyList(), listOf("date found, no API key"), false, unread = true)
        }
        val zone = phoneZone()
        val lines = mutableListOf<String>()
        val upcoming = if (soundsLikeChange(incoming.text)) upcomingEvents(incoming) else emptyList()  // changes only
        val asked = incoming.copy(upcoming = upcoming)

        // Ask Claude off the main thread, and turn errors into a log line, a retry and an alert count
        val found = try {
            withContext(Dispatchers.IO) { extract(key, asked, zone) }
        } catch (error: Exception) {  // any failure from the API, the network or reading the reply
            val failure = classifyError(error)

            // Count the problem toward its alert, and note in the log when the alert appears
            if (failure.problem != null && health.failed(failure.problem)) {
                lines.add("alert: ${ALERT_TITLES.getValue(failure.problem)}")
            }
            return Outcome(emptyList(), listOf(failure.line) + lines, failure.retry, failure = failure,
                unread = true)
        }
        clearApiAlerts(lines)

        // Say so when Claude found no events
        if (found.isEmpty()) {
            return Outcome(emptyList(), listOf("date found, no event") + lines, false)
        }
        val toOffer = mutableListOf<Event>()
        val followUps = mutableListOf<Event>()

        // Keep each event that hasn't started yet, and describe every one
        for (claudeEvent in found) {
            // Loose plans and bills become follow-ups, not plans
            if (claudeEvent.kind != KIND_EVENT) {
                followUps.add(claudeEvent)
                lines.add("${claudeEvent.kind}: ${eventDetails(claudeEvent)}")
                continue
            }
            val (matched, changed) = matchChange(nameGroupBirthday(claudeEvent, incoming), upcoming)
            val event = withFlightTimes(matched)

            // Don't offer events that have already started
            if (hasStarted(event, now())) {
                lines.add("already started, skipped: ${eventDetails(event)}")
                continue
            }

            // Don't offer new plans that are already on the calendar or already waiting (a second email or text about
            // the same plan), or moves the calendar already has
            if (changed == null && alreadyOnCalendar(event, calendarAround(event))) {
                lines.add("already on calendar, skipped: ${eventDetails(event)}")
                continue
            } else if (changed == null && alreadyWaiting(event)) {
                lines.add("already waiting, skipped: ${eventDetails(event)}")
                continue
            } else if (event.change == CHANGE_MOVED && changed?.begin == eventTimes(event).first) {
                lines.add("already moved, skipped: ${eventDetails(event)}")
                continue
            }
            val placed = withFullAddress(event)
            toOffer.add(placed)
            val kind = placed.change.ifBlank { "event" }  // "moved" or "cancelled" for changes
            lines.add("$kind: ${eventDetails(placed)}")
        }
        return Outcome(toOffer, lines, false, followUps = followUps)
    }

    // Handle a new text: describe it, then save its suggestions; when Claude couldn't read it, save it to try again
    // (every minute once online, or every hour for key and other problems) and leave quiet guesses waiting on Home
    suspend fun handle(incoming: IncomingText): Outcome {
        val outcome = describe(incoming)

        // Keep texts Claude couldn't read, and guess their plans on the phone meanwhile
        if (outcome.unread) {
            val time = now()
            val wait = if (outcome.retry) RETRY_MILLIS else KEY_RETRY_MILLIS  // no internet clears up soonest
            val retryId = dao.saveRetry(RetryText(sender = incoming.sender, text = incoming.text,
                receivedAt = incoming.receivedAt.toString(), app = incoming.app, conversation = incoming.group,
                savedAt = time,
                nextTry = time + wait))
            onRetrySaved(time + wait)
            val guesses = saveGuesses(incoming, retryId)
            val again = if (outcome.retry) "will try again every minute once online" else "will try again every hour"
            val guessed = "offline guesses: ${guesses.size}"
            return outcome.copy(lines = outcome.lines + again + guessed, suggestionIds = guesses)
        }
        saveFollowUps(outcome.followUps, incoming)
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
        val saved = saveFollowUps(outcome.followUps, incoming)

        // Say why nothing was found: the plans are already on the calendar, are over, or there were none
        if (ids.isEmpty()) {
            val note = when {
                saved > 0 -> "Saved on Datebook's home screen, under Bills due or Loose plans."
                outcome.lines.any { it.startsWith("already on calendar") } -> "That's already on your calendar."
                outcome.lines.any { it.startsWith("already waiting") } -> "That's already waiting for you in Datebook."
                outcome.lines.any { it.startsWith("already started") } -> "That plan has already started."
                else -> "No plans found in this."
            }
            return ShareResult(emptyList(), note)
        }
        return ShareResult(ids.mapNotNull { dao.suggestion(it) })
    }

    // Try the saved texts that are due, giving up on any saved over a day ago (their guesses stay, unchecked); when
    // Claude reads one, its guesses are replaced; returns what happened to each
    suspend fun retryDue(): List<Outcome> {
        val results = mutableListOf<Outcome>()

        // Try each saved text that is due
        for (retry in dao.dueRetries(now())) {
            val receivedAt = LocalDateTime.parse(retry.receivedAt)
            val incoming = IncomingText(retry.sender, retry.text, receivedAt, retry.app, retry.conversation)
            val outcome = describe(incoming)

            // Wait for the next round if it still fails and isn't too old yet
            if (outcome.unread && now() - retry.savedAt < GIVE_UP_MILLIS) {
                dao.rescheduleRetry(retry.id, now() + if (outcome.retry) RETRY_MILLIS else KEY_RETRY_MILLIS)
                continue
            }
            dao.deleteRetry(retry.id)

            // Give up on a text that still can't be read; its guesses stay on Home as not checked
            if (outcome.unread) {
                results.add(outcome.copy(lines = outcome.lines.map { "gave up ($it)" }))
                continue
            }
            saveFollowUps(outcome.followUps, incoming)
            results.add(outcome.copy(suggestionIds = refineGuesses(retry.id, outcome.events, incoming)))
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
            // A change to a plan that's still waiting updates that plan instead of making a new one
            if (event.calendarEventId < 0) {
                updateWaiting(-event.calendarEventId, event, incoming)?.let { ids.add(it) }
                continue
            }
            val others = calendarAround(event).filter { it.eventId != event.calendarEventId }  // not the one it moves
            val clashes = if (event.change == CHANGE_CANCELLED) emptyList() else clashesFor(event, others)
            val clash = clashText(clashes, event.phoneTimezone)
            val suggestion = suggestionOf(event, incoming, now(), clash)
            val id = dao.saveSuggestion(suggestion)
            closeLoosePlans(event, incoming)

            // Show a prompt for it, except for shared things
            if (announce) {
                onSuggested(suggestion.copy(id = id), incoming)
            }
            ids.add(id)
        }
        return ids
    }

    // Save loose plans and bills as follow-ups (not the same one twice while it's open, and no bill already past due),
    // with their first nudge or reminder booked; returns how many were saved
    private suspend fun saveFollowUps(found: List<Event>, incoming: IncomingText): Int {
        val zone = zoneOf(phoneZone()) ?: ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        var saved = 0

        // Save each one that's new and not over
        for (event in found) {
            val day = event.start.take(10)
            val past = runCatching { LocalDate.parse(day) < today }.getOrDefault(false)
            val open = dao.openFollowUps(event.kind).any {
                it.title.equals(event.title, ignoreCase = true) && it.date == day
            }

            // Skip repeats and bills already past due
            if (open || (past && event.kind != KIND_LOOSE)) {
                continue
            }
            dao.saveFollowUp(FollowUp(kind = event.kind, title = event.title, date = day, amount = event.amount,
                sender = incoming.sender, app = incoming.app, conversation = incoming.group,
                status = FollowUp.OPEN, createdAt = now(), nextNudge = firstNudge(event.kind, day, now(), zone),
                nudges = 0))
            saved += 1
        }
        return saved
    }

    // Close the open loose plans from a chat once a real plan about the same thing arrives from it ("Lunch with Sam"
    // closes when "Lunch at Luigi's" comes in): they share a word of 4 letters or more
    private suspend fun closeLoosePlans(plan: Event, incoming: IncomingText) {
        val words = TITLE_WORDS.findAll(plan.title.lowercase()).map { it.value }.toSet() - COMMON_WORDS

        // Check each open loose plan from the same chat
        for (loose in dao.openFollowUps(KIND_LOOSE)) {
            val sameChat = loose.app == incoming.app && loose.conversation == incoming.group &&
                (incoming.group.isNotBlank() || loose.sender == incoming.sender)
            val shared = TITLE_WORDS.findAll(loose.title.lowercase()).any { it.value in words }

            // Pinned down: the loose plan is done
            if (sameChat && shared) {
                dao.setFollowUpStatus(loose.id, FollowUp.DONE)
            }
        }
    }

    // Save quiet guesses for a text Claude couldn't read: "Plan with Sam" at each date or time found on the phone (at
    // most 3, none already started), linked to its retry so Claude can replace them later; returns their ids
    private suspend fun saveGuesses(incoming: IncomingText, retryId: Long): List<Long> {
        val zone = phoneZone()
        val times = withContext(Dispatchers.IO) { guessTimes(incoming.text, incoming.receivedAt, zone) }
        val ids = mutableListOf<Long>()

        // Save each time found as a waiting guess, with no prompt
        for (time in times.take(MAX_GUESSES)) {
            val event = Event(guessTitle(incoming), time.start, allDay = time.allDay, phoneTimezone = zone)

            // Skip times that are already over
            if (hasStarted(event, now())) {
                continue
            }
            val clash = clashText(clashesFor(event, calendarAround(event)), zone)
            ids.add(dao.saveSuggestion(suggestionOf(event, incoming, now(), clash).copy(retryId = retryId)))
        }
        return ids
    }

    // Once Claude has read a text: its waiting guesses are replaced quietly by Claude's plans; a guess you already
    // added gets a "Move …?" prompt only when Claude found another time or a place; with no guesses, Claude's plans
    // are announced as usual; returns the ids of the plans saved
    private suspend fun refineGuesses(retryId: Long, events: List<Event>, incoming: IncomingText): List<Long> {
        val guesses = dao.guessesFor(retryId)

        // Without guesses, the plans are announced like any new plan
        if (guesses.isEmpty()) {
            return saveSuggestions(events, incoming)
        }
        val left = events.toMutableList()
        val ids = mutableListOf<Long>()

        // Swap each guess for Claude's version
        for (guess in guesses) {
            // A guess still waiting just goes; Claude's plans take its place below
            if (guess.status == Suggestion.STATUS_WAITING) {
                dao.deleteSuggestion(guess.id)
                continue
            }

            // Only guesses you added need their calendar event updated
            if (guess.status != Suggestion.STATUS_ADDED) {
                continue
            }
            val guessBegin = eventTimes(guess.toEvent()).first
            val event = left.minByOrNull { abs(eventTimes(it).first - guessBegin) } ?: continue  // Claude found none
            left.remove(event)
            val sameTime = event.start == guess.start && event.allDay == guess.allDay
            val onCalendar = calendarAround(guess.toEvent()).firstOrNull {
                it.title == guess.title && abs(it.begin - guessBegin) < 60_000
            }

            // Ask to update the calendar event when the time differs or Claude found a place (and it's still there)
            if ((!sameTime || event.location.isNotBlank()) && onCalendar != null) {
                val move = event.copy(title = guess.title, change = CHANGE_MOVED, calendarEventId = onCalendar.eventId)
                ids.addAll(saveSuggestions(listOf(move), incoming))
            }
        }
        return ids + saveSuggestions(left, incoming, announce = false)
    }

    // Return true if the same plan (same title, ignoring case, at the same time) is already waiting for you
    private suspend fun alreadyWaiting(event: Event): Boolean {
        return dao.suggestionsWithStatus(Suggestion.STATUS_WAITING).any { plan ->
            plan.change.isBlank() && plan.title.trim().equals(event.title.trim(), ignoreCase = true) &&
                plan.start == event.start && plan.allDay == event.allDay
        }
    }

    // Return true if a text should go to Claude: it passes the date check, or (outside group chats, which always need a
    // clear date) it sounds like a change and there are calendar events it could change
    private suspend fun worthAsking(incoming: IncomingText): Boolean {
        // Texts with a date always go
        if (passesDateCheck(incoming)) {
            return true
        }
        return incoming.group.isBlank() && soundsLikeChange(incoming.text) && upcomingEvents(incoming).isNotEmpty()
    }

    // Fill a flight plan in from live flight data: its title with the route, its departure and arrival in the
    // departure airport's time (the delayed times when it's already delayed, else the scheduled ones), and the airport
    // as its place; it stays as Claude wrote it without data
    private suspend fun withFlightTimes(event: Event): Event {
        // Only flights are looked up
        if (event.flight.isBlank()) {
            return event
        }
        val info = withContext(Dispatchers.IO) { lookupFlight(event.flight, event.start.take(10)) } ?: return event
        val departs = info.departBest ?: info.departScheduled ?: return event  // no times to fill in
        val zone = zoneOf(info.fromZone) ?: zoneOf(event.phoneTimezone) ?: ZoneId.systemDefault()
        val end = (info.arriveBest ?: info.arriveScheduled)?.let { zonedText(it, zone) }.orEmpty()  // empty if unknown
        return event.copy(title = flightTitle(info), start = zonedText(departs, zone), end = end, allDay = false,
            timezone = zone.id, location = info.fromName.ifBlank { event.location })
    }

    // Swap a new plan's place, like "Luigi's", for its full address and coordinates, off the main thread; it stays as
    // written when there is no Maps key or no match (changes keep the calendar event's own place)
    private suspend fun withFullAddress(event: Event): Event {
        // Only new plans that name a place are looked up
        if (event.location.isBlank() || event.change.isNotBlank()) {
            return event
        }
        val found = withContext(Dispatchers.IO) { findPlace(event.location) } ?: return event  // stays as written
        return event.copy(location = found.line, placeLat = found.lat, placeLng = found.lng)
    }

    // List what a change could refer to: this chat's waiting plans first (as negative ids), then the calendar events
    // of the next 2 weeks, at most 40 in all, read off the main thread
    private suspend fun upcomingEvents(incoming: IncomingText): List<CalendarEvent> {
        val from = now()
        val waiting = waitingInChat(incoming).map { plan ->
            val (begin, end) = eventTimes(plan.toEvent())
            CalendarEvent(-plan.id, 0, plan.title, begin, end, plan.allDay, plan.location)
        }
        return withContext(Dispatchers.IO) { (waiting + calendar(from, from + CHANGE_WINDOW)).take(MAX_LISTED) }
    }

    // The new plans still waiting from the same chat (the same app and group, or the same person one-to-one) made in
    // the last week and not started yet (calendar-change prompts don't count); shared things have no chat
    private suspend fun waitingInChat(incoming: IncomingText): List<Suggestion> {
        // Shared things don't belong to a chat
        if (isShared(incoming.app)) {
            return emptyList()
        }
        val since = now() - 7 * DAY
        return dao.suggestionsWithStatus(Suggestion.STATUS_WAITING).filter {
            it.change.isBlank() && it.app == incoming.app && it.conversation == incoming.group &&
                it.createdAt >= since && (incoming.group.isNotBlank() || it.sender == incoming.sender) &&
                !hasStarted(it.toEvent(), now())
        }
    }

    // Update a waiting plan that changed in the chat: a move gets the new times (remembering the old start, once), a
    // cancellation takes it off the waiting list; returns the plan's id when it's still waiting
    private suspend fun updateWaiting(id: Long, event: Event, incoming: IncomingText): Long? {
        val plan = dao.suggestion(id) ?: return null  // already gone

        // A cancelled plan stops waiting and its prompt goes away
        if (event.change == CHANGE_CANCELLED) {
            dao.setStatus(id, Suggestion.STATUS_IGNORED)
            onWithdrawn(id)
            return null
        }
        val clash = clashText(clashesFor(event, calendarAround(event)), event.phoneTimezone)
        val moved = plan.copy(start = event.start, end = event.end, allDay = event.allDay, timezone = event.timezone,
            clash = clash, previousStart = plan.previousStart.ifBlank { plan.start })
        dao.updateSuggestion(moved)
        onSuggested(moved, incoming)  // replaces its prompt
        return id
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

// Match an event Claude says moves or cancels a calendar event to that event: it takes the calendar's title and id,
// a cancellation keeps the event's own time, and a move without an end keeps the event's length; returns the event and
// the calendar event it changes (null for new plans, or when Claude's number doesn't match a listed event)
fun matchChange(event: Event, upcoming: List<CalendarEvent>): Pair<Event, CalendarEvent?> {
    val changed = upcoming.getOrNull(event.calendarRef - 1)

    // A plan that changes nothing on the list is a new plan
    if (event.change.isBlank() || changed == null) {
        return Pair(event.copy(change = "", calendarRef = 0), null)
    }
    val zone = zoneOf(event.phoneTimezone) ?: ZoneId.systemDefault()
    val matched = event.copy(title = changed.title.ifBlank { event.title }, calendarEventId = changed.eventId,
        allDay = changed.allDay, timezone = "")

    // A cancellation keeps the calendar event's own time; a move keeps its length when no end was given
    if (matched.change == CHANGE_CANCELLED) {
        return Pair(matched.copy(start = localText(changed.begin, changed.allDay, zone),
            end = localText(changed.end, changed.allDay, zone)), changed)
    } else if (matched.end.isBlank() && !changed.allDay) {
        val length = Duration.ofMillis(changed.end - changed.begin)
        return Pair(matched.copy(end = parseLocal(matched.start).plus(length).format(LOCAL_MINUTES)), changed)
    }
    return Pair(matched, changed)
}

// A calendar time as Datebook writes times: YYYY-MM-DD for all-day events (kept at midnight UTC), else
// YYYY-MM-DDTHH:MM in the phone's zone
private fun localText(millis: Long, allDay: Boolean, zone: ZoneId): String {
    val instant = Instant.ofEpochMilli(millis)
    return if (allDay) instant.atZone(ZoneOffset.UTC).toLocalDate().toString()
    else LocalDateTime.ofInstant(instant, zone).format(LOCAL_MINUTES)
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
