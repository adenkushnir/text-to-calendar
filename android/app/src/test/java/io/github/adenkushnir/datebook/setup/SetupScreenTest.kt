// Tests for the setup pages: required steps block Next until done, optional ones can be skipped, then the finish page
package io.github.adenkushnir.datebook.setup
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Walks through the setup pages with different steps done
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val opened = mutableListOf<String>()
    private val actions = SetupActions(
        openAccess = { opened.add("access") },
        askNotifications = { opened.add("notifications") },
        checkAndSaveKey = { null },
        openBattery = { opened.add("battery") },
        askCalendar = { opened.add("calendar") },
        saveMapsKey = { opened.add("maps") },
    )

    // Show the setup pages with the given steps done
    private fun showSetup(status: SetupStatus, finished: MutableList<Boolean> = mutableListOf()) {
        compose.setContent {
            DatebookTheme {
                SetupScreen(status, actions, onSample = {}, onFinish = { finished.add(true) })
            }
        }
    }

    // A required step that isn't done keeps Next turned off, and its button opens the right setting
    @Test
    fun requiredStepBlocksNext() {
        showSetup(SetupStatus())
        compose.onNodeWithText("STEP 1 OF 6").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Open settings").performClick()
        assertEquals(listOf("access"), opened)
    }

    // Done required steps let you through; optional steps that aren't done show Skip; the end has the finish page
    @Test
    fun walkToTheEnd() {
        val finished = mutableListOf<Boolean>()
        showSetup(SetupStatus(access = true, notifications = true, key = true), finished)

        // Go through the three required pages
        for (page in 1..3) {
            compose.onNodeWithText("✓ Done").assertExists()
            compose.onNodeWithText("Next").performScrollTo().assertIsEnabled().performClick()
        }

        // Skip the three optional pages
        for (page in 4..6) {
            compose.onNodeWithText("Skip").performScrollTo().assertIsEnabled().performClick()
        }
        compose.onNodeWithText("You're set").assertExists()
        compose.onNodeWithText("Go to Datebook").performScrollTo().performClick()
        assertEquals(listOf(true), finished)
    }

    // Only notification access, notifications and the key are required
    @Test
    fun requiredSteps() {
        assertTrue(SetupStatus(access = true, notifications = true, key = true).requiredDone)
        assertFalse(SetupStatus(access = true, notifications = true, battery = true, calendar = true).requiredDone)
    }
}
