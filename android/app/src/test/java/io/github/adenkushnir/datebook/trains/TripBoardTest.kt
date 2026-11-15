// Tests for the live train board: the next 3 trains toward the trip's end, the one that just left remembered after it
// drops off NJ Transit's board, and what gets remembered
package io.github.adenkushnir.datebook.trains
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks tripBoard with made-up trains from New Brunswick
class TripBoardTest {
    private val minute = 60_000L
    private val now = Instant.parse("2037-11-10T21:00:00Z").toEpochMilli()

    // A train leaving some minutes from now, stopping at the given stations an hour later
    private fun train(id: String, inMinutes: Long, stopsAt: String = "NY", late: Int = 0,
        status: String = "On Time"): Train {
        val departs = Instant.ofEpochMilli(now + inMinutes * minute)
        val stops = listOf(TrainStop("NB", "New Brunswick", departs), TrainStop(stopsAt, "Somewhere",
            departs.plusSeconds(3600)))
        return Train(id, "Northeast Corridor", "New York", departs, "2", late, status, stops)
    }

    // The first look: the next 3 toward New York (skipping one that doesn't go there), nothing left yet
    @Test
    fun firstLook() {
        val live = listOf(train("3801", 5), train("3803", 12, stopsAt = "TR"), train("3805", 20, late = 240),
            train("3807", 35, status = "Cancelled"), train("3809", 50))
        val (board, remember) = tripBoard("New Brunswick", "New York Penn", "NY", live, emptyList(), now)
        assertEquals(listOf(live[0], live[2], live[3]).map { boardLine(it) }, board.lines)
        assertEquals("", board.last)
        assertEquals(now + 5 * minute, board.nextDeparture)
        assertEquals(listOf("3801", "3805", "3809"), remember.map { it.id })
    }

    // After the first train leaves it drops off the board but still shows as the one that just left
    @Test
    fun rememberedAfterLeaving() {
        val first = train("3801", 5)
        val (_, remember) = tripBoard("New Brunswick", "New York Penn", "NY", listOf(first, train("3805", 20)),
            emptyList(), now)
        val later = now + 7 * minute
        val (board, kept) = tripBoard("New Brunswick", "New York Penn", "NY", listOf(train("3805", 20)), remember,
            later)
        assertEquals(departedLine(first), board.last)
        assertTrue(departedLine(first).endsWith("Track 2 · Departed"))
        assertEquals(setOf("3801", "3805"), kept.map { it.id }.toSet())
        val (much, forgotten) = tripBoard("New Brunswick", "New York Penn", "NY", emptyList(), kept,
            now + 4 * 60 * minute)
        assertEquals("No trains found right now.", much.note)
        assertTrue(forgotten.isEmpty())
    }
}
