// Tests for the Next up countdown words
package io.github.adenkushnir.datebook
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

// Checks the countdown words for events today, tomorrow, later and in the past
class CountdownTest {
    private val today = LocalDate.of(2026, 11, 10)

    // An event today, or one already past, says "today"
    @Test
    fun todayAndPast() {
        assertEquals("today", countdownLabel(today, today))
        assertEquals("today", countdownLabel(today, today.minusDays(2)))
    }

    // An event tomorrow says "tomorrow"
    @Test
    fun tomorrow() {
        assertEquals("tomorrow", countdownLabel(today, today.plusDays(1)))
    }

    // Later events count the days, including across a month
    @Test
    fun laterDays() {
        assertEquals("in 3 days", countdownLabel(today, LocalDate.of(2026, 11, 13)))
        assertEquals("in 23 days", countdownLabel(today, LocalDate.of(2026, 12, 3)))
    }
}
