// Tests for the offline backup: turning ML Kit's times into guesses, their titles, and how guesses show on Home and
// on their card
package io.github.adenkushnir.datebook.offline
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import io.github.adenkushnir.datebook.HomeScreen
import io.github.adenkushnir.datebook.SuggestionScreen
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.IncomingText
import io.github.adenkushnir.datebook.prompts.guessNote
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks the offline guesses from ML Kit's answer to the screens
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineDatesTest {
    @get:Rule
    val compose = createComposeRule()
    private val newYork = ZoneId.of("America/New_York")
    private val fridayAtSeven = LocalDateTime.of(2037, 11, 13, 19, 0).atZone(newYork).toInstant().toEpochMilli()
    private val guess = Suggestion(id = 5, sender = "Sam", app = "sms", title = "Plan with Sam",
        start = "2037-11-13T19:00", end = "", allDay = false, location = "", repeat = "", timezone = "",
        phoneTimezone = "America/New_York", createdAt = 0, retryId = 9)

    // A time to the hour or minute is a timed guess, a day is all-day, and a month is too vague
    @Test
    fun timesBecomeGuesses() {
        assertEquals(GuessedTime("2037-11-13T19:00", false),
            guessedTime(fridayAtSeven, DateTimeEntity.GRANULARITY_MINUTE, newYork))
        assertEquals(GuessedTime("2037-11-13", true),
            guessedTime(fridayAtSeven, DateTimeEntity.GRANULARITY_DAY, newYork))
        assertNull(guessedTime(fridayAtSeven, DateTimeEntity.GRANULARITY_MONTH, newYork))
    }

    // Guesses are named after the person, or the group chat
    @Test
    fun guessTitles() {
        val now = LocalDateTime.of(2037, 11, 12, 9, 0)
        assertEquals("Plan with Sam", guessTitle(IncomingText("Sam", "dinner fri 7pm", now, "sms")))
        assertEquals("Plan in Soccer Team", guessTitle(IncomingText("Jake", "game sat 3pm", now, "groupme",
            "Soccer Team")))
    }

    // A guess says whether Claude is still to check it; plans Claude read have no note
    @Test
    fun guessNotes() {
        assertEquals("Offline guess · waiting for Claude", guessNote(guess, checking = true))
        assertEquals("Not checked by Claude", guessNote(guess, checking = false))
        assertNull(guessNote(guess.copy(retryId = 0), checking = true))
    }

    // Home marks a guess that's waiting for Claude
    @Test
    fun homeShowsGuess() {
        compose.setContent {
            DatebookTheme {
                HomeScreen(waiting = listOf(guess), checking = setOf(9L))
            }
        }
        compose.onNodeWithText("Offline guess · waiting for Claude").assertExists()
    }

    // The card shows the message a guess came from while Claude is still to check it
    @Test
    fun cardShowsMessage() {
        compose.setContent {
            DatebookTheme {
                SuggestionScreen(guess, onBack = {}, onAdd = {}, onIgnore = {}, guessText = "dinner fri 7pm at joes")
            }
        }
        compose.onNodeWithText("“dinner fri 7pm at joes”").assertExists()
        compose.onNodeWithText("Offline guess · waiting for Claude").assertExists()
    }
}
