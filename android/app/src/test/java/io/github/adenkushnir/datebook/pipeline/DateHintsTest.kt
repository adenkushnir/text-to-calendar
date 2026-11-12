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
        assertFalse(hasClearDate(null))
    }

    // Group chats need a clear date or time
    @Test
    fun clearDatesForGroups() {
        val clear = listOf("practice fri 7pm", "game Nov 14", "the 14th of November", "14 nov",
            "lunch tomorrow at noon", "dentist 12/3", "meet at 19:00", "party tonight", "flight 2026-12-03")

        // Check each clear date
        for (text in clear) {
            assertTrue("should be clear: $text", hasClearDate(text))
        }
    }

    // Loose hints that are enough for texts are not enough for group chats
    @Test
    fun looseHintsNotEnoughForGroups() {
        val loose = listOf("I sat down", "may be late", "the 3rd one was better", "be there at 7", "see you saturday",
            "this weekend?", "next week maybe", "lol ok")

        // Check each loose hint
        for (text in loose) {
            assertFalse("should not be clear: $text", hasClearDate(text))
        }
    }

    // Texts that move or cancel a plan sound like changes; ordinary plans and chat don't
    @Test
    fun changes() {
        val changes = listOf("dinner moved to 8", "practice is cancelled", "game canceled", "pushed back an hour",
            "rescheduled for next week", "lets do 8 instead", "meeting got postponed")
        for (text in changes) {
            assertTrue(text, soundsLikeChange(text))
        }
        assertFalse(soundsLikeChange("dinner fri 7pm"))
        assertFalse(soundsLikeChange("lol ok"))
        assertFalse(soundsLikeChange(null))
    }
}
