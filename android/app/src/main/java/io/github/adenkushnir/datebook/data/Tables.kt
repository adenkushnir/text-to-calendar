// The database tables: texts already seen, suggested events, texts waiting to be retried, and failure counts
package io.github.adenkushnir.datebook.data
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.adenkushnir.datebook.pipeline.Event

// A fingerprint of a text that was already handled, so the same text is only handled once (kept 7 days)
@Entity(tableName = "seen")
data class SeenText(
    @PrimaryKey val hash: String,
    val seenAt: Long,
)

// An event Datebook suggested, and whether it is waiting, added or ignored (kept 1 year; never the message text)
@Entity(tableName = "suggestions")
data class Suggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val app: String,
    val title: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val location: String,
    val repeat: String,
    val timezone: String,
    val phoneTimezone: String,
    val status: String = STATUS_WAITING,
    val createdAt: Long,
) {
    // Turn the saved row back into an event
    fun toEvent(): Event {
        return Event(title, start, end, allDay, location, repeat, timezone, phoneTimezone)
    }

    // Names for the three states a suggestion can be in
    companion object {
        const val STATUS_WAITING = "waiting"
        const val STATUS_ADDED = "added"
        const val STATUS_IGNORED = "ignored"
    }
}

// Save an event as a waiting suggestion from the given sender
fun suggestionOf(event: Event, sender: String, app: String, now: Long): Suggestion {
    return Suggestion(
        sender = sender,
        app = app,
        title = event.title,
        start = event.start,
        end = event.end,
        allDay = event.allDay,
        location = event.location,
        repeat = event.repeat,
        timezone = event.timezone,
        phoneTimezone = event.phoneTimezone,
        createdAt = now,
    )
}

// A text to send to Claude again later, because it failed for a reason that may clear up, like no internet
@Entity(tableName = "retry")
data class RetryText(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val text: String,
    val receivedAt: String,
    val app: String,
    val savedAt: Long,
    val nextTry: Long,
)

// How many times in a row a kind of problem has happened, for the alerts
@Entity(tableName = "health")
data class FailureCount(
    @PrimaryKey val kind: String,
    val failures: Int,
)
