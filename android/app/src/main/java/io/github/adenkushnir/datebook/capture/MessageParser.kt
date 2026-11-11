// Turns a messaging notification into the separate messages it holds, skipping your own (plain Kotlin, so it is
// easy to test: the Android listener copies the notification's fields into RawNotification first)
package io.github.adenkushnir.datebook.capture
import io.github.adenkushnir.datebook.pipeline.IncomingText
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
private val SENDER_PREFIX = Regex("""^([^:\n]{1,40}):\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)

// One message inside a conversation notification; sender is null for messages you sent yourself
data class RawMessage(val sender: String?, val text: String, val timeMillis: Long)

// The fields Datebook needs from a notification: which app, its title and text, the conversation's messages
// (empty for plain notifications), whether it is a group chat and the group's name, and when it was posted
data class RawNotification(
    val app: String,
    val title: String,
    val body: String,
    val messages: List<RawMessage> = emptyList(),
    val isGroup: Boolean = false,
    val conversationTitle: String = "",
    val postedMillis: Long,
)

// Return the messages in a notification, oldest first, as texts for the pipeline
fun messagesFrom(raw: RawNotification, zone: ZoneId = ZoneId.systemDefault()): List<IncomingText> {
    // Conversation notifications list every message, each with its own sender and time
    if (raw.messages.isNotEmpty()) {
        val group = if (raw.isGroup) raw.conversationTitle.ifBlank { raw.title } else ""  // group chats only
        val fromOthers = raw.messages.filter { it.sender != null && it.text.isNotBlank() }  // skip your own messages
        return fromOthers.map {
            IncomingText(it.sender!!, it.text, localTime(it.timeMillis, zone), raw.app, group, exactTime = true)
        }
    }

    // A plain notification with nothing to read
    if (raw.body.isBlank()) {
        return emptyList()
    }
    val prefixed = SENDER_PREFIX.find(raw.body)

    // GroupMe group chats show the group as the title and "Sender: text" as the body
    if (raw.app == GROUPME && prefixed != null) {
        val (sender, text) = prefixed.destructured
        return listOf(IncomingText(sender.trim(), text, localTime(raw.postedMillis, zone), raw.app, raw.title))
    }
    return listOf(IncomingText(raw.title, raw.body, localTime(raw.postedMillis, zone), raw.app))
}

// Turn milliseconds since 1970 into the phone's local date and time
private fun localTime(millis: Long, zone: ZoneId): LocalDateTime {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
}
