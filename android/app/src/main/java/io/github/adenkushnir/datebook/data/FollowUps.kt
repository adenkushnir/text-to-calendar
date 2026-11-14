// Follow-ups: loose plans ("we should get lunch next week") to pin down later, and bills and due dates ("phone bill of
// $45 due Nov 20") to be reminded about; they're kept apart from calendar plans and never become calendar events
package io.github.adenkushnir.datebook.data
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
const val KIND_LOOSE = "loose"
const val KIND_DUE = "due"
const val NUDGE_HOUR = 9  // nudges and reminders come at 9 in the morning
private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private val DAY_NAME = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

// One follow-up: a loose plan or something due, who it came from (and which chat), its day (YYYY-MM-DD, "" when
// none), the amount for a bill, whether it's open, done (paid, or pinned down) or dropped, when it was found, when its
// next nudge or reminder is due (0 for none), and how many have been sent
@Entity(tableName = "followups")
data class FollowUp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val date: String,
    val amount: String,
    val sender: String,
    val app: String,
    val conversation: String,
    val status: String,
    val createdAt: Long,
    val nextNudge: Long,
    val nudges: Int,
) {
    // Names for the states a follow-up can be in
    companion object {
        const val OPEN = "open"
        const val DONE = "done"
        const val DROPPED = "dropped"
    }
}

// The time of day a nudge comes on a given day, in milliseconds
private fun morningOf(day: LocalDate, zone: ZoneId): Long {
    return day.atTime(NUDGE_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
}

// When a new follow-up's first nudge or reminder is due (0 for none): a loose plan on the first day of the time it
// names (or 3 days after it came, when it names none or that day has passed), a bill 2 days before it's due (or on the
// day, if that's already passed); nothing for a bill whose day is over
fun firstNudge(kind: String, date: String, createdAt: Long, zone: ZoneId): Long {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
    val inThreeDays = morningOf(Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate().plusDays(3), zone)

    // Pick the time for each kind
    return when {
        kind == KIND_LOOSE && day != null && morningOf(day, zone) > createdAt -> morningOf(day, zone)
        kind == KIND_LOOSE -> inThreeDays
        day == null -> 0
        morningOf(day.minusDays(2), zone) > createdAt -> morningOf(day.minusDays(2), zone)
        morningOf(day, zone) + DAY_MILLIS > createdAt -> maxOf(morningOf(day, zone), createdAt)
        else -> 0
    }
}

// How a follow-up's day reads, like "Fri Nov 20" ("" when it has none)
fun followUpDay(followUp: FollowUp): String {
    return runCatching { LocalDate.parse(followUp.date).format(DAY_NAME) }.getOrDefault("")
}
