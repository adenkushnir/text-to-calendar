// Tests for the Notes tab: reminders and notes listed, search, saving what's typed, and deleting
package io.github.adenkushnir.datebook.notes
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_NOTE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks what the Notes tab shows and what its buttons do
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotesScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val newYork = ZoneId.of("America/New_York")
    private val nine = LocalDateTime.of(2037, 11, 16, 9, 0).atZone(newYork).toInstant().toEpochMilli()
    private val trash = FollowUp(id = 1, kind = KIND_REMINDER, title = "Take out the trash", date = "", amount = "",
        sender = "you", app = "datebook.typed", conversation = "", status = FollowUp.OPEN, createdAt = 0,
        nextNudge = nine, nudges = 0, repeat = "FREQ=WEEKLY;BYDAY=MO")
    private val gifts = trash.copy(id = 2, kind = KIND_NOTE, title = "Gift ideas for Mom", nextNudge = 0, repeat = "",
        body = "Gift ideas for Mom\nscarf, the blue mug")

    // A reminder shows when it goes off and how it repeats; a note shows its first lines; search narrows both
    @Test
    fun listed() {
        compose.setContent {
            DatebookTheme {
                NotesScreen(listOf(trash), listOf(gifts), newYork, NotesActions())
            }
        }
        compose.onNodeWithText("Mon Nov 16, 9:00 AM · Every Monday").assertExists()
        compose.onNodeWithText("scarf, the blue mug").assertExists()
        compose.onNodeWithText("Search notes and reminders").performTextInput("mug")
        compose.onNodeWithText("No reminders match.").assertExists()
        compose.onNodeWithText("Gift ideas for Mom").assertExists()
    }

    // Saving sends what was typed and shows what was made of it; ✕ deletes
    @Test
    fun saveAndDelete() {
        val saved = mutableListOf<String>()
        val deleted = mutableListOf<Long>()
        val actions = NotesActions(onSave = { saved.add(it); "Reminder set." }, onDelete = { deleted.add(it) })
        compose.setContent {
            DatebookTheme {
                NotesScreen(listOf(trash), emptyList(), newYork, actions)
            }
        }
        compose.onNodeWithText("A note, or \"remind me to…\"").performTextInput("remind me to stretch at 5")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("remind me to stretch at 5"), saved)
        compose.onNodeWithText("Reminder set.").assertExists()
        compose.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf(1L), deleted)
    }
}
