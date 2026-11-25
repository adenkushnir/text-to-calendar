// Answers Claude's look-ups in Deliberate with short plain lines: calendar events (with what the rules change), plans
// and their messages, lists, settings and rules, the weather and trains, and live flights
package io.github.adenkushnir.datebook.deliberate
import android.content.Context
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.flights.boardingMinutes
import io.github.adenkushnir.datebook.flights.flightEarlyMinutes
import io.github.adenkushnir.datebook.flights.isInternational
import io.github.adenkushnir.datebook.flights.FlightInfo
import io.github.adenkushnir.datebook.journeys.Journey
import io.github.adenkushnir.datebook.journeys.planJourney
import io.github.adenkushnir.datebook.flights.lookupFlights
import io.github.adenkushnir.datebook.flights.statusLine
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.pipeline.zoneOf
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_LOOSE
import io.github.adenkushnir.datebook.data.KIND_NOTE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.reminderWhen
import io.github.adenkushnir.datebook.fencing.FencingSettings
import io.github.adenkushnir.datebook.data.repeatLabel
import io.github.adenkushnir.datebook.lists.PackageStore
import io.github.adenkushnir.datebook.places.travelMode
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.trains.nextTrains
import io.github.adenkushnir.datebook.weather.homeForecast
import io.github.adenkushnir.datebook.weather.usesFahrenheit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
private const val MAX_EVENTS = 80
private const val MAX_PLANS = 40
private const val MAX_MESSAGE = 600
private const val MAX_DAYS = 400L
private val DAY = DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.US)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE MMM d, yyyy, h:mm a", Locale.US)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// Return true if any of the words (split by spaces) is in one of the texts, or there are no words
fun hasWords(words: String, vararg texts: String): Boolean {
    val wanted = words.lowercase().split(" ").map { it.trim() }.filter { it.length > 1 }
    return wanted.isEmpty() || wanted.any { word -> texts.any { it.lowercase().contains(word) } }
}

// Runs Claude's look-ups on the phone's data
class DeliberateReader(private val context: Context) {
    private val graph = AppGraph.get(context)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // Run one look-up by the tool's name and return its result as text
    suspend fun read(tool: String, input: Map<String, Any?>): String {
        val words = input["words"]?.toString().orEmpty()

        // Pick the look-up
        return when (tool) {
            TOOL_FIND_EVENTS -> findEvents(input["from"]?.toString().orEmpty(), input["to"]?.toString().orEmpty(), words)
            TOOL_FIND_PLANS -> findPlans(input["status"]?.toString().orEmpty(), words)
            TOOL_FIND_ITEMS -> findItems(input["kind"]?.toString().orEmpty(), words)
            TOOL_SETTINGS -> settings()
            TOOL_EXTRAS -> extras()
            TOOL_TOURNAMENTS -> tournaments()
            TOOL_FIND_FLIGHT -> findFlight(input["flight"]?.toString().orEmpty(), input["date"]?.toString().orEmpty())
            else -> "Unknown tool $tool."
        }
    }

    // A live flight, a line per leg it flies that day: number, status, both airports and countries, times in the
    // departure airport's zone, terminal and gate, and how early Datebook aims to get you there; or why it wasn't
    // found. A single leg is remembered, so a plan made from it needs no second look-up
    private fun findFlight(flight: String, date: String): String {
        val number = flight.uppercase().replace(" ", "")
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return "date must be YYYY-MM-DD."

        // Without a flight data key nothing can be looked up
        if (graph.flightsKeyStore.load().isNullOrBlank()) {
            return "Not found: there's no flight data key (Settings → Flights & trains), so ask the user for the " +
                "time and airport."
        }
        val legs = lookupFlights(graph.flightsKeyStore.load(), number, day.toString())

        // Say so when it isn't found
        if (legs.isEmpty()) {
            return "Not found: no live data for $number on $day (a wrong number, too far ahead, or not running that " +
                "day). Ask the user for the departure time and airport."
        }

        // One leg is remembered; several are listed for the user to pick from
        if (legs.size == 1) {
            graph.flightStore.save(day.toString(), legs.single())
            return legLine(legs.single())
        }
        return "$number flies ${legs.size} legs on $day. Unless the user said where they're going, ask which " +
            "(ask_choice), then give its arrival code as new_plan's to:\n" +
            legs.mapIndexed { index, leg -> "Leg ${index + 1}: ${legLine(leg)}" }.joinToString("\n")
    }

