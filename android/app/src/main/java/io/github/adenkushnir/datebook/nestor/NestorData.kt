// What Datebook sends Nestor, built from plain data so it can be tested: the alerts it speaks (which notifications,
// their words, a key against repeats and when they go stale), and the snapshot of plans, lists, notes, reminders,
// the train trip, the next 14 days of calendar events (with each one's picked train) and this week's men's foil, in the
// JSON Nestor's README describes
package io.github.adenkushnir.datebook.nestor
import io.github.adenkushnir.datebook.calendar.CalendarEvent
import io.github.adenkushnir.datebook.capture.WATCHABLE_APPS
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.extras.bringOf
import io.github.adenkushnir.datebook.fencing.FencingTile
import io.github.adenkushnir.datebook.fencing.streamWindow
import io.github.adenkushnir.datebook.flights.FlightInfo
import io.github.adenkushnir.datebook.journeys.Journey
import io.github.adenkushnir.datebook.flights.wallFlight
import io.github.adenkushnir.datebook.lists.Trip
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.tracking.packageLink
import io.github.adenkushnir.datebook.trains.TrainPick
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
const val SNAPSHOT_DAYS = 14L  // events from the start of today to 14 days ahead
val SPOKEN_CHANNELS = setOf("leave_by", "travel_changes", "follow_ups", "evening")
private val SOON_CHANNELS = setOf("leave_by", "travel_changes")  // these go stale after 20 minutes
private const val SOON_MINUTES = 20L
private const val LATER_HOURS = 12L
private val SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
private val MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val HHMM = Regex("""\d{2}:\d{2}""")  // a start time like "08:00"
private const val CONFIRMATION = "Confirmation: "
private const val TRACK = "Track: "

// A planned leave-by for an event: when to leave, the trip in words ("20 min drive") and how many minutes early you
// mean to be there
data class PlannedLeave(val leaveAt: Long, val travel: String, val early: Int = 0)

// One calendar event for the snapshot, with its calendar's name, its planned leave-by, the NJ Transit train picked
// for it, for flights the flight's last-known details, and the journey kept from its card (null when none)
data class SnapshotEvent(val event: CalendarEvent, val calendar: String, val leave: PlannedLeave?,
    val train: TrainPick? = null, val flight: FlightInfo? = null, val journey: Journey? = null)

// Everything the snapshot is made from
data class SnapshotParts(
    val waiting: List<Suggestion> = emptyList(),
    val packages: List<Suggestion> = emptyList(),
    val bills: List<FollowUp> = emptyList(),
    val assignments: List<FollowUp> = emptyList(),
    val trips: List<Trip> = emptyList(),
    val notes: List<FollowUp> = emptyList(),
    val reminders: List<FollowUp> = emptyList(),
    val trip: Map<String, String>? = null,
    val events: List<SnapshotEvent> = emptyList(),
    val fencing: List<FencingTile> = emptyList(),
)

// Return true if a notification on this channel is spoken by Nestor
fun isSpoken(channel: String?): Boolean {
    return channel in SPOKEN_CHANNELS
}

// What Nestor says for a notification: its title, then its text (or its lines), as one string
fun alertText(title: String, text: String, lines: List<String> = emptyList()): String {
    val body = if (lines.isNotEmpty()) lines.joinToString(". ") else text
    return listOf(title.trim(), body.trim()).filter { it.isNotBlank() }.joinToString(". ")
}

// The key Nestor uses to skip a repeat within 12 hours: the channel, the notification and its words
fun alertKey(channel: String, id: Int, text: String): String {
    return "$channel|$id|${text.hashCode()}"
}

// When an alert goes stale: 20 minutes for leave-by and travel changes, 12 hours for the rest
fun alertExpires(channel: String, nowMillis: Long, zone: ZoneId): String {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDateTime()
    val expires = if (channel in SOON_CHANNELS) now.plusMinutes(SOON_MINUTES) else now.plusHours(LATER_HOURS)
    return expires.format(SECONDS)
}

// A time as Nestor reads it, "2026-11-20T19:00"
fun localMinutes(millis: Long, zone: ZoneId): String {
    return Instant.ofEpochMilli(millis).atZone(zone).format(MINUTES)
}

