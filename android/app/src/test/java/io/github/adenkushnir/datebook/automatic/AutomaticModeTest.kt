// Tests for automatic mode: reading a chat from the screen's description, spotting new bubbles, turning them into
// texts, group chats, the prompt for texts you send, and the Settings page
package io.github.adenkushnir.datebook.automatic
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.buildPrompt
import io.github.adenkushnir.datebook.prompts.calendarNotes
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.settings.AutomaticPage
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks automatic mode from the screen to the prompt
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticModeTest {
    @get:Rule
    val compose = createComposeRule()
    private val width = 1080
    private val height = 2340

    // A chat screen: Sam's name at the top, a time heading, two bubbles, and the typing box with a draft
    private fun chatScreen(vararg bubbles: Pair<String, Boolean>): ScreenNode {
        val rows = bubbles.mapIndexed { i, (text, mine) ->
            val top = 400 + i * 200
            if (mine) ScreenNode(text, "message_text", left = 500, top = top, right = 1040, bottom = top + 150)
            else ScreenNode(text, "message_text", left = 40, top = top, right = 600, bottom = top + 150)
        }
        return ScreenNode(children = listOf(
            ScreenNode("Sam", "com.example:id/conversation_title", left = 150, top = 120, right = 600, bottom = 200),
            ScreenNode("6:41 PM", left = 480, top = 320, right = 600, bottom = 360),
            ScreenNode(children = rows),
            ScreenNode("my half-typed draft fri 9pm", editable = true, left = 40, top = 2100, right = 900,
                bottom = 2200),
        ))
    }

    // The chat's name, its bubbles (yours on the right) and nothing from the typing box or time headings
    @Test
    fun readsChat() {
        val chat = readChat(chatScreen("dinner fri 7pm at luigi's?" to true, "yes! see you then" to false), width,
            height)!!
        assertEquals("Sam", chat.title)
        assertEquals(listOf(Bubble("dinner fri 7pm at luigi's?", true), Bubble("yes! see you then", false)),
            chat.bubbles)
        assertNull(readChat(ScreenNode(children = listOf(ScreenNode("Conversations", top = 100, bottom = 180))),
            width, height))  // no typing box: the conversation list, not a chat
        assertTrue(isNoise("Delivered"))
        assertTrue(isNoise("6:41 PM"))
        assertFalse(isNoise("see you at 6:41 PM"))
    }

    // What's showing when a chat opens counts as seen; later bubbles are new; another chat starts over
    @Test
    fun onlyNewBubbles() {
        val tracker = ChatTracker()
        val first = readChat(chatScreen("old plan sat 2pm" to false), width, height)!!
        assertTrue(tracker.update("sms", first).isEmpty())
        val later = readChat(chatScreen("old plan sat 2pm" to false, "dinner fri 7pm?" to true), width, height)!!
        assertEquals(listOf(Bubble("dinner fri 7pm?", true)), tracker.update("sms", later))
        assertTrue(tracker.update("sms", later).isEmpty())
        val other = ChatScreen("Alex", listOf(Bubble("lunch tmrw at noon", false)))
        assertTrue(tracker.update("sms", other).isEmpty())
        tracker.leave()
        assertNull(tracker.current)
    }

    // One-to-one chats are named after the person; groups after the group; your texts are marked as yours
    @Test
    fun bubblesBecomeTexts() {
        val now = LocalDateTime.of(2037, 11, 12, 18, 41)
        val mine = incomingFrom("sms", "Sam", Bubble("dinner fri 7pm?", true), false, now)
        assertEquals("Sam", mine.sender)
        assertTrue(mine.fromMe && mine.onScreen)
        assertEquals("", mine.group)
        val group = incomingFrom("groupme", "Soccer Team", Bubble("scrimmage sun 4pm", false), true, now)
        assertEquals("Soccer Team", group.group)
        assertEquals("", group.sender)
        assertTrue(buildPrompt(mine, "").contains("From: the phone's owner, writing to Sam"))
    }

    // Group chats are learned from their notifications
    @Test
    fun groupChatsRemembered() {
        val groups = GroupChats(ApplicationProvider.getApplicationContext())
        assertFalse(groups.isGroup("groupme", "Soccer Team"))
        groups.remember("groupme", "Soccer Team")
        assertTrue(groups.isGroup("groupme", "Soccer Team"))
    }

    // Plans from texts you sent say who they went to
    @Test
    fun yourTextsNamed() {
        val plan = Suggestion(sender = "Sam", app = "com.samsung.android.messaging", title = "Dinner",
            start = "2037-11-13T19:00", end = "", allDay = false, location = "", repeat = "", timezone = "",
            phoneTimezone = "America/New_York", createdAt = 0, fromMe = true)
        assertEquals("you, to Sam", fromLine(plan))
        assertEquals("You → Sam (Messages)", calendarNotes(plan))
    }

    // The Settings page says whether it's on, lists the apps it reads, and opens Android's settings
    @Test
    fun settingsPage() {
        var opened = 0
        compose.setContent {
            DatebookTheme {
                AutomaticPage(on = false, apps = listOf("Messages (Samsung)", "GroupMe"),
                    openSettings = { opened += 1 }, onBack = {})
            }
        }
        compose.onNodeWithText("Only the apps switched on in Apps to watch: Messages (Samsung), GroupMe.")
            .assertExists()
        compose.onNodeWithText("TURN ON").performScrollTo().performClick()
        assertEquals(1, opened)
    }
}