    // One leg of a flight in a line
    private fun legLine(info: FlightInfo): String {
        val at = zoneOf(info.fromZone) ?: zone
        val departs = (info.departBest ?: info.departScheduled)?.atZone(at)?.format(DAY_TIME) ?: "time unknown"
        val lands = (info.arriveBest ?: info.arriveScheduled)?.atZone(at)?.format(CLOCK) ?: "unknown"
        val abroad = if (isInternational(info)) "international" else "domestic"
        return "${info.number} · ${statusLine(info)} · from ${info.fromName} (${info.fromCode}, " +
            "${info.fromCountry}) " +
            "to ${info.toCity} (${info.toCode}, ${info.toCountry}) · departs $departs (${at.id}) · lands $lands · " +
            "$abroad: boarding about ${boardingMinutes(info)} min before departure, and Datebook aims to get the user " +
            "there ${flightEarlyMinutes(info)} min before boarding unless they say (early counts from boarding)"
    }

    // Plan a journey for find_journey: the journey (null when there's no route), what it's for, and a short version
    // for Claude
    fun journey(input: Map<String, Any?>): Triple<Journey?, String, String> {
        val place = input["to"]?.toString().orEmpty().trim()
        val mode = input["mode"]?.toString().orEmpty().ifBlank { "TRANSIT" }
        val only = input["only"]?.toString().orEmpty().takeIf { it == "TRAIN" || it == "BUS" }
        val at = { name: String -> runCatching { java.time.LocalDateTime.parse(input[name]?.toString().orEmpty()) }
            .getOrNull()?.atZone(zone)?.toInstant()?.toEpochMilli() }
        val title = input["title"]?.toString().orEmpty().ifBlank { place.substringBefore(",") }
        val journey = planJourney(context, graph.mapsKeyStore.load(), graph.travelSettings.home(), place,
            place.substringBefore(","), mode, at("leave_at"), at("arrive_by"), listOfNotNull(only))

        // No route (no Maps key, no internet, or nowhere Google knows)
        if (journey == null) {
            return Triple(null, title, "No journey found to $place (no route, no internet, or no Google Maps key).")
        }
        val clock = { millis: Long -> Instant.ofEpochMilli(millis).atZone(zone).format(CLOCK) }
        val steps = journey.steps.joinToString("; ") { "${clock(it.starts)} ${it.title} (${it.under})" }
        return Triple(journey, title, "Shown to the user as a card. ${journey.minutes} min: $steps")
    }

    // A plan's or event's own travel settings in words, like "this plan: TRANSIT, 30 min early" ("" when none)
    private fun ownTravel(title: String, begin: Long): String {
        val store = LeaveByStore(context)
        val key = eventKey(title, begin)
        val parts = listOfNotNull(store.mode(key), store.early(key)?.let { "$it min early" },
            graph.trainStore.origin(key)?.let { "leaves from station $it" })
        return if (parts.isEmpty()) "" else "this plan: ${parts.joinToString(", ")}"
    }

    // An event's time in words: "Tue Nov 17, 2026, 6:00 PM–8:00 PM", or the day for all-day events
    private fun eventTime(event: CalendarEvent): String {
        // All-day events are kept at midnight UTC
        if (event.allDay) {
            return Instant.ofEpochMilli(event.begin).atZone(ZoneOffset.UTC).format(DAY) + " (all day)"
        }
        val begin = Instant.ofEpochMilli(event.begin).atZone(zone)
        val end = Instant.ofEpochMilli(event.end).atZone(zone)
        return "${begin.format(DAY_TIME)}–${end.format(CLOCK)}"
    }

    // The calendar events between two days with the words, each with its id, begin and what the rules change
    private suspend fun findEvents(from: String, to: String, words: String): String {
        val first = runCatching { LocalDate.parse(from) }.getOrNull() ?: return "from must be YYYY-MM-DD."
        val last = runCatching { LocalDate.parse(to) }.getOrNull()?.takeIf { !it.isBefore(first) } ?: first
        val until = if (last.isAfter(first.plusDays(MAX_DAYS))) first.plusDays(MAX_DAYS) else last
        val rules = graph.deliberateDao.rules()
        val events = calendarEvents(context, first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), graph.calendarSwitches.switchedOff())
            .filter { hasWords(words, it.title, it.location, it.notes) }.sortedBy { it.begin }
        val lines = events.take(MAX_EVENTS).map { event ->
            val parts = mutableListOf("id ${event.eventId}", "begin ${event.begin}", event.title.ifBlank { "(no title)" },
                eventTime(event))

            // Add the place, notes and rule effects only when there are some
            if (event.location.isNotBlank()) {
                parts.add("at ${event.location}")
            }

            if (event.notes.isNotBlank()) {
                parts.add("notes: ${event.notes.replace("\n", " / ").take(200)}")
            }
            val ruled = rules.filter { it.isOn && it.kind !in setOf(RULE_IGNORE, RULE_HABIT) &&
                coversEvent(it, event.title, event.location) }

            if (ruled.isNotEmpty()) {
                parts.add("rules: " + ruled.joinToString("; ") { ruleText(it) })
            }
            parts.add(ownTravel(event.title, event.begin))
            parts.filter { it.isNotBlank() }.joinToString(" · ")
        }

