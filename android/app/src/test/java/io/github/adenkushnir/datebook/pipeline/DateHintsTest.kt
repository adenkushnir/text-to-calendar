// Tests for the date and time pre-filter, with the same cases as the Python tests
package io.github.adenkushnir.datebook.pipeline
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks which texts the pre-filter lets through to Claude
class DateHintsTest {
    private val withDates = listOf(
        "dinner fri 7pm",
        "meeting tomorrow at 3",
        "Party on Saturday!",
        "dentist 12/3",
        "call at 7:30",
        "Dec 5th brunch?",
        "see you tonight",
        "lunch at noon",
        "flight is 2026-12-03",
        "it starts at 9 o'clock",
        "starts 8 o’clock",
        "the game is the 21st",
        "practice 6:30 P.M.",
        "TMRW at 10",
        "party 01/06/2026",
        "party 1/6/2026",
        "party 1-6-2026",
        "party 1.6.2026",
        "its my birthday march 3!",
        "my bday is the 3rd",
        "jakes birthday is saturday, party at 8",
    )
    private val withoutDates = listOf(
        "lol ok",
        "thanks!",
        "call me when you can",
        "I have 3 dogs",
        "sounds good",
        "on my way",
        "I'm at the store",
        "my number is 555-1234",
        "call 1-800-555-1234",
        "it was 2.5 miles",
        "happy birthday!!",
        "",
    )

    // Texts that mention a date or time are caught
    @Test
    fun findsDates() {
        // Check each text with a date
        for (text in withDates) {
            assertTrue("should find a date in: $text", hasDateHint(text))
        }
    }

    // Texts with no date or time are skipped
    @Test
    fun skipsTextsWithoutDates() {
        // Check each text without a date
        for (text in withoutDates) {
            assertFalse("should find no date in: $text", hasDateHint(text))
        }
    }

    // A missing text counts as no date
    @Test
    fun nullIsNoDate() {
        assertFalse(hasDateHint(null))
    }
}
