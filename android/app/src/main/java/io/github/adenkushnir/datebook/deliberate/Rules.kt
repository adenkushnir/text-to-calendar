// Deliberate's rules: which events or plans a rule covers (title words, a place, a sender), what the rules change for
// an event (travel time, arriving early, an earlier alert, the travel mode, what to bring, reminders, no alerts), which
// new plans are ignored, the habits Claude uses when reading texts, and how a rule reads
package io.github.adenkushnir.datebook.deliberate
import io.github.adenkushnir.datebook.places.travelMode
const val RULE_TRAVEL = "travel_minutes"
const val RULE_EARLY = "arrive_early"
const val RULE_ALERT = "alert_earlier"
const val RULE_MODE = "travel_mode"
const val RULE_BRING = "bring"
const val RULE_REMIND = "remind_before"
const val RULE_QUIET = "no_alerts"
const val RULE_IGNORE = "ignore"
const val RULE_HABIT = "habit"
const val REMIND_EVENING = "evening"  // a reminder at 8 PM the evening before
val RULE_KINDS = listOf(RULE_TRAVEL, RULE_EARLY, RULE_ALERT, RULE_MODE, RULE_BRING, RULE_REMIND, RULE_QUIET,
    RULE_IGNORE, RULE_HABIT)
private val SPLIT = Regex("\\s*[|,]\\s*")

// What the rules change for one calendar event: its travel time (null to ask Google), extra minutes to arrive early,
// minutes to bring the alert forward, a travel mode (null for the default), things to bring, reminders before it (in
// minutes, or the evening before), whether it gets no alerts at all, and the early minutes a rule set (null when no
// rule says, so a flight can fall back to its own default)
data class EventEffects(
    val minutes: Int? = null,
    val earlyMinutes: Int = 0,
    val alertEarlier: Int = 0,
    val mode: String? = null,
    val bring: List<String> = emptyList(),
    val remindBefore: List<String> = emptyList(),
    val quiet: Boolean = false,
    val early: Int? = null,
)

// The phrases a rule's words stand for: "soccer practice|fencing practice" gives both, lowercase
fun phrases(words: String): List<String> {
    return words.lowercase().split("|").map { it.trim() }.filter { it.isNotBlank() }
}

// Return true if a rule covers an event with this title and place: its words (any one phrase) are in the title and
// its place is in the event's place; a rule with neither covers every event
fun coversEvent(rule: Rule, title: String, location: String): Boolean {
    val words = phrases(rule.words)
    val titleOk = words.isEmpty() || words.any { title.lowercase().contains(it) }
    val placeOk = rule.place.isBlank() || location.lowercase().contains(rule.place.trim().lowercase())
    return titleOk && placeOk
}

// Return true if an ignore rule covers a new plan: from its sender or group (when the rule names one), with its words
// in the title (when it has any), and of its kind of plan (when it names one, like "due" for bills); a rule with no
// sender and no words covers nothing, so it can't ignore everything by mistake
fun coversPlan(rule: Rule, sender: String, group: String, title: String, kind: String): Boolean {
    val who = rule.sender.trim().lowercase()
    val words = phrases(rule.words)

    // An empty rule never matches
    if (who.isBlank() && words.isEmpty()) {
        return false
    }
    val fromOk = who.isBlank() || sender.lowercase().contains(who) || group.lowercase().contains(who)
    val titleOk = words.isEmpty() || words.any { title.lowercase().contains(it) }
    val kindOk = rule.value.isBlank() || rule.value.equals(kind, ignoreCase = true)
    return fromOk && titleOk && kindOk
}

// Return true if a new plan should be ignored by one of the rules that are on
fun ignoredByRules(rules: List<Rule>, sender: String, group: String, title: String, kind: String): Boolean {
    return rules.any { it.isOn && it.kind == RULE_IGNORE && coversPlan(it, sender, group, title, kind) }
}

// The habits that are on, like "Dinners last 2 hours", for Claude to use when it reads texts
fun habitsOf(rules: List<Rule>): List<String> {
    return rules.filter { it.isOn && it.kind == RULE_HABIT && it.value.isNotBlank() }.map { it.value.trim() }
}

