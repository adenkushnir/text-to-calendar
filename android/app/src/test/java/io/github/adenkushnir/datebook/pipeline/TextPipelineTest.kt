// Tests for the text pipeline with a real in-memory database and a fake Claude, like the Python main tests
package io.github.adenkushnir.datebook.pipeline
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anthropic.errors.AnthropicIoException
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.data.DatebookDao
import io.github.adenkushnir.datebook.data.DatebookDatabase
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.suggestionOf
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Runs texts through the pipeline and checks what is logged, saved, retried and counted
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextPipelineTest {
    private val newYork = "America/New_York"
    private val dinner = Event("Dinner with Sam", "2037-11-13T19:00", location = "Joe's")
    private val movie = Event("Movie", "2037-11-13T22:00")
    private val past = Event("Old dinner", "2020-01-03T19:00")
    private val incoming = IncomingText("Sam", "dinner fri 7pm at joes", LocalDateTime.of(2026, 11, 10, 9, 0), "sms")
    private lateinit var database: DatebookDatabase
    private lateinit var dao: DatebookDao
    private var clock = Instant.parse("2026-11-10T14:00:00Z").toEpochMilli()
    private val retries = mutableListOf<Long>()
    private val prompts = mutableListOf<Pair<String, String>>()
    private val asked = mutableListOf<IncomingText>()

    // Open a fresh in-memory database before each test
    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, DatebookDatabase::class.java).allowMainThreadQueries().build()
        dao = database.dao()
    }

    // Close the database after each test
    @After
    fun closeDatabase() {
        database.close()
    }

    // Build a pipeline whose Claude returns the given events, or throws the given error, with the given calendar and
    // the full addresses Google would find
    private fun pipeline(events: List<Event> = emptyList(), error: Exception? = null, key: String? = "sk-test",
        onCalendar: List<CalendarEvent> = emptyList(), places: Map<String, String> = emptyMap()): TextPipeline {
        return TextPipeline(
            dao = dao,
            apiKey = { key },
            extract = { _, text, zone ->
                asked.add(text)
                error?.let { throw it } ?: events.map { it.copy(phoneTimezone = zone) }
            },
            phoneZone = { newYork },
            now = { clock },
            onRetrySaved = { retries.add(it) },
            onSuggested = { suggestion, text -> prompts.add(Pair(suggestion.title, text.text)) },
            calendar = { from, to -> onCalendar.filter { it.begin < to && from < it.end } },
            findPlace = { places[it] },
        )
    }

    // Texts with no date are never sent to Claude
    @Test
    fun noDate() = runTest {
        val outcome = pipeline(listOf(dinner)).handle(incoming.copy(text = "lol ok"))
        assertEquals(listOf("no date"), outcome.lines)
        assertTrue(outcome.events.isEmpty())
    }

    // Without an API key the date is found but nothing is sent
    @Test
    fun noKey() = runTest {
        assertEquals(listOf("date found, no API key"), pipeline(listOf(dinner), key = null).handle(incoming).lines)
    }

    // A found event is described, offered and saved as a waiting suggestion with the phone's zone
    @Test
    fun eventFound() = runTest {
        val outcome = pipeline(listOf(dinner)).handle(incoming)
        assertEquals(listOf("event: Dinner with Sam | 2037-11-13T19:00 to ? | Joe's"), outcome.lines)
        val saved = dao.suggestion(outcome.suggestionIds.single())!!
        assertEquals("Dinner with Sam", saved.title)
        assertEquals(newYork, saved.phoneTimezone)
        assertEquals(Suggestion.STATUS_WAITING, saved.status)
        assertEquals(1, dao.watchSuggestions(Suggestion.STATUS_WAITING).first().size)
    }

    // Two plans give two suggestions, and a plan that already started is skipped
    @Test
    fun twoPlansAndAPastOne() = runTest {
        val outcome = pipeline(listOf(past, dinner, movie)).handle(incoming)
        assertEquals(listOf("Dinner with Sam", "Movie"), outcome.events.map { it.title })
        assertTrue(outcome.lines[0].startsWith("already started, skipped: Old dinner"))
        assertEquals(2, outcome.suggestionIds.size)
    }

    // No events found says so
    @Test
    fun noEvent() = runTest {
        assertEquals(listOf("date found, no event"), pipeline(emptyList()).handle(incoming).lines)
    }

    // No internet saves the text for later, books a retry, and never alerts
    @Test
    fun offlineIsRetried() = runTest {
        val outcome = pipeline(error = AnthropicIoException("offline")).handle(incoming)
        assertEquals(listOf("date found, no connection", "will try again every minute once online"), outcome.lines)
        assertEquals(listOf(clock + RETRY_MILLIS), retries)
        assertEquals(1, dao.dueRetries(clock + RETRY_MILLIS).size)
        assertEquals(null, dao.failures(PROBLEM_API))
    }

    // A saved text goes through once Claude answers, and is removed from the queue
    @Test
    fun retrySucceeds() = runTest {
        pipeline(error = AnthropicIoException("offline")).handle(incoming)
        clock += RETRY_MILLIS
        val results = pipeline(listOf(dinner)).retryDue()
        assertEquals(listOf("event: Dinner with Sam | 2037-11-13T19:00 to ? | Joe's"), results.single().lines)
        assertEquals(1, results.single().suggestionIds.size)
        assertTrue(dao.dueRetries(Long.MAX_VALUE).isEmpty())
    }

    // A saved text still failing is rescheduled, then given up on after a day
    @Test
    fun retryReschedulesThenGivesUp() = runTest {
        val offline = pipeline(error = AnthropicIoException("offline"))
        offline.handle(incoming)
        clock += RETRY_MILLIS
        assertTrue(offline.retryDue().isEmpty())
        assertEquals(1, dao.dueRetries(clock + RETRY_MILLIS).size)
        clock += GIVE_UP_MILLIS + RETRY_MILLIS
        assertEquals(listOf("gave up (date found, no connection)"), offline.retryDue().single().lines)
        assertTrue(dao.dueRetries(Long.MAX_VALUE).isEmpty())
    }

    // A key problem alerts at once, and the next good answer from Claude clears the alert and its count
    @Test
    fun keyAlertAndClear() = runTest {
        assertTrue(Health(dao).failed(PROBLEM_API_KEY))
        val lines = pipeline(listOf(dinner)).handle(incoming).lines
        assertTrue(lines.contains("alert cleared: Claude API key or credit problem"))
        assertEquals(0, dao.failures(PROBLEM_API_KEY))
    }

    // Other API problems alert only on the third failure in a row, and only once
    @Test
    fun apiAlertAfterThree() = runTest {
        val health = Health(dao)
        assertFalse(health.failed(PROBLEM_API))
        assertFalse(health.failed(PROBLEM_API))
        assertTrue(health.failed(PROBLEM_API))
        assertFalse(health.failed(PROBLEM_API))
    }

    // The same text is only new once within an hour, and new again after that
    @Test
    fun seenTexts() = runTest {
        val pipeline = pipeline()
        assertTrue(pipeline.isNew(incoming))
        assertFalse(pipeline.isNew(incoming))
        assertTrue(pipeline.isNew(incoming.copy(text = "actually sat 8pm")))
        clock += REPEAT_MILLIS
        assertTrue(pipeline.isNew(incoming))
    }

    // Clean-up deletes suggestions older than a year and keeps newer ones
    @Test
    fun cleanUpKeepsAYear() = runTest {
        pipeline(listOf(dinner)).handle(incoming)
        clock += KEEP_SUGGESTIONS_MILLIS - 1
        pipeline().cleanUp()
        assertEquals(1, dao.watchSuggestions(Suggestion.STATUS_WAITING).first().size)
        clock += 2
        pipeline().cleanUp()
        assertTrue(dao.watchSuggestions(Suggestion.STATUS_WAITING).first().isEmpty())
    }

    // A text with no date is dropped before anything is saved, so it leaves no trace
    @Test
    fun noDateLeavesNoTrace() = runTest {
        val outcome = pipeline(listOf(dinner)).receive(incoming.copy(text = "lol ok"))
        assertEquals(null, outcome)
        assertEquals(null, dao.seenAt(fingerprint(incoming.copy(text = "lol ok"))))
    }

    // A text with a date is handled once, and skipped when the notification shows it again
    @Test
    fun receiveOnce() = runTest {
        val pipeline = pipeline(listOf(dinner))
        assertEquals(1, pipeline.receive(incoming)!!.suggestionIds.size)
        assertEquals(null, pipeline.receive(incoming))
    }

    // A message with its own timestamp is never handled twice, even hours later
    @Test
    fun exactTimeMessagesOnlyOnce() = runTest {
        val pipeline = pipeline(listOf(dinner))
        val exact = incoming.copy(exactTime = true)
        assertTrue(pipeline.isNew(exact))
        clock += 5 * REPEAT_MILLIS
        assertFalse(pipeline.isNew(exact))
    }

    // Group chats use the stricter date check
    @Test
    fun groupsNeedAClearDate() = runTest {
        val group = incoming.copy(group = "Soccer Team", text = "see you saturday")
        assertEquals(null, pipeline(listOf(dinner)).receive(group))
        assertEquals(listOf("no date"), pipeline(listOf(dinner)).describe(group).lines)
        assertEquals(1, pipeline(listOf(dinner)).receive(group.copy(text = "practice sat 9am"))!!.events.size)
    }

    // A birthday from a group comes back named after the group, and is renamed after the sender on the phone
    @Test
    fun groupBirthdayNamedAfterSender() = runTest {
        val birthday = Event("Soccer Team's birthday", "2037-03-03", allDay = true, repeat = "FREQ=YEARLY")
        val group = incoming.copy(sender = "Jake", group = "Soccer Team", text = "its my birthday march 3!")
        val outcome = pipeline(listOf(birthday)).handle(group)
        assertEquals("Jake's birthday", outcome.events.single().title)
        assertEquals("Soccer Team", dao.suggestion(outcome.suggestionIds.single())!!.conversation)
    }

    // Each saved suggestion is announced once with its text, so the app can show its prompt
    @Test
    fun eachSuggestionGetsAPrompt() = runTest {
        pipeline(listOf(dinner, movie)).handle(incoming)
        assertEquals(listOf(Pair("Dinner with Sam", incoming.text), Pair("Movie", incoming.text)), prompts)
    }

    // Shared things skip the date check and the seen list, and are saved as waiting without a prompt
    @Test
    fun shareSkipsDateCheck() = runTest {
        val shared = incoming.copy(sender = "you", text = "dinner with sam sometime", app = SHARED_TEXT_APP)
        val result = pipeline(listOf(dinner)).share(shared)
        assertEquals(listOf("Dinner with Sam"), result.suggestions.map { it.title })
        assertEquals(Suggestion.STATUS_WAITING, result.suggestions.single().status)
        assertTrue(prompts.isEmpty())
        assertEquals(null, dao.seenAt(fingerprint(shared)))
        assertEquals(1, pipeline(listOf(dinner)).share(shared).suggestions.size)  // sharing again asks again
    }

    // Sharing without a key, offline or with nothing in it says why, and never queues a retry
    @Test
    fun shareNotes() = runTest {
        val shared = incoming.copy(sender = "you", app = SHARED_TEXT_APP)
        val noKey = pipeline(listOf(dinner), key = null).share(shared)
        assertTrue(noKey.needsKey)
        val offline = pipeline(error = AnthropicIoException("offline")).share(shared)
        assertEquals("Couldn't reach Claude. Check your internet and try again.", offline.note)
        assertTrue(offline.canRetry)
        assertTrue(retries.isEmpty())
        assertTrue(dao.dueRetries(Long.MAX_VALUE).isEmpty())
        assertEquals("No plans found in this.", pipeline().share(shared).note)
        assertEquals("That plan has already started.", pipeline(listOf(past)).share(shared).note)
    }

    // A plan already on the calendar gets no suggestion or prompt; a plan that overlaps another gets a clash warning
    @Test
    fun calendarChecks() = runTest {
        val zone = ZoneId.of(newYork)
        val sameDinner = CalendarEvent(1, 1, "Dinner", toMillis("2037-11-13T19:00", zone),
            toMillis("2037-11-13T21:00", zone), false)
        val skipped = pipeline(listOf(dinner), onCalendar = listOf(sameDinner)).handle(incoming)
        assertTrue(skipped.suggestionIds.isEmpty())
        assertTrue(skipped.lines.single().startsWith("already on calendar, skipped: Dinner with Sam"))
        assertTrue(prompts.isEmpty())
        val practice = sameDinner.copy(title = "Practice", begin = toMillis("2037-11-13T18:00", zone))
        val clashing = pipeline(listOf(dinner), onCalendar = listOf(practice)).handle(incoming)
        val saved = dao.suggestion(clashing.suggestionIds.single())!!
        assertEquals("You already have Practice 6:00 PM–9:00 PM", saved.clash)
        val shared = incoming.copy(sender = "you", app = SHARED_TEXT_APP)
        assertEquals("That's already on your calendar.",
            pipeline(listOf(dinner), onCalendar = listOf(sameDinner)).share(shared).note)
    }

    // Clear history deletes ignored suggestions and added plans that are over, and keeps waiting and coming-up ones
    @Test
    fun clearHistoryKeepsWhatsAhead() = runTest {
        val ids = pipeline(listOf(dinner, movie)).handle(incoming).suggestionIds
        dao.setStatus(ids[0], Suggestion.STATUS_ADDED)
        dao.setStatus(ids[1], Suggestion.STATUS_IGNORED)
        val over = dao.saveSuggestion(suggestionOf(past, incoming, clock))
        dao.setStatus(over, Suggestion.STATUS_ADDED)
        val waiting = pipeline(listOf(dinner)).handle(incoming.copy(text = "lunch sun at noon")).suggestionIds.single()
        assertEquals(2, pipeline().clearHistory())
        assertEquals(Suggestion.STATUS_ADDED, dao.suggestion(ids[0])!!.status)
        assertEquals(null, dao.suggestion(ids[1]))
        assertEquals(null, dao.suggestion(over))
        assertEquals(Suggestion.STATUS_WAITING, dao.suggestion(waiting)!!.status)
    }

    // A cancellation without a date still goes to Claude with the coming calendar events, and becomes a change for
    // that event at its own time; group chats still need a clear date
    @Test
    fun cancellation() = runTest {
        val zone = ZoneId.of(newYork)
        val practice = CalendarEvent(42, 1, "Practice", toMillis("2026-11-13T18:00", zone),
            toMillis("2026-11-13T20:00", zone), false)
        val cancelled = Event("Practice", "2026-11-13T18:00", change = CHANGE_CANCELLED, calendarRef = 1)
        val text = incoming.copy(text = "practice is cancelled")
        val outcome = pipeline(listOf(cancelled), onCalendar = listOf(practice)).receive(text)!!
        assertEquals(listOf(practice), asked.single().upcoming)
        val saved = dao.suggestion(outcome.suggestionIds.single())!!
        assertEquals(CHANGE_CANCELLED, saved.change)
        assertEquals(42L, saved.calendarEventId)
        assertEquals("2026-11-13T18:00", saved.start)
        assertEquals("2026-11-13T20:00", saved.end)
        val group = text.copy(sender = "Jake", group = "Soccer Team")
        assertEquals(null, pipeline(listOf(cancelled), onCalendar = listOf(practice)).receive(group))
    }

    // A move takes the calendar's title, keeps the event's length, and doesn't clash with the event it moves; a move
    // to the time the calendar already has is skipped
    @Test
    fun move() = runTest {
        val zone = ZoneId.of(newYork)
        val dinner = CalendarEvent(7, 1, "Dinner", toMillis("2026-11-13T19:00", zone),
            toMillis("2026-11-13T21:00", zone), false)
        val moved = Event("Dinner w Sam", "2026-11-13T20:00", change = CHANGE_MOVED, calendarRef = 1)
        val text = incoming.copy(text = "dinner moved to 8")
        val outcome = pipeline(listOf(moved), onCalendar = listOf(dinner)).handle(text)
        val saved = dao.suggestion(outcome.suggestionIds.single())!!
        assertEquals("Dinner", saved.title)
        assertEquals("2026-11-13T22:00", saved.end)
        assertEquals("", saved.clash)
        val same = moved.copy(start = "2026-11-13T19:00")
        val skipped = pipeline(listOf(same), onCalendar = listOf(dinner)).handle(text)
        assertTrue(skipped.lines.single().startsWith("already moved, skipped"))
    }

    // Texts that don't sound like a change never send calendar events, and Claude's change numbers that match
    // nothing are treated as new plans
    @Test
    fun noCalendarWithoutChange() = runTest {
        val zone = ZoneId.of(newYork)
        val lunch = CalendarEvent(7, 1, "Lunch", toMillis("2026-11-12T12:00", zone),
            toMillis("2026-11-12T13:00", zone), false)
        pipeline(listOf(movie), onCalendar = listOf(lunch)).handle(incoming)
        assertTrue(asked.single().upcoming.isEmpty())
        val (event, changed) = matchChange(movie.copy(change = CHANGE_MOVED, calendarRef = 3), listOf(lunch))
        assertEquals(null, changed)
        assertEquals("", event.change)
    }

    // A place named in a text becomes its full address; places Google can't find stay as written
    @Test
    fun fullAddress() = runTest {
        val found = mapOf("Joe's" to "Joe's, 1 Main St, Newark, NJ")
        val outcome = pipeline(listOf(dinner, movie.copy(location = "the park")), places = found).handle(incoming)
        val saved = outcome.suggestionIds.map { dao.suggestion(it)!!.location }
        assertEquals(listOf("Joe's, 1 Main St, Newark, NJ", "the park"), saved)
    }
}