// An event's start or end: the date for all-day events (kept at midnight UTC), else the local time
private fun eventTime(millis: Long, allDay: Boolean, zone: ZoneId): String {
    return if (allDay) Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
        else localMinutes(millis, zone)
}

// Who a calendar event came from, read from Datebook's first notes line ("Jake · Soccer Team (GroupMe)" gives Jake,
// "Sam (Messages)" Sam, "Canvas · PHYS-101" Canvas); empty for notes Datebook didn't write and for texts you sent
fun fromOfNotes(notes: String): String {
    val first = notes.lineSequence().firstOrNull()?.trim().orEmpty()
    val apps = WATCHABLE_APPS.map { it.shortName }

    // Canvas class events, then texts from a watched app
    if (first.startsWith("Canvas · ")) {
        return "Canvas"
    }
    val app = apps.firstOrNull { first.endsWith(" ($it)") } ?: return ""
    val who = first.removeSuffix(" ($app)").substringBefore(" · ").trim()
    return if (who.startsWith("You →")) "" else who
}

// One line of an event's notes that starts with a label ("" without it)
private fun labelled(notes: String, label: String): String {
    return notes.lines().firstOrNull { it.trim().startsWith(label) }?.trim()?.removePrefix(label)?.trim().orEmpty()
}

// A plan waiting for Add, for the snapshot
private fun waitingJson(plan: Suggestion): Map<String, Any> {
    return mapOf("id" to plan.id, "title" to plan.title, "start" to plan.start, "end" to plan.end,
        "all_day" to plan.allDay, "place" to plan.location, "from" to fromLine(plan), "kind" to "event",
        "change" to plan.change)
}

// A follow-up's due day and time ("2026-11-21T23:59", or the day alone)
private fun dueOf(item: FollowUp): String {
    return if (item.time.isBlank()) item.date else "${item.date}T${item.time}"
}

// A calendar event, for the snapshot (with its train when one is picked and a flight's status, for the wall's Next
// card)
private fun eventJson(item: SnapshotEvent, zone: ZoneId): Map<String, Any> {
    val event = item.event
    val json = mutableMapOf<String, Any>("id" to "${event.eventId}-${event.begin / 1000}", "title" to event.title,
        "start" to eventTime(event.begin, event.allDay, zone), "end" to eventTime(event.end, event.allDay, zone),
        "all_day" to event.allDay, "place" to event.location, "calendar" to item.calendar,
        "from" to fromOfNotes(event.notes), "bring" to bringOf(event.notes),
        "confirmation" to labelled(event.notes, CONFIRMATION), "track_url" to labelled(event.notes, TRACK),
        "leave_by" to (item.leave?.let { localMinutes(it.leaveAt, zone) } ?: ""),
        "travel" to (item.leave?.travel ?: ""), "early" to (item.leave?.early ?: 0))

    // The picked train: its number, when it leaves and arrives, from and to where, its track, delay and state
    if (item.train != null) {
        json["train"] = mapOf("departs" to Instant.ofEpochMilli(item.train.departs).atZone(zone).format(CLOCK),
            "from" to item.train.fromName, "to" to item.train.toName, "id" to item.train.trainId,
            "arrives" to Instant.ofEpochMilli(item.train.arrives).atZone(zone).format(CLOCK),
            "track" to item.train.track, "late" to item.train.secondsLate / 60, "status" to item.train.status)
    }

    // The journey kept from the plan's card: each step's kind, words, line and colour, and when it starts
    if (item.journey != null) {
        json["journey"] = item.journey.steps.map { step ->
            mapOf("kind" to step.kind, "title" to step.title, "under" to step.under, "line" to step.line,
                "color" to step.color, "starts" to localMinutes(step.starts, zone))
        }
    }

    // A flight's status line (orange on the wall when it's late or cancelled), and its details for the trip screen
    if (item.flight != null) {
        json["flight"] = wallFlight(item.flight) + flightDetails(item.flight, zone)
    }
    return json
}

