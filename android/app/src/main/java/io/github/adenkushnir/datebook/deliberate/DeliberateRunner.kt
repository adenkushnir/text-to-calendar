// Carries out Deliberate's changes on the phone: describes each one for the preview (with the events a rule would
// cover, and a card for a new flight), makes them once you confirm while noting how to put everything back, and undoes
// them
package io.github.adenkushnir.datebook.deliberate
import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.EventFields
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.calendar.canWriteCalendar
import io.github.adenkushnir.datebook.calendar.deleteEvent
import io.github.adenkushnir.datebook.calendar.planFields
import io.github.adenkushnir.datebook.calendar.readEvent
import io.github.adenkushnir.datebook.calendar.updateEvent
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_NOTE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.firstNudge
import io.github.adenkushnir.datebook.data.firstReminder
import io.github.adenkushnir.datebook.data.nextBill
import io.github.adenkushnir.datebook.data.noteTitle
import io.github.adenkushnir.datebook.data.repeatLabel
import io.github.adenkushnir.datebook.flights.FlightInfo
import io.github.adenkushnir.datebook.flights.flightEarlyMinutes
import io.github.adenkushnir.datebook.flights.flightTitle
import io.github.adenkushnir.datebook.flights.legMatches
import io.github.adenkushnir.datebook.flights.lookupFlight
import io.github.adenkushnir.datebook.flights.withFlight
import io.github.adenkushnir.datebook.followups.bookNextFollowUp
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.leaveby.leaveTime
import io.github.adenkushnir.datebook.leaveby.planLeaveBy
import io.github.adenkushnir.datebook.lists.PackageStore
import io.github.adenkushnir.datebook.pipeline.CHANGE_CANCELLED
import io.github.adenkushnir.datebook.pipeline.CHANGE_MOVED
import io.github.adenkushnir.datebook.pipeline.DELIBERATE_APP
import io.github.adenkushnir.datebook.pipeline.Event
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.pipeline.parseLocal
import io.github.adenkushnir.datebook.pipeline.summary
import io.github.adenkushnir.datebook.pipeline.zoneOf
import io.github.adenkushnir.datebook.places.findPlace
import io.github.adenkushnir.datebook.places.travelMode
import io.github.adenkushnir.datebook.prompts.addNow
import io.github.adenkushnir.datebook.prompts.addSuggestion
import io.github.adenkushnir.datebook.prompts.ignoreSuggestion
import io.github.adenkushnir.datebook.prompts.promptTitle
import io.github.adenkushnir.datebook.summary.scheduleSummary
import io.github.adenkushnir.datebook.trains.Station
import io.github.adenkushnir.datebook.trains.nameKey
import io.github.adenkushnir.datebook.widgets.updateWidgets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
private const val MATCH_DAYS = 14L  // a rule's preview lists the events it covers in the next two weeks
private const val MAX_MATCHES = 8
private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private val MAPPER = jacksonObjectMapper()
private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// What a preview shows: one line per change, the events or plans the new rules cover, and for a new flight its card
// (route, number, times, airport and this plan's travel; null without one), whose change then has no line of its own
data class Preview(val lines: List<String>, val matches: List<String>, val card: Map<String, Any?>? = null)

// What confirming did: a line per change made, a line per change that couldn't be made, the steps that undo it, and
// the calendar event to open afterwards (0 for none)
data class Applied(val done: List<String>, val failed: List<String>, val undo: List<Map<String, Any?>>,
    val openEvent: Long = 0)

// One undo step
private fun step(op: String, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
    return mapOf("op" to op, *pairs)
}

// Makes, describes and undoes Deliberate's changes
class DeliberateRunner(private val context: Context) {
    private val graph = AppGraph.get(context)
    private val dao = graph.dao
    private val rules = graph.deliberateDao
    private val settings = DeliberateSettings(context)
    private val packages = PackageStore(context)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private var opened = 0L  // the last calendar event a confirm wrote, to open afterwards

    // A plan's day and time in words, like "Sat Nov 21, 12:00 PM" or "Sat Nov 21"
    private fun whenText(date: String, time: String): String {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val clock = runCatching { LocalTime.parse(time) }.getOrNull()
        return if (clock == null) day.format(DAY) else day.atTime(clock).format(DAY_TIME)
    }