        // Say so when nothing matched
        if (lines.isEmpty()) {
            return "No calendar events from $first to $until" + if (words.isBlank()) "." else " with “$words”."
        }
        val more = if (events.size > MAX_EVENTS) "\n(${events.size - MAX_EVENTS} more; narrow the days or words)" else ""
        return lines.joinToString("\n") + more
    }

    // One plan in a line: id, status, title, when, place, who it came from, bring, and the kept message
    private fun planLine(plan: Suggestion): String {
        val parts = mutableListOf("id ${plan.id}", plan.status, planSummary(plan), "from ${fromLine(plan)}")

        // Add what's known about it
        if (plan.change.isNotBlank()) {
            parts.add("a ${plan.change} change to calendar event ${plan.calendarEventId}")
        }

        if (plan.bring.isNotBlank()) {
            parts.add("bring ${plan.bring}")
        }

        if (plan.trackingNumber.isNotBlank()) {
            parts.add("package ${plan.packageCarrier} ${plan.trackingNumber}")
        }

        if (plan.flight.isNotBlank()) {
            parts.add("flight ${plan.flight}")
        }
        parts.add(ownTravel(plan.title, eventTimes(plan.toEvent()).first))

        if (plan.link.isNotBlank()) {
            parts.add("link ${plan.link}")
        }

        if (plan.message.isNotBlank()) {
            parts.add("message: “${plan.message.replace("\n", " ").take(MAX_MESSAGE)}”")
        }
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    // The plans with a status (or any) and the words, newest first
    private suspend fun findPlans(status: String, words: String): String {
        val plans = graph.dao.allSuggestions()
            .filter { status == "any" || status.isBlank() || it.status == status }
            .filter { hasWords(words, it.title, it.sender, it.conversation, it.message, it.location) }
        val keep = if (DeliberateSettings(context).keepTexts()) "" else "\n(Message texts aren't kept; it's off in " +
            "Settings → Privacy.)"

        // Say so when nothing matched
        if (plans.isEmpty()) {
            return "No plans found." + keep
        }
        return plans.take(MAX_PLANS).joinToString("\n") { planLine(it) } + keep
    }

    // One list item in a line
    private fun itemLine(item: FollowUp): String {
        // Name the kind as the lists do
        val kind = when (item.kind) {
            KIND_DUE -> "bill"
            KIND_ASSIGNMENT -> "assignment"
            KIND_LOOSE -> "loose plan"
            else -> item.kind
        }
        val parts = mutableListOf("id ${item.id}", kind, item.title)

        // Add what's known about it
        if (item.amount.isNotBlank()) {
            parts.add(item.amount)
        }

        if (item.kind == KIND_REMINDER) {
            parts.add("goes off ${reminderWhen(item, zone).ifBlank { "(done)" }}")
        } else if (item.date.isNotBlank()) {
            parts.add("${if (item.kind == KIND_LOOSE) "around" else "due"} ${item.date}" +
                if (item.time.isNotBlank()) " ${item.time}" else "")
        }

        if (repeatLabel(item.repeat).isNotBlank()) {
            parts.add(repeatLabel(item.repeat).lowercase())
        }

        if (item.kind == KIND_NOTE && item.body.isNotBlank()) {
            parts.add("text: ${item.body.replace("\n", " / ").take(MAX_MESSAGE)}")
        }

        if (item.kind != KIND_NOTE && item.sender.isNotBlank() && item.sender != "you") {
            parts.add("from ${item.sender}" + if (item.conversation.isNotBlank()) " in ${item.conversation}" else "")
        }

        if (item.link.isNotBlank()) {
            parts.add("link ${item.link}")
        }

        if (item.message.isNotBlank()) {
            parts.add("message: “${item.message.replace("\n", " ").take(MAX_MESSAGE)}”")
        }
        return parts.joinToString(" · ")
    }

    // The open list items of a kind (or all) with the words, plus packages still on their way
    private suspend fun findItems(kind: String, words: String): String {
        // The kinds of follow-up asked for
        val wanted = when (kind) {
            "bills" -> setOf(KIND_DUE)
            "assignments" -> setOf(KIND_ASSIGNMENT)
            "reminders" -> setOf(KIND_REMINDER)
            "notes" -> setOf(KIND_NOTE)
            "loose" -> setOf(KIND_LOOSE)
            "packages" -> emptySet()
            else -> setOf(KIND_DUE, KIND_ASSIGNMENT, KIND_REMINDER, KIND_NOTE, KIND_LOOSE)
        }
        val lines = graph.dao.allOpenFollowUps().filter { it.kind in wanted }
            .filter { hasWords(words, it.title, it.body, it.sender, it.message) }.map { itemLine(it) }.toMutableList()

        // Packages are plans with a tracking number that haven't been marked received
        if (kind == "packages" || kind == "all") {
            val received = PackageStore(context).received()
            graph.dao.packagesNow().filter { it.id !in received && hasWords(words, it.title, it.trackingNumber) }
                .forEach { lines.add("id ${it.id} · package · ${planSummary(it)} · ${it.packageCarrier} " +
                    it.trackingNumber) }
        }
        return if (lines.isEmpty()) "Nothing found." else lines.joinToString("\n")
    }

    // The settings, rules, habits and pauses
    private suspend fun settings(): String {
        val summary = graph.summarySettings
        val travel = graph.travelSettings
        val deliberate = DeliberateSettings(context)
        val trip = graph.trainSettings.trip()
        val names = graph.trainSettings.stations().associate { it.code to it.name }
        val lines = mutableListOf(
            "Morning summary: ${if (summary.isOn()) "on" else "off"} at " +
                "${"%02d".format(summary.hour())}:${"%02d".format(summary.minute())}",
            "Home address: ${travel.home().ifBlank { "(not set)" }}",
            "Default travel mode: ${travelMode(travel.mode()).name}",
            "Saved train trip: " + (trip?.let { "${names[it.first] ?: it.first} → ${names[it.second] ?: it.second}" }
                ?: "(none)"),
            "Keep message texts: ${if (deliberate.keepTexts()) "on" else "off"}",
        )

        // Name each pause still on
        for (what in PAUSE_KINDS.filter { deliberate.paused(it) }) {
            lines.add("${pauseName(what)} paused until " +
                Instant.ofEpochMilli(deliberate.pausedUntil(what)).atZone(zone).format(DAY_TIME))
        }
        val rules = graph.deliberateDao.rules()
        lines.add(if (rules.isEmpty()) "Rules: none" else "Rules:")

        // One line per rule
        for (rule in rules) {
            lines.add("id ${rule.id} · ${if (rule.isOn) "on" else "off"} · ${ruleText(rule)}")
        }
        return lines.joinToString("\n")
    }

    // The men's foil tournaments found, one per line
    private fun tournaments(): String {
        val settings = FencingSettings(context)

        // Say so when the searches are off or found nothing
        if (!settings.isOn()) {
            return "Fencing tournaments are off (Settings → Fencing)."
        }
        val list = settings.tournaments()

        // One line per tournament
        return if (list.isEmpty()) "No men's foil tournaments found for the coming weeks." else list.joinToString("\n") {
            listOf(it.name, it.meet, it.place, "${it.firstDay} to ${it.lastDay}",
                if (it.startTime.isNotBlank()) "starts ${it.startTime}" else "",
                if (it.todayDate.isBlank()) ""
                    else "on ${it.todayDate}: stream ${it.todayStart}–${it.todayEnd} ${it.todayNote}",
                if (it.streamUrl.isNotBlank()) "watch ${it.streamName} ${it.streamUrl}" else "",
                if (it.resultsUrl.isNotBlank()) "results ${it.resultsUrl}" else "").filter { part -> part.isNotBlank() }
                .joinToString(" · ")
        }
    }

    // The forecast for the next days and the saved trip's trains
    private fun extras(): String {
        val now = System.currentTimeMillis()
        val forecast = homeForecast(context, now, zone)
        val unit = if (usesFahrenheit()) "°F" else "°C"
        val lines = mutableListOf<String>()

        // The forecast, day by day, when it could be fetched
        if (forecast == null) {
            lines.add("Weather: not available right now.")
        } else {
            forecast.days.take(7).forEach { lines.add("${it.date.format(DAY)}: high ${it.high}$unit, low " +
                "${it.low}$unit, rain ${it.rain}%") }
        }
        val trip = graph.trainSettings.trip()

        // The trains, when a trip is saved
        if (trip == null) {
            lines.add("Trains: no trip saved.")
        } else {
            val board = nextTrains(graph, trip.first, trip.second, now)
            lines.add("Trains ${board.fromName} → ${board.toName}:")
            lines.addAll(board.lines.ifEmpty { listOf(board.note.ifBlank { "No trains found." }) })
        }
        return lines.joinToString("\n")
    }
}
