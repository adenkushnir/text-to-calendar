// The changes Claude proposes in Deliberate: read from its propose_changes call, checked for what each one needs,
// and turned into plan times and rules
package io.github.adenkushnir.datebook.deliberate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// One proposed change: what to do and the fields Claude filled in (empty or 0 when not given; early is null when not
// given, since 0 means right on time)
data class Change(
    val action: String,
    val id: Long = 0,
    val begin: Long = 0,
    val title: String = "",
    val date: String = "",
    val time: String = "",
    val endDate: String = "",
    val endTime: String = "",
    val place: String = "",
    val amount: String = "",
    val repeat: String = "",
    val text: String = "",
    val carrier: String = "",
    val trackingNumber: String = "",
    val ruleKind: String = "",
    val words: String = "",
    val sender: String = "",
    val value: String = "",
    val pause: String = "",
    val until: String = "",
    val setting: String = "",
    val target: String = "",
    val link: String = "",
    val flight: String = "",
    val travel: String = "",
    val early: Int? = null,
    val station: String = "",
    val to: String = "",
)

// A field of a tool call's input as trimmed text ("" when missing)
private fun field(map: Map<*, *>, name: String): String {
    return map[name]?.toString()?.trim().orEmpty()
}

// A field of a tool call's input as a whole number (0 when missing or not a number)
private fun number(map: Map<*, *>, name: String): Long {
    val value = map[name]
    return (value as? Number)?.toLong() ?: value?.toString()?.trim()?.toLongOrNull() ?: 0
}

// The changes in a propose_changes call's input (anything that isn't a change is skipped)
fun changesOf(input: Map<String, Any?>): List<Change> {
    val list = input["changes"] as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        Change(
            action = field(map, "action"), id = number(map, "id"), begin = number(map, "begin"),
            title = field(map, "title"), date = field(map, "date"), time = field(map, "time"),
            endDate = field(map, "end_date"), endTime = field(map, "end_time"), place = field(map, "place"),
            amount = field(map, "amount"), repeat = field(map, "repeat").uppercase().removePrefix("RRULE:"),
            text = field(map, "text"), carrier = field(map, "carrier").lowercase(),
            trackingNumber = field(map, "tracking_number").replace(" ", ""), ruleKind = field(map, "rule_kind"),
            words = field(map, "words").lowercase(), sender = field(map, "sender"), value = field(map, "value"),
            pause = field(map, "pause"), until = field(map, "until"), setting = field(map, "setting"),
            target = field(map, "target"), link = field(map, "link"),
            flight = field(map, "flight").uppercase().replace(" ", ""), travel = field(map, "travel").uppercase(),
            early = field(map, "early").toDoubleOrNull()?.toInt(), station = field(map, "station"),
            to = field(map, "to"),
        )
    }
}

// Return true if a date reads as YYYY-MM-DD
fun isDate(text: String): Boolean {
    return runCatching { LocalDate.parse(text) }.isSuccess
}

// Return true if a time reads as HH:MM
fun isTime(text: String): Boolean {
    return runCatching { LocalTime.parse(text) }.isSuccess
}

// A plan's start or end as Datebook keeps it: "YYYY-MM-DDTHH:MM", or the date alone for all day ("" without a date)
fun planTime(date: String, time: String): String {
    // No date, no time
    if (!isDate(date)) {
        return ""
    }
    return if (isTime(time)) "${date}T${LocalTime.parse(time)}" else date
}

