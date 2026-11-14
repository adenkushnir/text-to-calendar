// Tests for the share sheet: the spinner, the plans with Add and ✕, and the notes with their buttons
package io.github.adenkushnir.datebook.share
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.SHARED_TEXT_APP
import io.github.adenkushnir.datebook.pipeline.ShareResult
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks what the sheet shows for each result and what its buttons do
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareSheetTest {
    @get:Rule
    val compose = createComposeRule()
    private val game = Suggestion(id = 3, sender = "you", app = SHARED_TEXT_APP, title = "Game 1",
        start = "2037-11-14T10:00", end = "", allDay = false, location = "", repeat = "", timezone = "",
        phoneTimezone = "America/New_York", createdAt = 0)
    private val pressed = mutableListOf<String>()

    // Show the sheet with the given result and handled ids, noting each button pressed
    private fun show(result: ShareResult?, added: Set<Long> = emptySet(), ignored: Set<Long> = emptySet()) {
        compose.setContent {
            DatebookTheme {
                ShareSheet(result, added, ignored, onAdd = { pressed.add("add $it") },
                    onIgnore = { pressed.add("ignore $it") }, onRetry = { pressed.add("retry") },
                    onOpenDatebook = { pressed.add("open") }, onClose = { pressed.add("close") })
            }
        }
    }

    // While Claude is reading, the sheet says so
    @Test
    fun loading() {
        show(null)
        compose.onNodeWithText("Looking for plans…").assertExists()
    }

    // Each plan has Add and ✕
    @Test
    fun plansWithButtons() {
        show(ShareResult(listOf(game, game.copy(id = 4, title = "Game 2"))))
        compose.onNodeWithText("Game 1").assertExists()
        compose.onNodeWithText("Game 2").assertExists()
        compose.onNodeWithText("Plans you don't add or ignore wait on Datebook's home screen.").assertExists()
        compose.onNodeWithText("DONE").performClick()
        assertEquals(listOf("close"), pressed)
    }

    // Plans already handled show what happened instead of buttons
    @Test
    fun handledPlans() {
        show(ShareResult(listOf(game, game.copy(id = 4, title = "Game 2"))), added = setOf(3), ignored = setOf(4))
        compose.onNodeWithText("Added ✓").assertExists()
        compose.onNodeWithText("Ignored").assertExists()
        compose.onNodeWithText("ADD").assertDoesNotExist()
    }

    // Tapping Add on a plan adds that plan
    @Test
    fun addOne() {
        show(ShareResult(listOf(game)))
        compose.onNodeWithText("ADD").performClick()
        compose.onNodeWithContentDescription("Ignore").performClick()
        assertEquals(listOf("add 3", "ignore 3"), pressed)
    }

    // A failure that may clear up offers Try again
    @Test
    fun notesWithButtons() {
        show(ShareResult(emptyList(), "Couldn't reach Claude.", canRetry = true))
        compose.onNodeWithText("Couldn't reach Claude.").assertExists()
        compose.onNodeWithText("OPEN DATEBOOK").assertDoesNotExist()
        compose.onNodeWithText("TRY AGAIN").performClick()
        assertEquals(listOf("retry"), pressed)
    }

    // A key problem offers Open Datebook
    @Test
    fun keyNote() {
        show(ShareResult(emptyList(), "Add your Claude API key in Datebook first.", needsKey = true))
        compose.onNodeWithText("OPEN DATEBOOK").performClick()
        compose.onNodeWithText("CLOSE").performClick()
        assertEquals(listOf("open", "close"), pressed)
    }
}