    // A calendar event's start in words
    private fun eventWhen(event: CalendarEvent): String {
        // All-day events are kept at midnight UTC
        if (event.allDay) {
            return Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).format(DAY)
        }
        return Instant.ofEpochMilli(event.begin).atZone(zone).format(DAY_TIME)
    }

    // Find a calendar event again by its id and start (null when it's gone)
    private fun findEvent(eventId: Long, begin: Long): CalendarEvent? {
        val switchedOff = graph.calendarSwitches.switchedOff()
        return calendarEvents(context, begin - 60_000, begin + 60_000, switchedOff).firstOrNull { it.eventId == eventId }
    }

    // The upcoming events in the next two weeks
    private fun upcoming(nowMillis: Long): List<CalendarEvent> {
        return calendarEvents(context, nowMillis, nowMillis + MATCH_DAYS * DAY_MILLIS,
            graph.calendarSwitches.switchedOff())
    }

    // The preview: a card for the first new flight, a line for each other change, and for new rules the upcoming
    // events (or waiting plans) they cover
    suspend fun preview(changes: List<Change>, nowMillis: Long = System.currentTimeMillis()): Preview {
        val flightAt = changes.indexOfFirst {
            it.action in setOf(ACT_NEW_PLAN, ACT_EDIT_EVENT) && it.flight.isNotBlank()
        }
        val card = changes.getOrNull(flightAt)?.let { flightCard(it) }
        val lines = changes.filterIndexed { index, _ -> index != flightAt }.map { describe(it) }
        val matches = mutableListOf<String>()

        // List what each new rule covers
        for (change in changes.filter { it.action == ACT_ADD_RULE }) {
            matches.addAll(ruleMatches(ruleOf(change, nowMillis), nowMillis))
        }

        // Keep the list short
        if (matches.size > MAX_MATCHES) {
            val more = matches.size - MAX_MATCHES
            return Preview(lines, matches.take(MAX_MATCHES) + "and $more more", card)
        }
        return Preview(lines, matches, card)
    }

    // Problems only the phone can see: a second plan for a flight that already has one on that day
    suspend fun problems(changes: List<Change>): List<String> {
        val flights = dao.flightSuggestions()
        return changes.filter { it.action == ACT_NEW_PLAN && it.flight.isNotBlank() }.mapNotNull { change ->
            flights.firstOrNull {
                it.flight.equals(change.flight, ignoreCase = true) && it.start.take(10) == change.date
            }
                ?.let { "plan ${it.id} is already flight ${change.flight} on ${change.date}: change it with " +
                    "set_plan_travel instead of making a second plan" }
        }
    }

    // The live details of the flight leg meant (the one landing where to says), remembered or looked up now (null when
    // it isn't found); this waits on the network, so it's only called off the main thread
    private fun flightInfo(change: Change): FlightInfo? {
        return graph.flightStore.info(change.flight, change.date)?.takeIf { legMatches(it, change.to) }
            ?: lookupFlight(graph.flightsKeyStore.load(), change.flight, change.date, change.to)?.also {
                graph.flightStore.save(change.date, it)
            }
    }

    // A new flight's card for the preview: the route big, the number, departure and landing, the airport and terminal,
    // and this plan's travel as chips (what was said when the flight isn't found)
    private fun flightCard(change: Change): Map<String, Any?> {
        val info = flightInfo(change)
        val at = info?.let { zoneOf(it.fromZone) } ?: zone
        val departs = info?.departBest ?: info?.departScheduled
        val lands = info?.arriveBest ?: info?.arriveScheduled
        val route = if (info != null && info.fromCode.isNotBlank() && info.toCode.isNotBlank())
            "${info.fromCode} → ${info.toCode}" else "Flight ${change.flight}"
        val airport = listOfNotNull(info?.fromName?.ifBlank { null } ?: change.place.ifBlank { null },
            info?.terminal?.ifBlank { null }?.let { "Terminal $it" }).joinToString(" · ")
        val heading = if (change.action == ACT_EDIT_EVENT) "Update ${eventName(change)}" else "New flight plan"
        return mapOf("heading" to heading, "route" to route, "number" to change.flight,
            "departs" to "Departs " + (departs?.atZone(at)?.format(DAY_TIME) ?: whenText(change.date, change.time)),
            "lands" to (lands?.let { "Lands ${it.atZone(at).format(CLOCK)}" } ?: ""), "airport" to airport,
            "chips" to travelChips(change, info, true), "found" to (info != null))
    }

    // A plan's own travel as short phrases: "🚆 By train", "Be there 30 min early", "From Princeton Junction"; a
    // flight with no early minutes given shows its default
    private fun travelChips(change: Change, info: FlightInfo?, isFlight: Boolean): List<String> {
        val chips = mutableListOf<String>()
        val early = change.early

        // Name each setting given
        if (change.travel.isNotBlank()) {
            chips.add(travelWords(change.travel))
        }

        if (early != null) {
            chips.add(if (early == 0) "Right on time" else if (isFlight) "Be there ${minutesText(early)} before boarding"
                else "Be there ${minutesText(early)} early")
        } else if (isFlight) {
            chips.add("Be there ${minutesText(flightEarlyMinutes(info))} before boarding")
        }

        if (change.station.isNotBlank()) {
            chips.add("From ${stationFor(change.station)?.name ?: change.station}")
        }
        return chips
    }

    // A travel mode as a chip's words
    private fun travelWords(mode: String): String {
        // Word each mode
        return when (mode) {
            "TRANSIT" -> "🚆 By train"
            "WALK" -> "🚶 Walking"
            "BICYCLE" -> "🚲 By bike"
            else -> "🚗 Driving"
        }
    }

    // A plan's own travel for a line: " · this plan only: 🚆 By train, Be there 30 min early" ("" when none given)
    private fun travelSuffix(change: Change): String {
        val chips = travelChips(change, null, false)
        return if (chips.isEmpty()) "" else " · this plan only: ${chips.joinToString(", ")}"
    }

    // The NJ Transit station a plan leaves from, by its code or name (null when none matches)
    private fun stationFor(name: String): Station? {
        // No station named
        if (name.isBlank()) {
            return null
        }
        val stations = graph.trainSettings.stations()
        val wanted = nameKey(name)
        return stations.firstOrNull { it.code.equals(name.trim(), ignoreCase = true) || nameKey(it.name) == wanted }
            ?: stations.firstOrNull { nameKey(it.name).contains(wanted) }
    }

    // The key a plan's travel settings and train are kept under
    private fun planKey(plan: Suggestion): String {
        return eventKey(plan.title, eventTimes(plan.toEvent()).first)
    }

    // Set a plan's own travel (mode, early minutes, station) under its key, returning the step that puts the old ones
    // back (none when nothing was given)
    private fun setTravel(key: String, change: Change): List<Map<String, Any?>> {
        val leaveBy = LeaveByStore(context)
        val trains = graph.trainStore
        val station = stationFor(change.station)

        // Nothing to set
        if (change.travel.isBlank() && change.early == null && station == null) {
            return emptyList()
        }
        val undo = step("plan_travel", "key" to key, "mode" to leaveBy.mode(key), "early" to leaveBy.early(key),
            "origin" to trains.origin(key))

        // Set each one given
        if (change.travel.isNotBlank()) {
            leaveBy.setMode(key, change.travel)
        }

        if (change.early != null) {
            leaveBy.setEarly(key, change.early)
        }

        if (station != null) {
            trains.setOrigin(key, station.code)
        }
        return listOf(undo)
    }

    // What a new rule covers: for ignore rules the plans waiting now, for the rest the next two weeks of events with
    // what changes for each
    private suspend fun ruleMatches(rule: Rule, nowMillis: Long): List<String> {
        // Ignore rules cover plans, not events
        if (rule.kind == RULE_IGNORE) {
            return dao.suggestionsWithStatus(Suggestion.STATUS_WAITING)
                .filter { coversPlan(rule, it.sender, it.conversation, it.title, "event") }
                .map { "Waiting now · ${it.title} → ignored" }
        }

        // Habits change how new texts are read, not events
        if (rule.kind == RULE_HABIT) {
            return emptyList()
        }
        return upcoming(nowMillis).filter { !it.allDay || rule.kind == RULE_BRING || rule.kind == RULE_QUIET }
            .filter { coversEvent(rule, it.title, it.location) }.map { event ->
                val minutes = rule.value.toIntOrNull()
                val effect = when {
                    rule.kind == RULE_TRAVEL && minutes != null ->
                        " · leave by ${Instant.ofEpochMilli(leaveTime(event.begin, minutes)).atZone(zone).format(CLOCK)}"
                    else -> ""
                }
                "${eventWhen(event)} · ${event.title.ifBlank { "Event" }}$effect"
            }
    }

    // One change in words, for the preview
    private suspend fun describe(change: Change): String {
        // Word each action
        return when (change.action) {
            ACT_NEW_PLAN -> if (change.flight.isNotBlank()) flightLine(change)
                else "New plan: ${change.title} · ${whenText(change.date, change.time)}" +
                    (if (change.place.isNotBlank()) " · ${change.place}" else "") + travelSuffix(change) + addedText()
            ACT_SET_PLAN_TRAVEL -> {
                val name = if (change.target == TARGET_EVENT) eventName(change) else planName(change.id)
                "This plan only, $name: ${travelChips(change, null, false).joinToString(", ")}"
            }
            ACT_EDIT_WAITING -> "Change the waiting plan ${planName(change.id)}: ${fieldsText(change)}"
            ACT_ADD_WAITING -> "Add ${planName(change.id)}" +
                if (canWriteCalendar(context)) " → saved to your calendar" else " → opens your calendar app to save it"
            ACT_IGNORE_WAITING -> "Ignore ${planName(change.id)}"
            ACT_MOVE_EVENT -> "Move ${eventName(change)} to ${whenText(change.date, change.time)}" +
                if (canWriteCalendar(context)) " → changed on your calendar"
                else " → a prompt that opens the event in your calendar app"
            ACT_EDIT_EVENT -> "Change ${eventName(change)}: " + (if (change.flight.isNotBlank())
                "flight ${change.flight} from live data" else fieldsText(change)) + travelSuffix(change) +
                " → changed on your calendar"
            ACT_CANCEL_EVENT -> "${eventName(change)} is cancelled → a prompt that opens the event in your calendar app"
            ACT_ADD_BILL -> "New bill: ${change.title}" +
                (if (change.amount.isNotBlank()) " · ${change.amount}" else "") +
                " · due ${whenText(change.date, "")}" + repeatText(change.repeat) +
                " · reminders 2 days before and on the day"
            ACT_ADD_ASSIGNMENT -> "New assignment: ${change.title} · due ${whenText(change.date, change.time)}"
            ACT_ADD_REMINDER -> "New reminder: ${change.title}" +
                (if (isDate(change.date)) " · ${whenText(change.date, change.time)}" else "") + repeatText(change.repeat)
            ACT_ADD_NOTE -> "New note: ${noteTitle(change.text.ifBlank { change.title })}"
            ACT_ADD_PACKAGE -> "New package: ${packageTitle(change)} · ${change.trackingNumber}"
            ACT_EDIT_ITEM -> "Change ${itemName(change.id)}: ${fieldsText(change)}"
            ACT_MARK_DONE -> "Done: ${itemName(change.id)}"
            ACT_DELETE_ITEM -> "Delete ${itemName(change.id)}"
            ACT_PACKAGE_RECEIVED -> "Got it: ${planName(change.id)}"
            ACT_ADD_RULE -> "New rule: ${ruleText(ruleOf(change, 0))}"
            ACT_TURN_RULE -> "Turn ${if (change.value == "off") "off" else "on"} the rule ${ruleName(change.id)}"
            ACT_DELETE_RULE -> "Delete the rule ${ruleName(change.id)}"
            ACT_PAUSE -> "${pauseName(change.pause)} paused until " +
                Instant.ofEpochMilli(untilMillis(change.until, zone) ?: 0).atZone(zone).format(DAY_TIME)
            ACT_SETTING -> settingText(change)
            ACT_SET_LINK -> {
                val name = if (change.target == TARGET_PLAN) planName(change.id) else itemName(change.id)
                if (change.link.isBlank()) "Remove the link from $name" else "Link for $name: ${change.link}"
            }
            else -> change.action
        }
    }

    // A new flight plan in words, with its live title and time when found
    private fun flightLine(change: Change): String {
        val info = flightInfo(change)
        val departs = (info?.departBest ?: info?.departScheduled)?.let { time ->
            time.atZone(zoneOf(info?.fromZone) ?: zone).format(DAY_TIME)
        } ?: whenText(change.date, change.time)
        val place = info?.fromName?.ifBlank { null } ?: change.place.ifBlank { null }
        return "New flight plan: ${info?.let { flightTitle(it) } ?: "Flight ${change.flight}"} · $departs" +
            (place?.let { " · $it" } ?: "") + travelSuffix(change) + addedText()
    }

    // Where a new plan goes: straight onto your calendar, or (without calendar write access) an Add prompt
    private fun addedText(): String {
        return if (canWriteCalendar(context)) " → saved to your calendar" else " → an Add to calendar? prompt"
    }

    // ", repeats monthly" style words for a repeat rule ("" for none)
    private fun repeatText(rule: String): String {
        val label = repeatLabel(rule)
        return if (label.isBlank()) "" else " · ${label.lowercase()}"
    }

    // The fields an edit changes, in words
    private fun fieldsText(change: Change): String {
        val parts = mutableListOf<String>()

        // Name each field given
        if (change.title.isNotBlank()) {
            parts.add("title “${change.title}”")
        }

        if (isDate(change.date) || isTime(change.time)) {
            parts.add("to ${whenText(change.date.ifBlank { LocalDate.now(zone).toString() }, change.time)}")
        }

        if (change.place.isNotBlank()) {
            parts.add("at ${change.place}")
        }

        if (change.amount.isNotBlank()) {
            parts.add("amount ${change.amount}")
        }

        if (change.text.isNotBlank()) {
            parts.add("text “${change.text.take(60)}”")
        }
        return parts.joinToString(", ").ifBlank { "no changes" }
    }

    // A package's title: the one given, or "UPS delivery"
    private fun packageTitle(change: Change): String {
        return change.title.ifBlank { "${change.carrier.uppercase().ifBlank { "Package" }} delivery" }
    }

    // A setting change in words
    private fun settingText(change: Change): String {
        // Word each setting
        return when (change.setting) {
            SET_SUMMARY_TIME -> "Morning summary at ${runCatching { LocalTime.parse(change.value).format(CLOCK) }
                .getOrDefault(change.value)}"
            SET_SUMMARY_ON -> "Morning summary ${if (onValue(change.value)) "on" else "off"}"
            SET_HOME -> "Home address: ${change.value}"
            SET_TRAVEL_MODE -> "Default travel mode: ${travelMode(change.value.uppercase()).name}"
            SET_FLIP_TRIP -> "Flip the saved train trip"
            SET_KEEP_TEXTS -> "Keep message texts ${if (onValue(change.value)) "on" else "off"}"
            else -> change.setting
        }
    }

    // Return true if a value means on ("on", "true", "yes")
    private fun onValue(value: String): Boolean {
        return value.lowercase() in setOf("on", "true", "yes", "1")
    }

    // A plan's title by id, in quotes
    private suspend fun planName(id: Long): String {
        return dao.suggestion(id)?.let { "“${it.title}”" } ?: "plan $id"
    }

    // A list item's title by id, in quotes
    private suspend fun itemName(id: Long): String {
        return dao.followUp(id)?.let { "“${it.title}”" } ?: "item $id"
    }

    // A rule by id, in words
    private suspend fun ruleName(id: Long): String {
        return rules.rule(id)?.let { "“${ruleText(it)}”" } ?: "rule $id"
    }

    // A calendar event by id and start, in words
    private fun eventName(change: Change): String {
        val event = findEvent(change.id, change.begin) ?: return "the event"
        return "${event.title.ifBlank { "Event" }} (${eventWhen(event)})"
    }

    // Make every change, in order, noting how to undo each; a change that can't be made is skipped and said so
    suspend fun apply(changes: List<Change>, nowMillis: Long = System.currentTimeMillis()): Applied {
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val undo = mutableListOf<Map<String, Any?>>()
        opened = 0

        // Make each change
        for (change in changes) {
            // A change that fails doesn't stop the others
            try {
                val steps = make(change, nowMillis)

                // Nothing to undo means it couldn't be made
                if (steps == null) {
                    failed.add(describe(change))
                } else {
                    undo.addAll(steps)
                    done.add(describe(change))
                }
            } catch (error: Exception) {  // a missing row, an unreadable date, a calendar that went away
                failed.add("${describe(change)} (${error.message})")
            }
        }
        afterChanges(changes)
        return Applied(done, failed, undo, opened)
    }

    // Book what depends on the changes: reminders, the summary, leave-by alerts and the widgets
    private suspend fun afterChanges(changes: List<Change>) {
        bookNextFollowUp(context)

        // A new summary time needs a new booking
        if (changes.any { it.action == ACT_SETTING && it.setting.startsWith("summary") }) {
            scheduleSummary(context, ExistingWorkPolicy.REPLACE)
        }
        planLeaveBy(context)
        updateWidgets(context)
    }

    // A plan made by Deliberate, waiting for Add
    private fun deliberatePlan(title: String, start: String, end: String, place: String, nowMillis: Long,
        allDay: Boolean): Suggestion {
        return Suggestion(sender = "you", app = DELIBERATE_APP, title = title, start = start, end = end,
            allDay = allDay, location = place, repeat = "", timezone = "", phoneTimezone = zone.id,
            createdAt = nowMillis)
    }

    // A follow-up made by Deliberate
    private fun deliberateItem(kind: String, change: Change, nowMillis: Long, nextNudge: Long): FollowUp {
        return FollowUp(kind = kind, title = change.title, date = change.date, amount = change.amount, sender = "you",
            app = DELIBERATE_APP, conversation = "", status = FollowUp.OPEN, createdAt = nowMillis,
            nextNudge = nextNudge, nudges = 0, time = if (isTime(change.time)) change.time else "",
            repeat = change.repeat)
    }

    // The end of a moved plan: the end given, else the same length as before
    private fun movedEnd(change: Change, start: String, oldStart: String, oldEnd: String): String {
        // An end given wins
        if (isDate(change.endDate) || isTime(change.endTime)) {
            return planTime(change.endDate.ifBlank { start.take(10) }, change.endTime)
        }

        // Keep the length when both old times are known and the new start has a time
        if (oldEnd.length >= 16 && oldStart.length >= 16 && start.length >= 16) {
            val length = Duration.between(parseLocal(oldStart), parseLocal(oldEnd))
            return parseLocal(start).plus(length).toString().take(16)
        }
        return ""
    }

    // Make one change and return the steps that undo it, or null when it can't be made
    private suspend fun make(change: Change, nowMillis: Long): List<Map<String, Any?>>? {
        // Do what the action says
        return when (change.action) {
            ACT_NEW_PLAN -> {
                val start = planTime(change.date, change.time)
                val end = if (isDate(change.endDate) || isTime(change.endTime))
                    planTime(change.endDate.ifBlank { change.date }, change.endTime) else ""
                val title = if (change.flight.isNotBlank()) "Flight ${change.flight}" else change.title
                val written = Event(title, start, end, !isTime(change.time), change.place, phoneTimezone = zone.id,
                    flight = change.flight)
                val info = if (change.flight.isNotBlank()) flightInfo(change) else null
                val event = if (info != null) withFlight(written, info) else written

                // A flight not found yet keeps which leg it is, for when it shows up
                if (change.flight.isNotBlank() && info == null && change.to.isNotBlank()) {
                    graph.flightStore.setLeg(change.flight, change.date, change.to)
                }
                val found = if (event.location.isBlank()) null else findPlace(context, graph.mapsKeyStore.load(),
                    graph.travelSettings.home(), event.location)  // its spot on the map, for trains
                val plan = deliberatePlan(event.title, event.start, event.end, found?.line ?: event.location, nowMillis,
                    event.allDay).copy(repeat = change.repeat, flight = change.flight, timezone = event.timezone,
                    placeLat = found?.lat, placeLng = found?.lng)
                val id = dao.saveSuggestion(plan)
                val steps = mutableListOf(step("delete_plan", "id" to id))

                // You confirmed it, so it goes straight onto the calendar (else it waits as an Add prompt)
                if (canWriteCalendar(context)) {
                    val eventId = addNow(context, id) ?: 0
                    opened = maxOf(opened, eventId)
                    steps.add(step("delete_event", "id" to eventId))
                } else {
                    graph.notifier.show(plan.copy(id = id), plan.title)
                }
                steps + setTravel(planKey(plan), change)
            }
            ACT_SET_PLAN_TRAVEL -> {
                val key = if (change.target == TARGET_EVENT) {
                    findEvent(change.id, change.begin)?.let { eventKey(it.title, it.begin) }
                } else {
                    dao.suggestion(change.id)?.let { planKey(it) }
                }
                setTravel(key ?: return null, change).ifEmpty { null }
            }
            ACT_EDIT_WAITING -> {
                val old = dao.suggestion(change.id)?.takeIf { it.status == Suggestion.STATUS_WAITING } ?: return null
                val start = when {
                    isDate(change.date) -> planTime(change.date, change.time.ifBlank { old.start.drop(11) })
                    isTime(change.time) -> planTime(old.start.take(10), change.time)
                    else -> old.start
                }
                val end = if (start == old.start && !isDate(change.endDate) && !isTime(change.endTime)) old.end
                    else movedEnd(change, start, old.start, old.end)
                dao.updateSuggestion(old.copy(title = change.title.ifBlank { old.title }, start = start, end = end,
                    allDay = start.length == 10, location = change.place.ifBlank { old.location }))
                listOf(step("put_plan", "row" to MAPPER.writeValueAsString(old)))
            }
            ACT_ADD_WAITING -> {
                val old = dao.suggestion(change.id) ?: return null
                addSuggestion(context, change.id)
                listOf(step("put_plan", "row" to MAPPER.writeValueAsString(old)))
            }
            ACT_IGNORE_WAITING -> {
                val old = dao.suggestion(change.id) ?: return null
                ignoreSuggestion(context, change.id)
                listOf(step("put_plan", "row" to MAPPER.writeValueAsString(old)))
            }
            ACT_EDIT_EVENT -> editEvent(change, nowMillis)
            ACT_MOVE_EVENT, ACT_CANCEL_EVENT -> {
                // A move is made on the calendar itself when Datebook may write to it
                if (change.action == ACT_MOVE_EVENT && canWriteCalendar(context)) {
                    return editEvent(change, nowMillis)
                }
                val event = findEvent(change.id, change.begin) ?: return null
                val oldStart = localTime(event.begin, event.allDay)
                val oldEnd = localTime(event.end, event.allDay)
                val moving = change.action == ACT_MOVE_EVENT
                val start = if (moving) planTime(change.date, change.time.ifBlank { oldStart.drop(11) }) else oldStart
                val end = if (moving) movedEnd(change, start, oldStart, oldEnd) else oldEnd
                val plan = deliberatePlan(event.title, start, end, event.location, nowMillis, start.length == 10)
                    .copy(change = if (moving) CHANGE_MOVED else CHANGE_CANCELLED, calendarEventId = event.eventId)
                val id = dao.saveSuggestion(plan)
                graph.notifier.show(plan.copy(id = id), promptTitle(plan))
                listOf(step("delete_plan", "id" to id))
            }
            ACT_ADD_BILL -> {
                val bill = deliberateItem(KIND_DUE, change, nowMillis, firstNudge(KIND_DUE, change.date, nowMillis,
                    zone))
                listOf(step("delete_item", "id" to dao.saveFollowUp(bill)))
            }
            ACT_ADD_ASSIGNMENT -> {
                val assignment = deliberateItem(KIND_ASSIGNMENT, change, nowMillis,
                    firstNudge(KIND_ASSIGNMENT, change.date, nowMillis, zone))
                listOf(step("delete_item", "id" to dao.saveFollowUp(assignment)))
            }
            ACT_ADD_REMINDER -> {
                val first = firstReminder(change.date, change.time, change.repeat, nowMillis, zone)
                val reminder = deliberateItem(KIND_REMINDER, change, nowMillis, first)
                    .copy(date = if (isDate(change.date)) change.date else "")
                listOf(step("delete_item", "id" to dao.saveFollowUp(reminder)))
            }
            ACT_ADD_NOTE -> {
                val text = change.text.ifBlank { change.title }
                val note = deliberateItem(KIND_NOTE, change, nowMillis, 0).copy(title = noteTitle(text), body = text,
                    date = "", repeat = "")
                listOf(step("delete_item", "id" to dao.saveFollowUp(note)))
            }
            ACT_ADD_PACKAGE -> {
                val day = if (isDate(change.date)) change.date else LocalDate.now(zone).toString()
                val plan = deliberatePlan(packageTitle(change), day, "", "", nowMillis, true)
                    .copy(packageCarrier = change.carrier, trackingNumber = change.trackingNumber)
                listOf(step("delete_plan", "id" to dao.saveSuggestion(plan)))
            }
            ACT_EDIT_ITEM -> editItem(change, nowMillis)
            ACT_MARK_DONE -> {
                val old = dao.followUp(change.id) ?: return null
                dao.setFollowUpStatus(old.id, FollowUp.DONE)
                val steps = mutableListOf(step("put_item", "row" to MAPPER.writeValueAsString(old)))
                nextBill(old, nowMillis, zone)?.let { steps.add(step("delete_item", "id" to dao.saveFollowUp(it))) }
                steps
            }
            ACT_DELETE_ITEM -> {
                val old = dao.followUp(change.id) ?: return null
                dao.setFollowUpStatus(old.id, FollowUp.DROPPED)
                listOf(step("put_item", "row" to MAPPER.writeValueAsString(old)))
            }
            ACT_PACKAGE_RECEIVED -> {
                dao.suggestion(change.id) ?: return null
                packages.markReceived(change.id)
                listOf(step("unreceive", "id" to change.id))
            }
            ACT_ADD_RULE -> addRule(change, nowMillis)
            ACT_TURN_RULE -> {
                val old = rules.rule(change.id) ?: return null
                rules.updateRule(old.copy(isOn = change.value != "off"))
                listOf(step("put_rule", "row" to MAPPER.writeValueAsString(old)))
            }
            ACT_DELETE_RULE -> {
                val old = rules.rule(change.id) ?: return null
                rules.deleteRule(old.id)
                listOf(step("put_rule", "row" to MAPPER.writeValueAsString(old)))
            }
            ACT_PAUSE -> {
                val until = untilMillis(change.until, zone) ?: return null
                val old = settings.pausedUntil(change.pause)
                settings.setPausedUntil(change.pause, until)
                listOf(step("pause", "what" to change.pause, "until" to old))
            }
            ACT_SETTING -> changeSetting(change.setting, change.value)
            ACT_SET_LINK -> if (change.target == TARGET_PLAN) {
                val old = dao.suggestion(change.id) ?: return null
                dao.updateSuggestion(old.copy(link = change.link))
                listOf(step("put_plan", "row" to MAPPER.writeValueAsString(old)))
            } else {
                val old = dao.followUp(change.id) ?: return null
                dao.updateFollowUp(old.copy(link = change.link))
                listOf(step("put_item", "row" to MAPPER.writeValueAsString(old)))
            }
            else -> null
        }
    }

    // Change an event on the calendar: its title, day and time, place, notes added at the end, or with a flight number
    // the flight's live title, times and airport (which also makes a plan for the flight, so it's tracked and gets its
    // trains); never repeating events, whose whole series would move. Returns the steps that put it back, or null when
    // it can't be changed
    private suspend fun editEvent(change: Change, nowMillis: Long): List<Map<String, Any?>>? {
        val event = findEvent(change.id, change.begin) ?: return null
        val old = readEvent(context, event.eventId)?.takeIf { it.repeat.isBlank() } ?: return null
        val oldStart = localTime(event.begin, old.allDay)
        val oldEnd = localTime(event.end, old.allDay)
        val retimed = isDate(change.date) || isTime(change.time)
        val start = if (retimed) planTime(change.date.ifBlank { oldStart.take(10) },
            change.time.ifBlank { oldStart.drop(11) }) else oldStart
        val end = if (!retimed && !isDate(change.endDate) && !isTime(change.endTime)) oldEnd
            else movedEnd(change, start, oldStart, oldEnd)
        val written = Event(change.title.ifBlank { old.title }, start, end, start.length == 10,
            change.place.ifBlank { old.location }, phoneTimezone = zone.id, flight = change.flight)
        val info = if (change.flight.isNotBlank()) flightInfo(change) else null
        val filled = if (info != null) withFlight(written, info) else written
        val plan = deliberatePlan(filled.title, filled.start, filled.end, filled.location, nowMillis, filled.allDay)
            .copy(timezone = filled.timezone, flight = change.flight, status = Suggestion.STATUS_ADDED,
                calendarEventId = event.eventId)
        val times = planFields(plan)
        val notes = listOf(old.notes, change.text).filter { it.isNotBlank() }.joinToString("\n")
        val new = old.copy(title = times.title, begin = times.begin, end = times.end, allDay = times.allDay,
            location = times.location, timezone = times.timezone, notes = notes)

        // The calendar can refuse
        if (!updateEvent(context, event.eventId, new)) {
            return null
        }
        opened = maxOf(opened, event.eventId)
        val steps = mutableListOf(step("put_event", "id" to event.eventId, "row" to MAPPER.writeValueAsString(old)))

        // A flight gets a plan of its own, with its map spot for trains
        if (change.flight.isNotBlank()) {
            val found = if (new.location.isBlank()) null else findPlace(context, graph.mapsKeyStore.load(),
                graph.travelSettings.home(), new.location)
            steps.add(step("delete_plan", "id" to dao.saveSuggestion(plan.copy(placeLat = found?.lat,
                placeLng = found?.lng))))
        }
        return steps + setTravel(eventKey(new.title, new.begin), change)
    }

    // A calendar event time as Datebook keeps plan times ("YYYY-MM-DDTHH:MM", or the date for all-day events)
    private fun localTime(millis: Long, allDay: Boolean): String {
        // All-day events are kept at midnight UTC
        if (allDay) {
            return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
        }
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().toString().take(16)
    }

    // Change a bill, assignment, reminder or note, booking its next reminder again when its day or time moved
    private suspend fun editItem(change: Change, nowMillis: Long): List<Map<String, Any?>>? {
        val old = dao.followUp(change.id) ?: return null
        val date = if (isDate(change.date)) change.date else old.date
        val time = if (isTime(change.time)) change.time else old.time
        val repeat = change.repeat.ifBlank { old.repeat }
        val moved = date != old.date || time != old.time || repeat != old.repeat
        val nextNudge = when {
            !moved -> old.nextNudge
            old.kind == KIND_REMINDER -> firstReminder(date, time, repeat, nowMillis, zone)
            else -> firstNudge(old.kind, date, nowMillis, zone)
        }
        val body = if (old.kind == KIND_NOTE && change.text.isNotBlank()) change.text else old.body
        val title = when {
            old.kind == KIND_NOTE && change.text.isNotBlank() -> noteTitle(change.text)
            else -> change.title.ifBlank { old.title }
        }
        dao.updateFollowUp(old.copy(title = title, date = date, time = time, repeat = repeat, body = body,
            amount = change.amount.ifBlank { old.amount }, nextNudge = nextNudge))
        return listOf(step("put_item", "row" to MAPPER.writeValueAsString(old)))
    }

    // Save a new rule; an ignore rule also ignores the plans it covers that are waiting now
    private suspend fun addRule(change: Change, nowMillis: Long): List<Map<String, Any?>> {
        val rule = ruleOf(change, nowMillis)
        val steps = mutableListOf(step("delete_rule", "id" to rules.saveRule(rule)))

        // Ignore what's already waiting from it
        if (rule.kind == RULE_IGNORE) {
            // Ignore each waiting plan the rule covers
            for (plan in dao.suggestionsWithStatus(Suggestion.STATUS_WAITING)) {
                // Only the plans it covers
                if (coversPlan(rule, plan.sender, plan.conversation, plan.title, "event")) {
                    steps.add(step("put_plan", "row" to MAPPER.writeValueAsString(plan)))
                    ignoreSuggestion(context, plan.id)
                }
            }
        }
        return steps
    }

    // Change a setting and return the step that puts the old value back
    private fun changeSetting(setting: String, value: String): List<Map<String, Any?>>? {
        val summary = graph.summarySettings
        val travel = graph.travelSettings

        // Change the one named, remembering its old value
        return when (setting) {
            SET_SUMMARY_TIME -> {
                val time = runCatching { LocalTime.parse(value) }.getOrNull() ?: return null
                val old = LocalTime.of(summary.hour(), summary.minute()).toString()
                summary.set(summary.isOn(), time.hour, time.minute)
                listOf(step("setting", "setting" to setting, "value" to old))
            }
            SET_SUMMARY_ON -> {
                val old = summary.isOn().toString()
                summary.set(onValue(value), summary.hour(), summary.minute())
                listOf(step("setting", "setting" to setting, "value" to old))
            }
            SET_HOME -> {
                val old = travel.home()
                travel.setHome(value)
                listOf(step("setting", "setting" to setting, "value" to old))
            }
            SET_TRAVEL_MODE -> {
                val old = travel.mode()
                travel.setMode(value.uppercase())
                listOf(step("setting", "setting" to setting, "value" to old))
            }
            SET_FLIP_TRIP -> {
                val trip = graph.trainSettings.trip() ?: return null
                graph.trainSettings.setTrip(trip.second, trip.first)
                listOf(step("setting", "setting" to setting, "value" to ""))
            }
            SET_KEEP_TEXTS -> {
                val old = settings.keepTexts().toString()
                settings.setKeepTexts(onValue(value))
                listOf(step("setting", "setting" to setting, "value" to old))
            }
            else -> null
        }
    }

    // Undo a change: run its steps backwards, so the last thing done is put back first
    suspend fun undo(steps: List<Map<String, Any?>>) {
        // Put back each step
        for (undoStep in steps.reversed()) {
            val id = (undoStep["id"] as? Number)?.toLong() ?: 0
            val row = undoStep["row"]?.toString().orEmpty()

            // Each kind of step has its own way back
            when (undoStep["op"]) {
                "delete_plan" -> {
                    graph.notifier.cancel(id)
                    dao.deleteSuggestion(id)
                }
                "delete_event" -> if (id > 0) deleteEvent(context, id)
                "put_event" -> updateEvent(context, id, MAPPER.readValue<EventFields>(row))
                "put_plan" -> dao.updateSuggestion(MAPPER.readValue<Suggestion>(row))
                "delete_item" -> dao.setFollowUpStatus(id, FollowUp.DROPPED)
                "put_item" -> dao.updateFollowUp(MAPPER.readValue<FollowUp>(row))
                "unreceive" -> packages.unmarkReceived(id)
                "delete_rule" -> rules.deleteRule(id)
                "put_rule" -> {
                    val rule = MAPPER.readValue<Rule>(row)

                    // Put a deleted rule back, or the old version over a changed one
                    if (rules.rule(rule.id) == null) {
                        rules.saveRule(rule)
                    } else {
                        rules.updateRule(rule)
                    }
                }
                "pause" -> settings.setPausedUntil(undoStep["what"].toString(),
                    (undoStep["until"] as? Number)?.toLong() ?: 0)
                "setting" -> changeSetting(undoStep["setting"].toString(), undoStep["value"]?.toString().orEmpty())
                "plan_travel" -> {
                    val key = undoStep["key"].toString()
                    LeaveByStore(context).putMode(key, undoStep["mode"]?.toString())
                    LeaveByStore(context).setEarly(key, (undoStep["early"] as? Number)?.toInt())
                    graph.trainStore.putOrigin(key, undoStep["origin"]?.toString())
                }
            }
        }
        bookNextFollowUp(context)
        scheduleSummary(context, ExistingWorkPolicy.REPLACE)
        planLeaveBy(context)
        updateWidgets(context)
    }
}

// One plan in words for a look-up, like "Lunch with Sam · Sat Nov 21, 12:00 PM · Luigi's"
fun planSummary(plan: Suggestion): String {
    return runCatching { summary(plan.toEvent()) }.getOrDefault(plan.title)
}
