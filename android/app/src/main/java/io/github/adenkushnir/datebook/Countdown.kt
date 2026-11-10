// Words for how far away an event is, like "today", "tomorrow" or "in 3 days", for the Next up card
package io.github.adenkushnir.datebook
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Describe the gap between today and an event's date
fun countdownLabel(today: LocalDate, eventDay: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, eventDay)

    // Pick the words for the gap
    return when {
        days <= 0L -> "today"
        days == 1L -> "tomorrow"
        else -> "in $days days"
    }
}
