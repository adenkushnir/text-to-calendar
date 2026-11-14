// Tests for the share sheet: the spinner, the plans with Add and ✕, the notes with their buttons, and schedules
package io.github.adenkushnir.datebook.share
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    // Three or more plans are a schedule: a ticked list with "Add 3 ticked", where tapping a row unticks it
    @Test
    fun scheduleReview() {
        val plans = listOf(game, game.copy(id = 4, title = "Game 2"), game.copy(id = 5, title = "Game 3"))
        val ticks = mutableListOf<String>()
        var starts = 0
        compose.setContent {
            DatebookTheme {
                ShareSheet(ShareResult(plans), emptySet(), emptySet(), onAdd = {}, onIgnore = {}, onRetry = {},
                    onOpenDatebook = {}, onClose = {}, ticked = setOf(3L, 4L, 5L),
                    onTick = { id, on -> ticks.add("$id $on") }, onAddTicked = { starts += 1 })
            }
        }
        compose.onNodeWithText("Found 3 dates", substring = true).assertExists()
        compose.onNodeWithText("Game 2").performClick()
        assertEquals(listOf("4 false"), ticks)
        compose.onNodeWithText("Add 3 ticked").performClick()
        assertEquals(1, starts)
    }

    // While adding a schedule: how many are added, the next plan, and Add next / Skip / Stop; then "All done"
    @Test
    fun scheduleAdding() {
        val plans = listOf(game, game.copy(id = 4, title = "Game 2"), game.copy(id = 5, title = "Game 3"))
        var queue by mutableStateOf<AddQueue?>(AddQueue(listOf(3L, 4L, 5L), next = 1))
        compose.setContent {
            DatebookTheme {
                ShareSheet(ShareResult(plans), setOf(3L), emptySet(), onAdd = {}, onIgnore = {}, onRetry = {},
                    onOpenDatebook = {}, onClose = { pressed.add("stop") }, queue = queue,
                    onAddNext = { pressed.add("next") }, onSkip = { pressed.add("skip") })
            }
        }
        compose.onNodeWithText("1 OF 3 ADDED").assertExists()
        compose.onNodeWithText("Next: Game 2").assertExists()
        compose.onNodeWithText("Add next").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(listOf("next", "skip", "stop"), pressed)
        queue = AddQueue(listOf(3L, 4L, 5L), next = 3)
        compose.onNodeWithText("All done: 1 of 3 added").assertExists()
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
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("close"), pressed)
    }

    // Plans already handled show what happened instead of buttons
    @Test
    fun handledPlans() {
        show(ShareResult(listOf(game, game.copy(id = 4, title = "Game 2"))), added = setOf(3), ignored = setOf(4))
        compose.onNodeWithText("Added ✓").assertExists()
        compose.onNodeWithText("Ignored").assertExists()
        compose.onNodeWithText("Add").assertDoesNotExist()
    }

    // Tapping Add on a plan adds that plan
    @Test
    fun addOne() {
        show(ShareResult(listOf(game)))
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithContentDescription("Ignore").performClick()
        assertEquals(listOf("add 3", "ignore 3"), pressed)
    }

    // A failure that may clear up offers Try again
    @Test
    fun notesWithButtons() {
        show(ShareResult(emptyList(), "Couldn't reach Claude.", canRetry = true))
        compose.onNodeWithText("Couldn't reach Claude.").assertExists()
        compose.onNodeWithText("Open Datebook").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), pressed)
    }

    // A key problem offers Open Datebook
    @Test
    fun keyNote() {
        show(ShareResult(emptyList(), "Add your Claude API key in Datebook first.", needsKey = true))
        compose.onNodeWithText("Open Datebook").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(listOf("open", "close"), pressed)
    }
}