// What's wrong with a change before it can be shown (null when it has what it needs)
fun problemWith(change: Change): String? {
    // Check what each action needs
    return when (change.action) {
        ACT_NEW_PLAN -> when {
            change.flight.isNotBlank() && !isDate(change.date) -> "a flight plan needs the day it leaves"
            change.flight.isBlank() && (change.title.isBlank() || !isDate(change.date)) ->
                "a new plan needs a title and a date"
            else -> travelProblem(change)
        }
        ACT_SET_PLAN_TRAVEL -> when {
            change.id <= 0 || change.target !in setOf(TARGET_PLAN, TARGET_EVENT) ->
                "set_plan_travel needs an id and a target (plan or event)"
            change.target == TARGET_EVENT && change.begin <= 0 -> "set_plan_travel on an event needs its begin"
            change.travel.isBlank() && change.early == null && change.station.isBlank() ->
                "set_plan_travel needs travel, early or station"
            else -> travelProblem(change)
        }
        ACT_EDIT_WAITING, ACT_ADD_WAITING, ACT_IGNORE_WAITING, ACT_EDIT_ITEM, ACT_MARK_DONE, ACT_DELETE_ITEM,
        ACT_PACKAGE_RECEIVED, ACT_TURN_RULE, ACT_DELETE_RULE -> if (change.id <= 0) "${change.action} needs an id"
            else null
        ACT_MOVE_EVENT -> if (change.id <= 0 || change.begin <= 0 || !isDate(change.date))
            "move_event needs the event's id and begin, and the new date" else null
        ACT_CANCEL_EVENT -> if (change.id <= 0 || change.begin <= 0) "cancel_event needs the event's id and begin"
            else null
        ACT_ADD_BILL -> if (change.title.isBlank() || !isDate(change.date)) "a bill needs a title and a due date"
            else null
        ACT_ADD_ASSIGNMENT -> if (change.title.isBlank() || !isDate(change.date))
            "an assignment needs a title and a due date" else null
        ACT_ADD_REMINDER -> if (change.title.isBlank()) "a reminder needs a title" else null
        ACT_ADD_NOTE -> if (change.text.isBlank() && change.title.isBlank()) "a note needs text" else null
        ACT_ADD_PACKAGE -> if (change.trackingNumber.isBlank()) "a package needs a tracking number" else null
        ACT_ADD_RULE -> ruleProblem(change)
        ACT_PAUSE -> if (change.pause !in PAUSE_KINDS || untilMillis(change.until, ZoneId.systemDefault()) == null)
            "a pause needs what to pause and when it ends" else null
        ACT_SETTING -> if (change.setting !in SETTINGS) "unknown setting ${change.setting}" else null
        ACT_SET_LINK -> when {
            change.id <= 0 || change.target !in setOf(TARGET_PLAN, TARGET_ITEM) -> "set_link needs an id and a target"
            change.link.isNotBlank() && !change.link.startsWith("http") -> "set_link needs a web link"
            else -> null
        }
        else -> "unknown action ${change.action}"
    }
}

// What's wrong with a plan's own travel settings (null when they're fine or not given)
private fun travelProblem(change: Change): String? {
    val early = change.early

    // Check the mode and the minutes
    return when {
        change.travel !in TRAVEL_CHOICES -> "unknown travel mode ${change.travel}"
        early != null && (early < 0 || early > MAX_EARLY_MINUTES) -> "early must be 0 to $MAX_EARLY_MINUTES minutes"
        else -> null
    }
}

// What's wrong with a new rule (null when it has what it needs)
private fun ruleProblem(change: Change): String? {
    val minutes = change.value.toIntOrNull()

    // Check each kind's value
    return when (change.ruleKind) {
        RULE_TRAVEL, RULE_EARLY, RULE_ALERT -> if (minutes == null || minutes < 0) "${change.ruleKind} needs minutes"
            else if (change.words.isBlank() && change.place.isBlank()) "a rule needs words or a place" else null
        RULE_MODE, RULE_BRING -> if (change.value.isBlank()) "${change.ruleKind} needs a value"
            else if (change.words.isBlank() && change.place.isBlank()) "a rule needs words or a place" else null
        RULE_REMIND -> if (change.value != REMIND_EVENING && minutes == null) "remind_before needs minutes or evening"
            else if (change.words.isBlank() && change.place.isBlank()) "a rule needs words or a place" else null
        RULE_QUIET -> if (change.words.isBlank() && change.place.isBlank()) "a rule needs words or a place" else null
        RULE_IGNORE -> if (change.words.isBlank() && change.sender.isBlank()) "an ignore rule needs words or a sender"
            else null
        RULE_HABIT -> if (change.value.isBlank()) "a habit needs words" else null
        else -> "unknown rule kind ${change.ruleKind}"
    }
}

// The rule a change makes
fun ruleOf(change: Change, nowMillis: Long): Rule {
    return Rule(kind = change.ruleKind, words = change.words, place = change.place, sender = change.sender,
        value = change.value, isOn = true, createdAt = nowMillis)
}

// When a pause ends, in milliseconds, from "YYYY-MM-DDTHH:MM" (or a date, meaning its start); null when unreadable
fun untilMillis(until: String, zone: ZoneId): Long? {
    val time = runCatching { LocalDateTime.parse(until) }.getOrNull()
        ?: runCatching { LocalDate.parse(until).atStartOfDay() }.getOrNull() ?: return null
    return time.atZone(zone).toInstant().toEpochMilli()
}
