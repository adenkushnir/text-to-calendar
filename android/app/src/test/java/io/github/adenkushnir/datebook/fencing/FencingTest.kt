// Tests for the men's foil tournaments: reading Claude's answer, merging searches, the week's tiles, live streams and
// the empty-week line, with a made-up week in New York
package io.github.adenkushnir.datebook.fencing
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the fencing section with a week starting Mon Nov 16 2037
class FencingTest {
    private val zone = ZoneId.of("America/New_York")
    private val monday = LocalDate.of(2037, 11, 16)
    private val junior = Tournament("Junior Men's Foil", "November NAC", "Columbus, OH", LocalDate.of(2037, 11, 20),
        LocalDate.of(2037, 11, 21), "08:00", "FencingTV", "https://www.fencingtv.com/live", "https://example.org/results")
    private val grandPrix = Tournament("Men's Foil Grand Prix", "FIE Grand Prix", "Turin", LocalDate.of(2037, 11, 21),
        LocalDate.of(2037, 11, 22), "03:30")
    private val worldCup = Tournament("Junior Men's Foil", "Junior World Cup", "Bochum", LocalDate.of(2037, 12, 5),
        LocalDate.of(2037, 12, 6))

    // Milliseconds for a day and time in New York
    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long {
        return ZonedDateTime.of(day.atTime(hour, minute), zone).toInstant().toEpochMilli()
    }

    // Claude's answer is read from inside its json tags; bad dates, missing names and non-web links are dropped
    @Test
    fun readsAnswers() {
        val answer = "I searched FencingTV and the FIE.\n<json>{\"events\": [" +
            "{\"name\": \"Junior Men's Foil\", \"meet\": \"November NAC\", \"place\": \"Columbus, OH\", " +
            "\"first_day\": \"2037-11-20\", \"last_day\": \"2037-11-21\", \"start_time\": \"08:00\", " +
            "\"stream_url\": \"fencingtv.com\", \"results_url\": \"https://example.org/results\"}, " +
            "{\"name\": \"\", \"first_day\": \"2037-11-20\"}, {\"name\": \"Div I\", \"first_day\": \"soon\"}]}</json>"
        val found = tournamentsFrom(answerJson(answer)!!.path("events"))
        assertEquals(1, found.size)
        assertEquals("", found[0].streamUrl)
        assertEquals("https://example.org/results", found[0].resultsUrl)
        assertNull(answerJson("No events found."))
        assertEquals(listOf(junior, grandPrix), tournamentsFromJson(tournamentsToJson(listOf(junior, grandPrix))))
    }

    // A new search replaces the old one, keeps today's stream details, and drops tournaments that are over
    @Test
    fun mergesSearches() {
        val friday = LocalDate.of(2037, 11, 20)
        val checked = junior.copy(todayDate = friday.toString(), todayStart = "09:00", todayNote = "Table of 64")
        val old = Tournament("Div I Men's Foil", "October NAC", "Orlando", LocalDate.of(2037, 10, 9),
            LocalDate.of(2037, 10, 12))
        val merged = mergeSearch(listOf(checked, old), listOf(junior, grandPrix, old), friday)
        assertEquals(listOf(junior.name, grandPrix.name), merged.map { it.name })
        assertEquals("Table of 64", merged[0].todayNote)
        val renamed = junior.copy(name = "Junior Men's Individual Foil", streamUrl = "")
        val again = mergeSearch(listOf(junior, grandPrix), listOf(renamed), friday)
        assertEquals(listOf(renamed.name, grandPrix.name), again.map { it.name })
        assertEquals("https://www.fencingtv.com/live", again[0].streamUrl)
        val today = mergeToday(listOf(junior), listOf(junior.copy(todayStart = "10:00", todayEnd = "18:00",
            todayNote = "Table of 16", streamUrl = "")), friday)
        assertEquals("10:00", today[0].todayStart)
        assertEquals("https://www.fencingtv.com/live", today[0].streamUrl)
    }

    // This week's tournaments become tiles in order; the one streaming now is first and lit
    @Test
    fun tiles() {
        val list = listOf(junior, grandPrix, worldCup)
        val mondayTiles = fencingTiles(list, at(monday, 9), zone)
        assertEquals(listOf(junior, grandPrix), mondayTiles.map { it.tournament })
        assertFalse(mondayTiles.any { it.live })
        assertEquals(Pair("Fri", "Fri–Sat"), tileDays(mondayTiles[0]))
        assertEquals("Starts 8:00 AM", tileTime(mondayTiles[0]))
        val saturday = LocalDate.of(2037, 11, 21)
        val live = fencingTiles(list, at(saturday, 14), zone)
        assertEquals(junior, live[0].tournament)
        assertTrue(live[0].live)
        assertFalse(live[1].live)  // the Grand Prix's 3:30 AM stream is over by 2 PM
        assertEquals("Sat", tileDays(live[0]).first)
        assertEquals("Streaming now", tileTime(live[0]))
    }

    // Today's checked stream times win over the usual start time
    @Test
    fun streamTimes() {
        val friday = LocalDate.of(2037, 11, 20)
        val checked = junior.copy(todayDate = friday.toString(), todayStart = "10:00", todayEnd = "12:00")
        assertEquals(Pair(at(friday, 10), at(friday, 12)), streamWindow(checked, friday, zone))
        assertEquals(at(friday, 8), streamWindow(junior, friday, zone)!!.first)
        assertNull(streamWindow(junior, monday, zone))
        assertNull(streamWindow(worldCup, LocalDate.of(2037, 12, 5), zone))
    }

    // Watch and Results use the exact links when found, else the sites for USA or FIE events
    @Test
    fun fallbackLinks() {
        assertEquals("https://www.fencingtv.com/live", watchLink(junior))
        assertEquals("https://example.org/results", resultsLink(junior))
        val noLinks = junior.copy(streamUrl = "", resultsUrl = "")
        assertEquals("https://www.fencingtv.com", watchLink(noLinks))
        assertEquals("https://www.fencingtimelive.com", resultsLink(noLinks))
        assertTrue(watchLink(grandPrix).startsWith("https://www.youtube.com/results?search_query=FIE+Grand+Prix"))
        assertEquals("https://fie.org/competitions", resultsLink(grandPrix))
    }

    // An empty week names the next tournament found
    @Test
    fun emptyWeek() {
        assertEquals("No men's foil events this week. Next: Junior World Cup, Bochum · Sat Dec 5.",
            emptyWeekLine(listOf(worldCup), monday))
        assertEquals("No men's foil events this week.", emptyWeekLine(emptyList(), monday))
        assertTrue(searchPrompt(monday, zone).contains("https://www.fencingtv.com"))
        assertTrue(todayPrompt(monday, zone, listOf(junior)).contains("Junior Men's Foil · November NAC"))
    }
}
