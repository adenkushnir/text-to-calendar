// Tests for finding places: the search sent to Google, reading its reply, Maps links, travel settings and the page
package io.github.adenkushnir.datebook.places
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.settings.PlacesPage
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks place lookups, links and the Places & travel page
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlacesTest {
    @get:Rule
    val compose = createComposeRule()

    // The calendar location joins the name and address, without repeating a name the address starts with
    @Test
    fun placeLines() {
        assertEquals("Luigi's, 123 Main St, East Hanover, NJ", placeLine("Luigi's", "123 Main St, East Hanover, NJ"))
        assertEquals("123 Main St, East Hanover, NJ", placeLine("123 Main St", "123 Main St, East Hanover, NJ"))
        assertEquals("Luigi's", placeLine("Luigi's", ""))
    }

    // The search asks for one place near where you are, or near home when your location isn't known
    @Test
    fun searches() {
        val near = searchBody("Luigi's", Pair(40.8, -74.4), "1 College Ave, New Brunswick, NJ")
        assertEquals("Luigi's", near["textQuery"])
        assertEquals(1, near["pageSize"])
        val circle = (near["locationBias"] as Map<*, *>)["circle"] as Map<*, *>
        assertEquals(50000.0, circle["radius"])
        assertEquals("Luigi's near 1 College Ave, New Brunswick, NJ",
            searchBody("Luigi's", null, "1 College Ave, New Brunswick, NJ")["textQuery"])
        assertFalse(searchBody("Luigi's", null, "").containsKey("locationBias"))
    }

    // Google's reply becomes the calendar location with its coordinates; no match gives nothing
    @Test
    fun replies() {
        val reply = """{"places": [{"displayName": {"text": "Luigi's", "languageCode": "en"},
            "formattedAddress": "123 Main St, East Hanover, NJ 07936, USA",
            "location": {"latitude": 40.8, "longitude": -74.36}}]}"""
        assertEquals(FoundPlace("Luigi's, 123 Main St, East Hanover, NJ 07936, USA", 40.8, -74.36), parsePlace(reply))
        assertNull(parsePlace("{}"))
    }

    // Open in Maps starts directions to the place with the travel mode
    @Test
    fun mapsLink() {
        val intent = mapsIntent("Luigi's, 123 Main St", TRAVEL_TRANSIT)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=Luigi's%2C%20123%20Main%20St" +
            "&travelmode=transit", intent.data.toString())
    }

    // Driving is the default and stands in for anything unknown; the home address is remembered
    @Test
    fun travelSettings() {
        val settings = TravelSettings(ApplicationProvider.getApplicationContext())
        assertEquals(TRAVEL_DRIVE, settings.mode())
        settings.setMode(TRAVEL_WALK)
        assertEquals(TRAVEL_WALK, settings.mode())
        settings.setMode("TELEPORT")
        assertEquals(TRAVEL_DRIVE, settings.mode())
        settings.setHome(" 1 College Ave ")
        assertEquals("1 College Ave", settings.home())
    }

    // The page saves the travel mode and the home address, and offers location access
    @Test
    fun placesPage() {
        val chosen = mutableListOf<String>()
        var home = ""
        var asked = 0
        compose.setContent {
            DatebookTheme {
                PlacesPage(maskedKey = null, saveKey = {}, mode = TRAVEL_DRIVE, onMode = { chosen.add(it) },
                    home = "", saveHome = { home = it }, location = false, askLocation = { asked += 1 },
                    allTheTime = false, askAllTheTime = {}, onBack = {})
            }
        }
        compose.onNodeWithText("Transit").performScrollTo().performClick()
        assertEquals(listOf(TRAVEL_TRANSIT), chosen)
        compose.onNodeWithText("Home address").performScrollTo().performTextReplacement("1 College Ave")
        compose.onNodeWithText("SAVE").performScrollTo().performClick()
        assertEquals("1 College Ave", home)
        compose.onAllNodesWithText("TURN ON")[0].performScrollTo().performClick()
        assertEquals(1, asked)
    }
}
