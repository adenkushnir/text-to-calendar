// Tests for the Settings screens: the menu, app and calendar switches, model choice, Clear history and the debug-only
// test box
package io.github.adenkushnir.datebook.settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.calendar.CalendarSwitches
import io.github.adenkushnir.datebook.calendar.PhoneCalendar
import io.github.adenkushnir.datebook.capture.WatchedApp
import io.github.adenkushnir.datebook.pipeline.DEFAULT_MODEL
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.TILE_BLUE
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks each Settings page does what its buttons say
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    // Each menu row opens its page
    @Test
    fun menuRows() {
        val opened = mutableListOf<String>()
        compose.setContent {
            DatebookTheme {
                val apps = SettingsItem(DatebookIcons.Apps, TILE_BLUE, "Apps to watch", "hint") { opened.add("apps") }
                val about = SettingsItem(DatebookIcons.Info, TILE_BLUE, "About Datebook", "hint") {
                    opened.add("about")
                }
                SettingsMenu(listOf(apps, about), onBack = {})
            }
        }
        compose.onNodeWithText("Apps to watch").performClick()
        compose.onNodeWithText("About Datebook").performClick()
        assertEquals(listOf("apps", "about"), opened)
    }

    // Flipping an app's switch saves the new setting
    @Test
    fun appSwitches() {
        val saved = mutableMapOf<String, Boolean>()
        val apps = listOf(WatchedApp("groupme", "GroupMe", "GroupMe", true))
        compose.setContent {
            DatebookTheme {
                AppsPage(apps, isOn = { true }, setOn = { app, on -> saved[app] = on }, onBack = {})
            }
        }
        compose.onNodeWithText("GroupMe").assertExists()
        compose.onNode(isToggleable()).performClick()
        assertEquals(mapOf("groupme" to false), saved)
    }

    // Tapping Haiku chooses it
    @Test
    fun chooseModel() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            DatebookTheme {
                ClaudePage("sk-ant-api03-••••9f2a", DEFAULT_MODEL, onModel = { chosen.add(it) },
                    checkAndSaveKey = { null }, onBack = {})
            }
        }
        compose.onNodeWithText("sk-ant-api03-••••9f2a").assertExists()
        compose.onNodeWithText("Haiku 5.5").performScrollTo().performClick()
        assertEquals(listOf(HAIKU_MODEL), chosen)
    }

    // Clear history asks first, then says how many were deleted
    @Test
    fun clearHistoryAsksFirst() {
        var cleared = 0
        compose.setContent {
            DatebookTheme {
                PrivacyPage(clearHistory = { cleared += 1; 3 }, onBack = {})
            }
        }
        compose.onNodeWithText("Clear history").performScrollTo().performClick()
        compose.onNodeWithText("Clear history?").assertExists()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Deleted 3 old suggestion(s).").assertExists()
        assertEquals(1, cleared)
    }

    // The test box only appears when the app is a debug build
    @Test
    fun testBoxOnlyInDebug() {
        compose.setContent {
            DatebookTheme {
                AboutPage("1.0.0", openGitHub = {}, pretend = null, onBack = {})
            }
        }
        compose.onNodeWithText("Datebook 1.0.0").assertExists()
        compose.onNodeWithText("Pretend a text arrived").assertDoesNotExist()
    }

    // Without calendar access the page offers Allow; with it, each calendar has a switch that saves the choice
    @Test
    fun calendarsPage() {
        var access by mutableStateOf(false)
        val saved = mutableMapOf<Long, Boolean>()
        var asked = 0
        val calendars = listOf(PhoneCalendar(4, "Personal", "me@example.com"))
        compose.setContent {
            DatebookTheme {
                CalendarsPage(access, calendars, isOn = { true }, setOn = { id, on -> saved[id] = on },
                    askAccess = { asked += 1 }, onBack = {})
            }
        }
        compose.onNodeWithText("Allow").performScrollTo().performClick()
        assertEquals(1, asked)
        access = true
        compose.onNodeWithText("Personal").assertExists()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        assertEquals(mapOf(4L to false), saved)
    }

    // Calendars start switched on and remember being switched off
    @Test
    fun calendarSwitchesRemembered() {
        val switches = CalendarSwitches(ApplicationProvider.getApplicationContext())
        assertTrue(switches.isOn(4))
        switches.set(4, false)
        assertEquals(setOf(4L), switches.switchedOff())
        switches.set(4, true)
        assertTrue(switches.isOn(4))
    }

    // The chosen model is remembered, and anything unknown falls back to Opus 5.5
    @Test
    fun modelChoiceRemembered() {
        val choice = ModelChoice(ApplicationProvider.getApplicationContext())
        assertEquals(DEFAULT_MODEL, choice.get())
        choice.set(HAIKU_MODEL)
        assertEquals(HAIKU_MODEL, choice.get())
        choice.set("claude-made-up")
        assertEquals(DEFAULT_MODEL, choice.get())
    }
}
