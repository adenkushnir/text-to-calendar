// Tests for the Settings screens: the menu, app switches, model choice, Clear history and the debug-only test box
package io.github.adenkushnir.datebook.settings
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.capture.WatchedApp
import io.github.adenkushnir.datebook.pipeline.DEFAULT_MODEL
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import org.junit.Assert.assertEquals
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
                SettingsMenu(listOf(SettingsItem("▣", "Apps to watch", "hint") { opened.add("apps") },
                    SettingsItem("i", "About Datebook", "hint") { opened.add("about") }), onBack = {})
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
        compose.onNodeWithText("CLEAR HISTORY").performScrollTo().performClick()
        compose.onNodeWithText("Clear history?").assertExists()
        compose.onNodeWithText("CLEAR").performClick()
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
