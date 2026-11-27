// Tests for texts that update an event already on the calendar: what they add to its notes, the before → after rows,
// the prompt's line and its button word
package io.github.adenkushnir.datebook.prompts
import io.github.adenkushnir.datebook.calendar.planFields
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.CHANGE_CANCELLED
import io.github.adenkushnir.datebook.pipeline.CHANGE_DETAILS
import io.github.adenkushnir.datebook.pipeline.CHANGE_MOVED
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Checks what a text's update changes and how it's said
class UpdatesTest {
    private val newYork = "America/New_York"
    private val moved = Suggestion(id = 7, sender = "Sam", app = "messages", title = "Dinner with Sam",
        start = "2037-11-13T20:00", end = "2037-11-13T22:00", allDay = false, location = "", repeat = "",
        timezone = "", phoneTimezone = newYork, createdAt = 0, change = CHANGE_MOVED, calendarEventId = 42,
        bring = "$20")
    private val old = planFields(moved.copy(start = "2037-11-13T19:00", end = "2037-11-13T21:00", bring = ""))
        .copy(notes = "Birthday dinner, table for 4")

    // Times are said in New Jersey's zone
    @Before
    fun inNewJersey() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
    }

    @Test
    fun thingsToBringJoinTheBringLine() {
        assertEquals("Birthday dinner\nBring: cleats, $20", updatedNotes("Birthday dinner\nBring: cleats", "$20", ""))
        assertEquals("Bring: $20", updatedNotes("", "$20", ""))
        assertEquals("Bring: cleats", updatedNotes("Bring: cleats", "Cleats", ""))  // already there
    }

    @Test
    fun aCodeGetsItsOwnLineOnce() {
        assertEquals("Party\nCode: 4412", updatedNotes("Party", "", "4412"))
        assertEquals("Party\nCode: 4412", updatedNotes("Party\nCode: 4412", "", "4412"))
    }

    @Test
    fun aMoveShowsBeforeAndAfter() {
        assertEquals(listOf(UpdateRow("When", "Fri 7:00 PM", "Fri 8:00 PM"), UpdateRow("Notes", "", "Bring: $20")),
            updateRows(old, moved))
        assertEquals("Fri 7:00 PM → 8:00 PM · add Bring: $20", updateLine(old, moved))
        assertEquals("Update", actionWord(moved))
    }

    @Test
    fun notesOnlyKeepTheTime() {
        val details = moved.copy(change = CHANGE_DETAILS, start = "2037-11-13T19:00", end = "2037-11-13T21:00",
            bring = "", confirmation = "4412")
        assertEquals(listOf(UpdateRow("Notes", "", "Code: 4412")), updateRows(old, details))
        assertEquals("add Code: 4412", updateLine(old, details))
        assertEquals(true, notesOnly(details))
    }

    @Test
    fun aCancelSaysRemove() {
        val cancelled = moved.copy(change = CHANGE_CANCELLED)
        assertEquals(emptyList<UpdateRow>(), updateRows(old, cancelled))
        assertEquals("Fri 7:00 PM", updateLine(old, cancelled))
        assertEquals("Remove", actionWord(cancelled))
        assertEquals("Add", actionWord(moved.copy(change = "")))
    }
}
