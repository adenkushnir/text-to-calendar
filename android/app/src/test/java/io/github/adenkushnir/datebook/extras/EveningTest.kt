// Tests for the evening heads-ups: the bring line in and out of an event's notes, which plans count as outdoor, and
// the weather warning for rain, snow and cold
package io.github.adenkushnir.datebook.extras
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.weather.Forecast
import io.github.adenkushnir.datebook.weather.HourWeather
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the heads-ups with a made-up soccer game on Wed Nov 11 2037, 6 to 8 PM in New York
class EveningTest {
    private val zone = ZoneId.of("America/New_York")
    private val six = ZonedDateTime.of(2037, 11, 11, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val hour = 60 * 60 * 1000L
    private val soccer = CalendarEvent(1, 1, "Soccer", six, six + 2 * hour, false, "Riverside Field",
        notes = "Jake · Soccer Team (GroupMe)\nBring: cleats, $20")

    // An hour of the forecast some hours after 6 PM
    private fun at(hoursAfter: Int, temp: Int, rain: Int, code: Int = 3): HourWeather {
        return HourWeather(six + hoursAfter * hour, temp, rain, code, false)
    }

    // The bring line goes into the notes and is read back out; nothing to bring gives nothing
    @Test
    fun bringLine() {
        assertEquals("Bring: cleats, $20", bringLine(" cleats, $20 "))
        assertEquals("", bringLine(""))
        assertEquals("cleats, $20", bringOf(soccer.notes))
        assertEquals("", bringOf("Sam (Messages)"))
        val dinner = soccer.copy(eventId = 2, title = "Dinner", notes = "")
        assertEquals(listOf("Soccer at 6:00 PM: cleats, $20"), bringLines(listOf(soccer, dinner), zone))
    }

    // Place words count (not inside other words); so does a plan Claude marked outdoor on the same day
    @Test
    fun outdoor() {
        assertTrue(soundsOutdoor("Soccer", "Riverside Field"))
        assertTrue(soundsOutdoor("Picnic with Sam", ""))
        assertFalse(soundsOutdoor("Dinner", "Parking garage, 5 Main St"))
        val dinner = soccer.copy(title = "Dinner", location = "Luigi's")
        val plan = Suggestion(sender = "Sam", app = "sms", title = "dinner", start = "2037-11-11T18:00", end = "",
            allDay = false, location = "", repeat = "", timezone = "", phoneTimezone = "America/New_York",
            createdAt = 0, outdoor = true)
        assertFalse(isOutdoor(dinner, emptyList(), zone))
        assertTrue(isOutdoor(dinner, listOf(plan), zone))
        assertFalse(isOutdoor(dinner, listOf(plan.copy(start = "2037-11-12T18:00")), zone))
    }

    // Rain from 50% during the event and cold below 40° at its start warn; fine weather doesn't
    @Test
    fun weather() {
        val wet = Forecast(null, listOf(at(-1, 45, 90), at(0, 38, 30), at(1, 37, 70, 61), at(2, 36, 95)), emptyList())
        assertEquals("Soccer at 6:00 PM: rain likely (70%), cold (38°)", weatherWarning(soccer, wet, true, zone))
        val snowy = Forecast(null, listOf(at(0, 30, 80, 73)), emptyList())
        assertEquals("Soccer at 6:00 PM: snow likely (80%), cold (30°)", weatherWarning(soccer, snowy, true, zone))
        val fine = Forecast(null, listOf(at(0, 55, 10), at(1, 52, 40)), emptyList())
        assertNull(weatherWarning(soccer, fine, true, zone))
        assertNull(weatherWarning(soccer, Forecast(null, listOf(at(0, 10, 0)), emptyList()), false, zone))
    }
}
