// Tests for NJ Transit trains: reading the departure board, picking a train, spotting changes, the wording, stations
// and their locations, and what's remembered
package io.github.adenkushnir.datebook.trains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.HomeScreen
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks the trains from the board to the card
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrainsTest {
    @get:Rule
    val compose = createComposeRule()
    private val newBrunswick = Station("NB", "New Brunswick", 40.4970, -74.4455)
    private val penn = Station("NY", "New York Penn Station", 40.7506, -73.9935)
    private val board = """{"STATION_2CHAR": "NB", "STATIONNAME": "New Brunswick", "ITEMS": [
        {"SCHED_DEP_DATE": "13-Nov-2037 05:12:00 PM", "DESTINATION": "New York", "TRACK": "2", "LINE": "NEC",
         "TRAIN_ID": "3851", "STATUS": "On Time", "SEC_LATE": "0", "STOPS": [
            {"STATION_2CHAR": "NB", "STATIONNAME": "New Brunswick", "TIME": "13-Nov-2037 05:12:00 PM"},
            {"STATION_2CHAR": "NY", "STATIONNAME": "New York Penn Station", "TIME": "13-Nov-2037 06:05:00 PM"}]},
        {"SCHED_DEP_DATE": "13-Nov-2037 05:42:00 PM", "DESTINATION": "New York", "TRACK": "", "LINE": "NEC",
         "TRAIN_ID": "3855", "STATUS": "", "SEC_LATE": "180", "STOPS": [
            {"STATION_2CHAR": "NY", "STATIONNAME": "New York Penn Station", "TIME": "13-Nov-2037 06:35:00 PM"}]},
        {"SCHED_DEP_DATE": "13-Nov-2037 05:30:00 PM", "DESTINATION": "Trenton", "TRACK": "1", "LINE": "NEC",
         "TRAIN_ID": "3920", "STATUS": "On Time", "SEC_LATE": "0", "STOPS": [
            {"STATION_2CHAR": "TR", "STATIONNAME": "Trenton", "TIME": "13-Nov-2037 06:00:00 PM"}]}]}"""

    // Board times are New Jersey times; the board's trains keep their track, delay, status and stops
    @Test
    fun readsBoard() {
        assertEquals(Instant.parse("2037-11-13T22:12:00Z"), boardTime("13-Nov-2037 05:12:00 PM"))
        assertNull(boardTime("soon"))
        val trains = parseBoard(board)
        assertEquals(listOf("3851", "3855", "3920"), trains.map { it.id })
        assertEquals("2", trains[0].track)
        assertEquals(180, trains[1].secondsLate)
        assertEquals(Instant.parse("2037-11-13T23:05:00Z"), arrivalAt(trains[0], "NY"))
        assertNull(arrivalAt(trains[2], "NY"))
    }

    // The pick is the latest train that still reaches a station near the plan 10 minutes early, delay included
    @Test
    fun picksTrain() {
        val trains = parseBoard(board)
        val start = Instant.parse("2037-11-13T23:50:00Z").toEpochMilli()  // 6:50 PM
        assertEquals("3855", pickTrain(mapOf(newBrunswick to trains), listOf(penn), start)!!.trainId)
        val earlier = Instant.parse("2037-11-13T23:40:00Z").toEpochMilli()  // 6:40 PM: 3855 + 3 min late is too late
        assertEquals("3851", pickTrain(mapOf(newBrunswick to trains), listOf(penn), earlier)!!.trainId)
        val cancelled = trains.map { it.copy(status = "Cancelled") }
        assertNull(pickTrain(mapOf(newBrunswick to cancelled), listOf(penn), start))
    }

    // The card and alerts read naturally, and alerts go out for cancellations, new delays and tracks
    @Test
    fun wordsAndChanges() {
        val pick = pickTrain(mapOf(newBrunswick to parseBoard(board)), listOf(penn),
            Instant.parse("2037-11-13T23:40:00Z").toEpochMilli())!!
        assertEquals("Take NJT 3851 at 5:12 PM, New Brunswick → New York Penn Station · Track 2", pickLine(pick))
        assertEquals("NJT 3851 now 6 min late", trainChange(pick, pick.copy(secondsLate = 360)))
        assertEquals("NJT 3851 is cancelled", trainChange(pick, pick.copy(status = "Cancelled")))
        assertEquals("NJT 3851 now on track 4", trainChange(pick, pick.copy(track = "4")))
        assertNull(trainChange(pick, pick.copy(secondsLate = 60)))
        assertEquals(listOf("5:12 PM · Track 2 · On time", "5:42 PM · 3 min late"),
            parseBoard(board).take(2).map { boardLine(it) })
    }

    // Station locations come from the timetable zip's stops.txt, matched to RailData's names
    @Test
    fun stationLocations() {
        val stopsText = "stop_id,stop_code,stop_name,stop_desc,stop_lat,stop_lon,zone_id\n" +
            "1,,\"NEW BRUNSWICK\",,40.497,-74.4455,1\n105,,NEW YORK PENN STATION,,40.7506,-73.9935,2\n"
        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip ->
            zip.putNextEntry(ZipEntry("stops.txt"))
            zip.write(stopsText.toByteArray())
            zip.closeEntry()
        }
        val stops = readStopsFromZip(ByteArrayInputStream(zipped.toByteArray()))
        val stations = joinStations(listOf("NB" to "New Brunswick", "NY" to "New York Penn Station",
            "XX" to "Nowhere"), stops)
        assertEquals(listOf("NB", "NY"), stations.map { it.code })
        assertEquals(40.497, stations[0].lat, 0.0001)
    }

    // The nearest stations are within about 2 miles, closest first
    @Test
    fun nearest() {
        val stations = listOf(newBrunswick, penn)
        assertEquals(listOf(penn), nearestStations(40.7505, -73.9934, stations))  // Madison Square Garden
        assertTrue(nearestStations(40.0, -75.0, stations).isEmpty())
        assertEquals(47.4, distanceKm(newBrunswick.lat, newBrunswick.lng, penn.lat, penn.lng), 0.5)
    }

    // RailData requests are multipart forms with one part per field
    @Test
    fun forms() {
        assertEquals("--b\r\nContent-Disposition: form-data; name=\"station\"\r\n\r\nNB\r\n--b--\r\n",
            formBody(mapOf("station" to "NB"), "b"))
    }

    // The stations, trip and picked trains are remembered
    @Test
    fun remembered() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = TrainSettings(context)
        assertTrue(settings.stationsDue(0))
        settings.setStations(listOf(newBrunswick, penn), 1000)
        assertEquals(listOf(newBrunswick, penn), settings.stations())
        settings.setTrip("NB", "NY")
        assertEquals(Pair("NB", "NY"), settings.trip())
        val store = TrainStore(context)
        val pick = TrainPick("3851", "NEC", "New Brunswick", "New York Penn", 1, 2, "2", 0, "On Time")
        store.setPick("key", pick)
        assertEquals(pick, store.pick("key"))
    }

    // Home's Next trains card lists the trains and flips the trip
    @Test
    fun homeCard() {
        var flipped = 0
        compose.setContent {
            DatebookTheme {
                HomeScreen(trains = TrainBoard("New Brunswick", "New York Penn", listOf("5:12 PM · Track 2 · On time")),
                    onFlipTrains = { flipped += 1 })
            }
        }
        compose.onNodeWithText("5:12 PM · Track 2 · On time").assertExists()
        compose.onNodeWithText("⇄").performClick()
        assertEquals(1, flipped)
    }

    // A plan's list has every train from the station that stops near the plan, marking the best one, the ones that get
    // there late, and yours
    @Test
    fun planList() {
        val trains = parseBoard(board)
        val start = Instant.parse("2037-11-13T23:40:00Z").toEpochMilli()  // 6:40 PM
        val (to, rows) = trainRows(newBrunswick, trains, listOf(penn), start, mine = "3855")
        assertEquals(penn, to)
        assertEquals(listOf("3851", "3855"), rows.map { it.pick.trainId })
        assertTrue(rows[0].best)
        assertTrue(rows[1].late)
        assertTrue(rows[1].mine)
        assertEquals("5:12 PM", rows[0].leaves)
        assertEquals("6:38 PM", rows[1].arrives)  // 6:35 PM plus 3 minutes late
        assertEquals(Pair(null, emptyList<TrainRow>()), trainRows(newBrunswick, trains, emptyList(), start, null))
    }

    // Your chosen train and station are remembered per plan; switching station forgets the train
    @Test
    fun choicesRemembered() {
        val store = TrainStore(ApplicationProvider.getApplicationContext())
        store.setChoice("plan", "3851")
        assertEquals("3851", store.choice("plan"))
        store.setOrigin("plan", "ED")
        assertEquals("ED", store.origin("plan"))
        assertNull(store.choice("plan"))
    }

    // The plan card lists the trains with their marks, switches station and makes a tapped train yours
    @Test
    fun planCard() {
        val trains = parseBoard(board)
        val start = Instant.parse("2037-11-13T23:40:00Z").toEpochMilli()
        val rows = trainRows(newBrunswick, trains, listOf(penn), start, null).second
        val edison = Station("ED", "Edison", 40.5194, -74.4108)
        val picked = mutableListOf<String>()
        val switched = mutableListOf<String>()
        val plan = io.github.adenkushnir.datebook.data.Suggestion(sender = "you", app = "", title = "Dinner",
            start = "2037-11-13T19:00", end = "", allDay = false, location = "Carmine's", repeat = "", timezone = "",
            phoneTimezone = "America/New_York", createdAt = 0)
        compose.setContent {
            DatebookTheme {
                io.github.adenkushnir.datebook.SuggestionScreen(plan, onBack = {}, onAdd = {}, onIgnore = {},
                    trains = TrainChoices(newBrunswick, listOf(newBrunswick, edison), penn, rows),
                    onPickTrain = { picked.add(it.pick.trainId) }, onSwitchOrigin = { switched.add(it) })
            }
        }
        compose.onNodeWithText("6:05 PM ★ Best").assertExists()
        compose.onNodeWithText("6:38 PM (late)").assertExists()
        compose.onNodeWithText("5:12 PM · Track 2 · On time").performScrollTo().performClick()
        assertEquals(listOf("3851"), picked)
        compose.onNodeWithText("New Brunswick ▾").performScrollTo().performClick()
        compose.onNodeWithText("Edison").performClick()
        assertEquals(listOf("ED"), switched)
    }
}
