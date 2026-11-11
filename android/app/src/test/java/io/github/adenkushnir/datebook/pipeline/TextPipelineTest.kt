// Tests for the text pipeline with a real in-memory database and a fake Claude, like the Python main tests
package io.github.adenkushnir.datebook.pipeline
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anthropic.errors.AnthropicIoException
import io.github.adenkushnir.datebook.data.DatebookDao
import io.github.adenkushnir.datebook.data.DatebookDatabase
import io.github.adenkushnir.datebook.data.Suggestion
import java.time.Instant
import java.time.LocalDateTime
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

    // Build a pipeline whose Claude returns the given events, or throws the given error
    private fun pipeline(events: List<Event> = emptyList(), error: Exception? = null, key: String? = "sk-test"):
        TextPipeline {
        return TextPipeline(
            dao = dao,
            apiKey = { key },
            extract = { _, _, zone -> error?.let { throw it } ?: events.map { it.copy(phoneTimezone = zone) } },
            phoneZone = { newYork },
            now = { clock },
            onRetrySaved = { retries.add(it) },
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
}
