// Works out the home screen's Next up and Coming up from the suggestions you added: those still ahead, soonest first
// (from step 7 this switches to reading your calendar)
package io.github.adenkushnir.datebook
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.pipeline.hasStarted
import io.github.adenkushnir.datebook.pipeline.zoneOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Return the added suggestions that haven't started yet, soonest first (changes you opened aren't new plans)
fun upcoming(added: List<Suggestion>, nowMillis: Long): List<Suggestion> {
    val ahead = added.filter { it.change.isBlank() && !hasStarted(it.toEvent(), nowMillis) }  // drop started ones
    return ahead.sortedBy { eventTimes(it.toEvent()).first }  // soonest start first
}

// The countdown for a suggestion, like "today", "tomorrow" or "in 3 days", counted in the phone's time zone
fun countdownFor(suggestion: Suggestion, nowMillis: Long): String {
    val zone = zoneOf(suggestion.phoneTimezone) ?: ZoneId.systemDefault()
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
    val eventDay = LocalDate.ofInstant(Instant.ofEpochMilli(eventTimes(suggestion.toEvent()).first), zone)
    return countdownLabel(today, eventDay)
}
