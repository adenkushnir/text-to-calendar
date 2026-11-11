// Tests for turning messaging notifications into separate messages, including GroupMe's group format
package io.github.adenkushnir.datebook.capture
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks conversation notifications, plain notifications and GroupMe groups and DMs
class MessageParserTest {
    private val zone = ZoneId.of("America/New_York")
    private val noon = ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    // Conversation notifications give every message with its own sender and time, skipping your own
    @Test
    fun conversationMessages() {
        val raw = RawNotification(SAMSUNG_MESSAGES, "Sam", "dinner fri 7pm", postedMillis = noon, messages = listOf(
            RawMessage("Sam", "you free this week?", noon - 60_000),
            RawMessage(null, "yeah why", noon - 30_000),
            RawMessage("Sam", "dinner fri 7pm", noon),
        ))
        val messages = messagesFrom(raw, zone)
        assertEquals(listOf("you free this week?", "dinner fri 7pm"), messages.map { it.text })
        assertTrue(messages.all { it.sender == "Sam" && it.group.isEmpty() && it.exactTime })
        assertEquals(LocalDateTime.of(2026, 11, 10, 12, 0), messages.last().receivedAt)
    }

    // A group conversation keeps each sender and the group's name
    @Test
    fun groupConversation() {
        val raw = RawNotification(GOOGLE_MESSAGES, "Family", "", postedMillis = noon, isGroup = true,
            conversationTitle = "Family", messages = listOf(RawMessage("Mom", "brunch sun 11am", noon)))
        val message = messagesFrom(raw, zone).single()
        assertEquals("Mom", message.sender)
        assertEquals("Family", message.group)
    }

    // A plain text notification is one message from the title's sender
    @Test
    fun plainText() {
        val message = messagesFrom(RawNotification(SAMSUNG_MESSAGES, "Sam", "lunch at noon", postedMillis = noon), zone)
            .single()
        assertEquals("Sam", message.sender)
        assertEquals("lunch at noon", message.text)
        assertEquals("", message.group)
    }

    // GroupMe groups put the group as the title and "Sender: text" as the body
    @Test
    fun groupMeGroup() {
        val raw = RawNotification(GROUPME, "Soccer Team", "Jake: practice moved to sat 9am", postedMillis = noon)
        val message = messagesFrom(raw, zone).single()
        assertEquals("Jake", message.sender)
        assertEquals("practice moved to sat 9am", message.text)
        assertEquals("Soccer Team", message.group)
    }

    // A GroupMe DM is one message from the title's sender, with no group
    @Test
    fun groupMeDirectMessage() {
        val raw = RawNotification(GROUPME, "Jake", "see you at 6pm", postedMillis = noon)
        val message = messagesFrom(raw, zone).single()
        assertEquals("Jake", message.sender)
        assertEquals("", message.group)
    }

    // Empty notifications give no messages
    @Test
    fun emptyNotification() {
        assertTrue(messagesFrom(RawNotification(SAMSUNG_MESSAGES, "Sam", "", postedMillis = noon), zone).isEmpty())
    }
}