// A flight's details for the wall's trip screen: its number, where it lands, when it departs and lands (local times,
// "" when unknown), terminal and gate
fun flightDetails(info: FlightInfo, zone: ZoneId): Map<String, Any> {
    val departs = info.departBest ?: info.departScheduled
    val lands = info.arriveBest ?: info.arriveScheduled
    return mapOf("number" to info.number, "to_city" to info.toCity, "to_code" to info.toCode,
        "departs" to (departs?.let { localMinutes(it.toEpochMilli(), zone) } ?: ""),
        "lands" to (lands?.let { localMinutes(it.toEpochMilli(), zone) } ?: ""), "terminal" to info.terminal,
        "gate" to info.gate)
}

// A men's foil event on the wall's schedule: its name, the meet and place, the day it shows (today while it's on) with
// its start time when known, its last day, today's stage, and when its stream is on that day (for LIVE)
fun fencingJson(tile: FencingTile, zone: ZoneId): Map<String, Any> {
    val tournament = tile.tournament
    val time = tournament.startTime.takeIf { HHMM.matches(it) }
    val window = streamWindow(tournament, tile.day, zone)
    return mapOf("name" to tournament.name,
        "where" to listOf(tournament.meet, tournament.place).filter { it.isNotBlank() }.joinToString(" · "),
        "start" to if (time != null) "${tile.day}T$time" else tile.day.toString(), "end" to tournament.lastDay.toString(),
        "live" to tile.live, "stage" to if (tournament.todayDate == tile.day.toString()) tournament.todayNote else "",
        "live_from" to (window?.let { localMinutes(it.first, zone) } ?: ""),
        "live_until" to (window?.let { localMinutes(it.second, zone) } ?: ""))
}

// The whole snapshot as JSON-ready values
fun snapshotOf(parts: SnapshotParts, zone: ZoneId): Map<String, Any> {
    val snapshot = mutableMapOf<String, Any>(
        "waiting" to parts.waiting.map(::waitingJson),
        "lists" to mapOf(
            "packages" to parts.packages.map {
                mapOf("id" to it.id, "title" to it.title, "carrier" to it.packageCarrier.uppercase(),
                    "number" to it.trackingNumber,
                    "track_url" to (packageLink(it.packageCarrier, it.trackingNumber)?.url ?: ""),
                    "expected" to it.start.take(10))
            },
            "bills" to parts.bills.map { mapOf("id" to it.id, "title" to it.title, "due" to dueOf(it),
                "amount" to it.amount) },
            "assignments" to parts.assignments.map { mapOf("id" to it.id, "title" to it.title, "due" to dueOf(it),
                "class" to it.conversation) },
            "trips" to parts.trips.map { trip ->
                mapOf("id" to (trip.plans.firstOrNull()?.id ?: 0L), "title" to trip.name,
                    "start" to trip.first.toString(), "end" to trip.last.toString(), "plans" to trip.plans.map { plan ->
                        mapOf("title" to plan.title, "start" to plan.start, "confirmation" to plan.confirmation)
                    })
            },
        ),
        "notes" to parts.notes.map { mapOf("id" to it.id, "text" to it.body.ifBlank { it.title }) },
        "reminders" to parts.reminders.filter { it.nextNudge > 0 }
            .map { mapOf("id" to it.id, "text" to it.title, "at" to localMinutes(it.nextNudge, zone)) },
        "events" to parts.events.map { eventJson(it, zone) },
        "fencing" to parts.fencing.filter { !it.finished }.map { fencingJson(it, zone) },
    )

    // The train trip only when one is saved
    if (parts.trip != null) {
        snapshot["trip"] = parts.trip
    }
    return snapshot
}

// The first day of the snapshot's events (the start of today) and the end of its 14 days, in milliseconds
fun snapshotWindow(nowMillis: Long, zone: ZoneId): Pair<Long, Long> {
    val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toLocalDate()
    return Pair(today.atStartOfDay(zone).toInstant().toEpochMilli(),
        today.plusDays(SNAPSHOT_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli())
}

// Return true if an address is on the home network (a private, link-local or loopback address), the only kind
// Datebook talks to Nestor on
fun isHomeAddress(address: java.net.InetAddress): Boolean {
    val bytes = address.address
    val uniqueLocal = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC  // IPv6 fc00::/7
    return address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress || uniqueLocal
}
