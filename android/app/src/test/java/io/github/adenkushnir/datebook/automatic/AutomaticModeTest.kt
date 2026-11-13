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
        assertTrue(isNoise("10:52 AM"))  // Google Messages puts a narrow space before AM
        assertFalse(isNoise("see you at 6:41 PM"))
    }

    // A Google Messages chat as the phone describes it: the list scrolls under the top bar and lists the newest
    // message first, the name sits in the bar's title row (listed after the messages), and the time heading is a
    // message text in a separator
    private fun googleChat(vararg bubbles: Pair<String, Boolean>): ScreenNode {
        val rows = bubbles.mapIndexed { i, (text, mine) ->
            val top = 103 + i * 250  // the first one is partly under the top bar
            val left = if (mine) 663 else 84
            ScreenNode(viewId = "message_bubble_context", children = listOf(
                ScreenNode(text, "message_text", left = left, top = top, right = left + 700, bottom = top + 100)))
        }
        val heading = ScreenNode(viewId = "text_separator", children = listOf(
            ScreenNode("10:52 AM", "message_text", left = 631, top = 2299, right = 809, bottom = 2355)))
        return ScreenNode(children = listOf(
            ScreenNode(viewId = "message_list", scrollable = true, left = 0, top = 149, right = 1440, bottom = 3120,
                children = rows.reversed() + heading),
            ScreenNode(editable = true, left = 196, top = 2843, right = 852, bottom = 3039),
            ScreenNode(viewId = "top_app_bar", children = listOf(ScreenNode(viewId = "top_app_bar_title_row",
                children = listOf(ScreenNode("Sam Rivera", left = 392, top = 219, right = 754, bottom = 303))))),
        ))
    }

    // Google Messages: the name from the top bar, never a message near the top; no time headings
    @Test
    fun readsGoogleMessages() {
        val chat = readChat(googleChat("Run same back." to true, "k change of plans, dinner sat 7:30" to false,
            "Dinner 7pm friday at joes" to true), 1440, 3120)!!
        assertEquals("Sam Rivera", chat.title)
        assertEquals(listOf(Bubble("k change of plans, dinner sat 7:30", false),
            Bubble("Dinner 7pm friday at joes", true)), chat.bubbles)
        val tracker = ChatTracker()
        tracker.update("google", chat)
        val sent = readChat(googleChat("k change of plans, dinner sat 7:30" to false,
            "Dinner 7pm friday at joes" to true, "Lunch Sunday 3pm at the diner" to true), 1440, 3120)!!
        assertEquals(listOf(Bubble("Lunch Sunday 3pm at the diner", true)), tracker.update("google", sent))
    }

    // A GroupMe group chat as the phone describes it: a poll, a "Last read" line, a sender name and time over the
    // message, pinned banners in their own sideways list, the name in the toolbar, and a typing box with a hint
    private fun groupMeChat(): ScreenNode {
        val id = "com.groupme.android:id/"
        val poll = ScreenNode(viewId = "${id}list_item_system_message", children = listOf(
            ScreenNode("WINNER!", "${id}poll_result_header_text", left = 182, top = 73, right = 407, bottom = 149),
            ScreenNode("who's coming to practice today", "${id}poll_title", left = 182, top = 149, right = 952,
                bottom = 225),
            ScreenNode("Votes 4 · Ended Nov 12 at 6 PM", "${id}poll_footer", left = 347, top = 917, right = 941,
                bottom = 974),
        ))
        val message = ScreenNode(viewId = "${id}list_item_chat_message", children = listOf(
            ScreenNode("Last read", "${id}last_read_marker_text", left = 624, top = 2219, right = 816, bottom = 2281),
            ScreenNode("Today", "${id}view_timestamp", left = 635, top = 2309, right = 805, bottom = 2380),
            ScreenNode("Jake Moore", "${id}view_sender_text", left = 182, top = 2454, right = 450, bottom = 2506),
            ScreenNode("Just now", "${id}view_message_timestamp", left = 450, top = 2454, right = 632, bottom = 2506),
            ScreenNode("practice moved to sat 4pm", "${id}view_message_text", left = 182, top = 2506, right = 1226,
                bottom = 2710),
        ))
        return ScreenNode(children = listOf(
            ScreenNode(viewId = "android:id/list", scrollable = true, left = 0, top = 0, right = 1440, bottom = 3120,
                children = listOf(poll, message)),
            ScreenNode(viewId = "${id}pills_container", scrollable = true, left = 0, top = 345, right = 1440,
                bottom = 513, children = listOf(ScreenNode("Alex Chen created a poll", "${id}pill_text",
                    left = 1162, top = 398, right = 1440, bottom = 460))),
            ScreenNode(viewId = "${id}toolbar_title_container", children = listOf(
                ScreenNode("Soccer Team 2025-26", "${id}toolbar_chat_name", left = 308, top = 212,
                    right = 978, bottom = 281))),
            ScreenNode("Message Soccer Team 2025-26", "${id}input_chat_message", editable = true,
                left = 210, top = 2885, right = 859, bottom = 3011),
        ))
    }

    // GroupMe: the group's name from the toolbar, and only the message itself (no poll, names, times or banners)
    @Test
    fun readsGroupMe() {
        val chat = readChat(groupMeChat(), 1440, 3120)!!
        assertEquals("Soccer Team 2025-26", chat.title)
        assertEquals(listOf(Bubble("practice moved to sat 4pm", false)), chat.bubbles)
    }

    // A GroupMe chat with Jamie as the phone describes it: everyone's messages on the left, each labelled with its
    // sender, and a profile picture only on the first of a run (yours says "View your profile")
    private fun groupMeDirect(withYourPicture: Boolean): ScreenNode {
        val id = "com.groupme.android:id/"

        // One message: its label, its sender's picture (if any) and its text
        fun message(sender: String, text: String, top: Int, picture: String?): ScreenNode {
            val pictures = if (picture == null) emptyList() else listOf(ScreenNode(viewId = "${id}view_sender_avatar",
                description = picture, left = 42, top = top, right = 154, bottom = top + 112))
            val words = ScreenNode(text, "${id}view_message_text", left = 182, top = top + 50, right = 900,
                bottom = top + 120)
            return ScreenNode(viewId = "${id}view_chat_message_container",
                description = "Tuesday, 9:46 PM\nMessage from $sender\n\n$text", children = pictures + words)
        }
        val yourPicture = if (withYourPicture) "View your profile" else null
        return ScreenNode(children = listOf(
            ScreenNode(viewId = "android:id/list", scrollable = true, left = 0, top = 0, right = 1440, bottom = 3120,
                children = listOf(
                    message("Jamie Fox", "dinner fri 7pm at joes?", 500, "View contact Jamie Fox"),
                    message("Chris Park", "bowling tues 6pm", 800, yourPicture),
                    message("Chris Park", "see you there", 1100, null),
                )),
            ScreenNode(viewId = "${id}toolbar_title_container", children = listOf(
                ScreenNode("Jamie Fox", "${id}toolbar_chat_name", left = 308, top = 212, right = 978, bottom = 281))),
            ScreenNode("Message Jamie Fox", "${id}input_chat_message", editable = true, left = 210, top = 2885,
                right = 859, bottom = 3011),
        ))
    }

    // GroupMe: your name comes from the message with your picture, and every message sent by that name is yours,
    // even later when your picture isn't on screen; before it's known, none are
    @Test
    fun groupMeKnowsYours() {
        val chat = readChat(groupMeDirect(true), 1440, 3120)!!
        assertEquals("Chris Park", chat.yourName)
        assertEquals(listOf(Bubble("dinner fri 7pm at joes?", false), Bubble("bowling tues 6pm", true),
            Bubble("see you there", true)), chat.bubbles)
        val later = readChat(groupMeDirect(false), 1440, 3120, yourName = "Chris Park")!!
        assertEquals(listOf(false, true, true), later.bubbles.map { it.mine })
        assertEquals(listOf(false, false, false), readChat(groupMeDirect(false), 1440, 3120)!!.bubbles.map { it.mine })
        val names = YourNames(ApplicationProvider.getApplicationContext())
        names.set("com.groupme.android", "Chris Park")
        assertEquals("Chris Park", names.get("com.groupme.android"))
    }

    // Scrolling up to older messages and back down sends nothing; the same text sent twice counts again; a message
    // that arrives while you're scrolled up counts once you're back at the bottom
    @Test
    fun scrollingAndRepeats() {
        val tracker = ChatTracker()

        // Sam's chat showing the given bubbles
        fun screen(vararg texts: String) = ChatScreen("Sam", texts.map { Bubble(it, false) })
        assertTrue(tracker.update("sms", screen("c", "d", "e")).isEmpty())
        assertTrue(tracker.update("sms", screen("a", "b", "c")).isEmpty())  // scrolled up
        assertTrue(tracker.update("sms", screen("c", "d", "e")).isEmpty())  // back down
        assertEquals(listOf(Bubble("e", false)), tracker.update("sms", screen("d", "e", "e")))  // sent twice
        assertTrue(tracker.update("sms", screen("b", "c", "d")).isEmpty())  // scrolled up again
        assertEquals(listOf(Bubble("f", false)), tracker.update("sms", screen("e", "e", "f")))
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
