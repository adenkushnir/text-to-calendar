// Tests for reminders: when one first goes off, when a repeat comes back, how repeats read, and note titles
package io.github.adenkushnir.datebook.data
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Checks reminder times from Tue Nov 10 2037 at 2:00 PM in New York
class RemindersTest {
    private val newYork = ZoneId.of("America/New_York")
    private val now = LocalDateTime.of(2037, 11, 10, 14, 0)

    // Milliseconds for a time in New York
    private fun millis(time: LocalDateTime): Long {
        return time.atZone(newYork).toInstant().toEpochMilli()
    }

    // A reminder goes off at its time, at 9 AM on its day without a time, at 9 AM tomorrow without either, and a
    // minute from now when its time has passed (or at its next repeat)
    @Test
    fun firstTimes() {
        val created = millis(now)
        assertEquals(millis(LocalDateTime.of(2037, 11, 11, 15, 30)),
            firstReminder("2037-11-11", "15:30", "", created, newYork))
        assertEquals(millis(LocalDateTime.of(2037, 11, 12, 9, 0)),
            firstReminder("2037-11-12", "", "", created, newYork))
        assertEquals(millis(LocalDateTime.of(2037, 11, 11, 9, 0)), firstReminder("", "", "", created, newYork))
        assertEquals(millis(now.plusMinutes(1)), firstReminder("2037-11-10", "09:00", "", created, newYork))
        assertEquals(millis(LocalDateTime.of(2037, 11, 11, 9, 0)),
            firstReminder("2037-11-10", "09:00", "FREQ=DAILY", created, newYork))
    }

    // Repeats: every day, every 3 days, weekly on Mondays and Thursdays, every 2 weeks, monthly; none without a rule
    @Test
    fun repeats() {
        val monday = LocalDateTime.of(2037, 11, 9, 8, 0)
        assertEquals(LocalDateTime.of(2037, 11, 11, 8, 0), nextRepeat("FREQ=DAILY", monday, now))
        assertEquals(LocalDateTime.of(2037, 11, 12, 8, 0), nextRepeat("FREQ=DAILY;INTERVAL=3", monday, now))
        assertEquals(LocalDateTime.of(2037, 11, 12, 8, 0), nextRepeat("FREQ=WEEKLY;BYDAY=MO,TH", monday, now))
        assertEquals(LocalDateTime.of(2037, 11, 23, 8, 0), nextRepeat("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", monday, now))
        assertEquals(LocalDateTime.of(2037, 12, 9, 8, 0), nextRepeat("FREQ=MONTHLY", monday, now))
        assertNull(nextRepeat("", monday, now))
    }

    // Repeats read in plain words, and a note's title is its first line, cut short
    @Test
    fun wording() {
        assertEquals("Every day", repeatLabel("FREQ=DAILY"))
        assertEquals("Every 3 days", repeatLabel("FREQ=DAILY;INTERVAL=3"))
        assertEquals("Every Monday", repeatLabel("FREQ=WEEKLY;BYDAY=MO"))
        assertEquals("Every Mon and Thu", repeatLabel("FREQ=WEEKLY;BYDAY=MO,TH"))
        assertEquals("Every month", repeatLabel("FREQ=MONTHLY"))
        assertEquals("", repeatLabel(""))
        assertEquals("Gift ideas for Mom", noteTitle("Gift ideas for Mom\nscarf, the blue mug"))
        assertEquals(58, noteTitle("x".repeat(80)).length)
    }
}