// What the rules that are on change for an event; the newest rule wins where only one can apply (rules come newest
// first), and every matching bring list and reminder counts
fun effectsFor(rules: List<Rule>, title: String, location: String): EventEffects {
    val matching = rules.filter { it.isOn && it.kind != RULE_IGNORE && it.kind != RULE_HABIT &&
        coversEvent(it, title, location) }

    // The first (newest) rule of a kind with a usable value
    fun minutesOf(kind: String): Int? {
        return matching.filter { it.kind == kind }.firstNotNullOfOrNull { it.value.trim().toIntOrNull() }
    }
    val mode = matching.firstOrNull { it.kind == RULE_MODE && it.value.isNotBlank() }?.value?.trim()?.uppercase()
    val early = minutesOf(RULE_EARLY)?.coerceIn(0, 24 * 60)
    return EventEffects(
        minutes = minutesOf(RULE_TRAVEL)?.coerceIn(0, 24 * 60),
        earlyMinutes = early ?: 0,
        alertEarlier = minutesOf(RULE_ALERT)?.coerceIn(0, 24 * 60) ?: 0,
        mode = mode?.let { travelMode(it).id },
        bring = matching.filter { it.kind == RULE_BRING }.flatMap { it.value.split(SPLIT) }.map { it.trim() }
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() },
        remindBefore = matching.filter { it.kind == RULE_REMIND }.map { it.value.trim() }.filter { it.isNotBlank() },
        quiet = matching.any { it.kind == RULE_QUIET },
        early = early,
    )
}

// Merge an event's own bring line ("cleats, $20") with the rules' things to bring, without repeats
fun mergeBring(own: String, extra: List<String>): String {
    val items = own.split(SPLIT).map { it.trim() }.filter { it.isNotBlank() }.toMutableList()

    // Add each extra thing that isn't already there
    for (item in extra) {
        // Skip things already on the list
        if (items.none { it.equals(item, ignoreCase = true) }) {
            items.add(item)
        }
    }
    return items.joinToString(", ")
}

// Minutes as words: "50 min", "2 h", "1 h 30 min"
fun minutesText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60

    // Pick the shortest way to say it
    return when {
        hours == 0 -> "$minutes min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest min"
    }
}

// Which events or plans a rule covers, in words: "Title has “practice” · at “Hilltop” · from “Soccer Team”", or
// "Every event"
fun scopeText(rule: Rule): String {
    val parts = mutableListOf<String>()

    // Name each part the rule matches on
    if (rule.words.isNotBlank()) {
        parts.add("Title has " + phrases(rule.words).joinToString(" or ") { "“$it”" })
    }

    if (rule.place.isNotBlank()) {
        parts.add("at “${rule.place.trim()}”")
    }

    if (rule.sender.isNotBlank()) {
        parts.add("from “${rule.sender.trim()}”")
    }
    return if (parts.isEmpty()) "Every event" else parts.joinToString(" · ")
}

// What a reminder rule's value means: "the evening before at 8 PM", or "2 h before"
fun remindText(value: String): String {
    return if (value == REMIND_EVENING) "the evening before at 8 PM"
        else value.toIntOrNull()?.let { "${minutesText(it)} before" } ?: value
}

// A rule in words, like "Title has “practice” → 50 min travel"
fun ruleText(rule: Rule): String {
    val value = rule.value.trim()

    // Say what each kind does
    val effect = when (rule.kind) {
        RULE_TRAVEL -> "${minutesText(value.toIntOrNull() ?: 0)} travel, instead of Google Maps"
        RULE_EARLY -> "be there ${minutesText(value.toIntOrNull() ?: 0)} early"
        RULE_ALERT -> "leave-by alert ${minutesText(value.toIntOrNull() ?: 0)} earlier"
        RULE_MODE -> "go by ${travelMode(value.uppercase()).name.lowercase()}"
        RULE_BRING -> "bring $value"
        RULE_REMIND -> "remind me ${remindText(value)}"
        RULE_QUIET -> "no leave-by, bring or weather alerts"
        RULE_IGNORE -> if (value.isBlank()) "never prompt" else "never prompt (${kindName(value)})"
        RULE_HABIT -> return "Habit: $value"
        else -> value
    }
    return "${scopeText(rule)} → $effect"
}

// A kind of plan in words, for ignore rules
private fun kindName(kind: String): String {
    // Name each kind of plan
    return when (kind.lowercase()) {
        "due" -> "bills"
        "assignment" -> "assignments"
        "reminder" -> "reminders"
        "loose" -> "loose plans"
        else -> "plans"
    }
}
