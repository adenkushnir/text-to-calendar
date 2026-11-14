// The database tables: texts already seen, suggested events, texts waiting to be retried, and failure counts
package io.github.adenkushnir.datebook.data
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.adenkushnir.datebook.pipeline.Event
import io.github.adenkushnir.datebook.pipeline.IncomingText

// A fingerprint of a text that was already handled, so the same text is only handled once (kept 7 days)
@Entity(tableName = "seen")
data class SeenText(
    @PrimaryKey val hash: String,
    val seenAt: Long,
)

// An event Datebook suggested, who from (and which group chat), whether it is waiting, added or ignored, the clash
// warning shown with it, for a changed or cancelled plan which calendar event it changes, and its flight, package
// and place coordinates, its start before it changed in the chat while waiting, whether you sent the text, and for an
// offline guess the retry-queue text Claude is still to check (0 for plans Claude read), and its confirmation code
// (kept 1 year; never the message text)
@Entity(tableName = "suggestions")
data class Suggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val app: String,
    val conversation: String = "",
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
    val clash: String = "",
    val change: String = "",
    val calendarEventId: Long = 0,
    val flight: String = "",
    val packageCarrier: String = "",
    val trackingNumber: String = "",
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    @ColumnInfo(defaultValue = "") val previousStart: String = "",
    @ColumnInfo(defaultValue = "0") val fromMe: Boolean = false,
    @ColumnInfo(defaultValue = "0") val retryId: Long = 0,
    @ColumnInfo(defaultValue = "") val confirmation: String = "",
) {
    // Turn the saved row back into an event
    fun toEvent(): Event {
        return Event(title, start, end, allDay, location, repeat, timezone, phoneTimezone, flight = flight,
            packageCarrier = packageCarrier, trackingNumber = trackingNumber, placeLat = placeLat, placeLng = placeLng,
            confirmation = confirmation)
    }

    // Names for the three states a suggestion can be in, plus the one for a calendar event shown on its card (never
    // saved)
    companion object {
        const val STATUS_WAITING = "waiting"
        const val STATUS_ADDED = "added"
        const val STATUS_IGNORED = "ignored"
        const val STATUS_CALENDAR = "calendar"
    }
}

// Save an event as a waiting suggestion from the text's sender, app and group chat (empty for one-to-one), with its
// clash warning (empty when nothing clashes), and for a change, which calendar event it changes
fun suggestionOf(event: Event, incoming: IncomingText, now: Long, clash: String = ""): Suggestion {
    return Suggestion(
        sender = incoming.sender,
        app = incoming.app,
        conversation = incoming.group,
        title = event.title,
        start = event.start,
        end = event.end,
        allDay = event.allDay,
        location = event.location,
        repeat = event.repeat,
        timezone = event.timezone,
        phoneTimezone = event.phoneTimezone,
        createdAt = now,
        clash = clash,
        change = event.change,
        calendarEventId = event.calendarEventId,
        flight = event.flight,
        packageCarrier = event.packageCarrier,
        trackingNumber = event.trackingNumber,
        placeLat = event.placeLat,
        placeLng = event.placeLng,
        fromMe = incoming.fromMe,
        confirmation = event.confirmation,
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
    val conversation: String = "",
    val savedAt: Long,
    val nextTry: Long,
)

// How many times in a row a kind of problem has happened, for the alerts
@Entity(tableName = "health")
data class FailureCount(
    @PrimaryKey val kind: String,
    val failures: Int,
)
