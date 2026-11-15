// Tests for the sample trains on Home's train board: their timetable, the train that just left, and the next 3
package io.github.adenkushnir.datebook.trains
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks sample trains from New Brunswick to New York Penn on Tue Nov 10 2037 at 2:00 PM
class SampleTrainsTest {
    private val now = LocalDateTime.of(2037, 11, 10, 14, 0).atZone(NJ_ZONE).toInstant().toEpochMilli()
    private val newBrunswick = SAMPLE_STATIONS.first { it.code == "NB" }
    private val newYork = SAMPLE_STATIONS.first { it.code == "NY" }

    // Trains leave from about 5 AM every 31 or 22 minutes, reach New York after about an hour, and some are late or
    // cancelled
    @Test
    fun timetable() {
        val trains = sampleDepartures(newBrunswick, newYork, now)
        val today = trains.filter { it.departs!!.atZone(NJ_ZONE).toLocalDate().dayOfMonth == 10 }
        assertEquals(5, today[0].departs!!.atZone(NJ_ZONE).hour)
        assertEquals(31, ChronoUnit.MINUTES.between(today[0].departs, today[1].departs))
        assertEquals(22, ChronoUnit.MINUTES.between(today[1].departs, today[2].departs))
        val ride = ChronoUnit.MINUTES.between(today[0].departs, arrivalAt(today[0], "NY"))
        assertTrue(ride in 50..90)
        assertEquals("1", today[0].track)
        assertEquals(240, today[3].secondsLate)
        assertTrue(isCancelled(today[6]))
        assertTrue(trains.size > today.size * 2)  // yesterday and tomorrow too
    }

    // The board shows the last train that left (never a cancelled one) and the next 3, marked as samples
    @Test
    fun board() {
        val stations = listOf(newBrunswick, newYork)
        val board = sampleBoard(stations, "NB", "NY", now)
        val trains = sampleDepartures(newBrunswick, newYork, now)
        val leaves = { train: Train -> train.departs!!.toEpochMilli() + train.secondsLate * 1000L }
        val last = trains.last { leaves(it) <= now && !isCancelled(it) }
        assertEquals(trains.filter { leaves(it) > now }.take(3).map { boardLine(it) }, board.lines)
        assertEquals(departedLine(last), board.last)
        assertTrue(board.last.endsWith(" · Departed"))
        assertTrue(board.sample)
        assertEquals("New Brunswick", board.fromName)
        assertEquals("New York Penn Station", board.toName)
    }

    // A trip saved with a station that isn't in the list asks to be picked again
    @Test
    fun unknownStation() {
        val board = sampleBoard(SAMPLE_STATIONS, "XX", "NY", now)
        assertTrue(board.lines.isEmpty())
        assertEquals("Pick your trip again in Settings.", board.note)
    }
}
