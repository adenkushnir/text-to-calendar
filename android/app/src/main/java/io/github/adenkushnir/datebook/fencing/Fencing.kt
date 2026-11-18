// Men's foil tournaments to follow and watch: what Claude's web search found, saved as JSON; which ones fall in the
// next 7 days for the tiles on Home, which one is live, the line for an empty week, and the searches' instructions
package io.github.adenkushnir.datebook.fencing
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
const val WINDOW_DAYS = 7L  // Home shows the next week
const val SEARCH_DAYS = 21L  // the Monday search looks 3 weeks ahead
private const val LIVE_HOURS = 8L  // a stream with no end given counts as live for 8 hours
private val MAPPER = ObjectMapper()
private val JSON_TAGS = Regex("<json>(.*?)</json>", RegexOption.DOT_MATCHES_ALL)
private val DAY_NAME = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

// One tournament event: its name ("Junior Men's Foil"), the meet ("November NAC"), where, its first and last days,
// when it starts each day (HH:MM in your zone, "" when unknown), the stream's name and link, the results link, and
// from the event-day check: today's stream start and end (HH:MM) and its stage ("Table of 16")
data class Tournament(
    val name: String,
    val meet: String,
    val place: String,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val startTime: String = "",
    val streamName: String = "",
    val streamUrl: String = "",
    val resultsUrl: String = "",
    val todayDate: String = "",
    val todayStart: String = "",
    val todayEnd: String = "",
    val todayNote: String = "",
)

// Return true if a tournament is a USA Fencing national event (NACs, Junior Olympics, Summer Nationals, national
// championships); everything else is treated as an FIE event
fun isUsaEvent(tournament: Tournament): Boolean {
    val words = "${tournament.meet} ${tournament.name}".lowercase()
    return listOf("nac", "north american cup", "junior olympics", "summer nationals", "national championship",
        "usa fencing").any { words.contains(it) }
}

// Where Watch goes: the stream found, else FencingTV's schedule for USA events, else a YouTube search for the FIE
// event's live stream
fun watchLink(tournament: Tournament): String {
    // The exact stream wins
    if (tournament.streamUrl.isNotBlank()) {
        return tournament.streamUrl
    }
    val search = java.net.URLEncoder.encode("${tournament.meet.ifBlank { tournament.name }} men's foil live", "UTF-8")
    return if (isUsaEvent(tournament)) "https://www.fencing.tv"
        else "https://www.youtube.com/results?search_query=$search"
}

// Where Results goes: the results page found, else FencingTimeLive for USA events or the FIE's competitions page
fun resultsLink(tournament: Tournament): String {
    // The exact page wins
    if (tournament.resultsUrl.isNotBlank()) {
        return tournament.resultsUrl
    }
    return if (isUsaEvent(tournament)) "https://www.fencingtimelive.com" else "https://fie.org/competitions"
}

// One tile on Home: the tournament, the day it shows (today when it's on, else its first day), whether it's live now
data class FencingTile(val tournament: Tournament, val day: LocalDate, val live: Boolean)

// A tournament's key, so a later search or the event-day check updates the same one
fun tournamentKey(tournament: Tournament): String {
    return "${tournament.name.trim().lowercase()}|${tournament.meet.trim().lowercase()}|${tournament.firstDay}"
}

// Tournaments as JSON, for saving
fun tournamentsToJson(list: List<Tournament>): String {
    return MAPPER.writeValueAsString(list.map {
        mapOf("name" to it.name, "meet" to it.meet, "place" to it.place, "first_day" to it.firstDay.toString(),
            "last_day" to it.lastDay.toString(), "start_time" to it.startTime, "stream_name" to it.streamName,
            "stream_url" to it.streamUrl, "results_url" to it.resultsUrl, "today_date" to it.todayDate,
            "today_start" to it.todayStart, "today_end" to it.todayEnd, "today_note" to it.todayNote)
    })
}

// A text field of a JSON object, trimmed ("" when missing)
private fun text(node: JsonNode, name: String): String {
    return node.path(name).asText("").trim()
}

// A link only when it's a web link
private fun link(value: String): String {
    return if (value.startsWith("https://") || value.startsWith("http://")) value else ""
}

// A time only when it reads as HH:MM
private fun clockOf(value: String): String {
    return runCatching { LocalTime.parse(value).toString() }.getOrDefault("")
}

// Tournaments from JSON: saved ones, or the events list in Claude's answer (unreadable entries are skipped)
fun tournamentsFrom(node: JsonNode): List<Tournament> {
    return node.mapNotNull { item ->
        val first = runCatching { LocalDate.parse(text(item, "first_day")) }.getOrNull() ?: return@mapNotNull null
        val last = runCatching { LocalDate.parse(text(item, "last_day")) }.getOrNull()?.takeIf { !it.isBefore(first) }
            ?: first
        val name = text(item, "name").ifBlank { return@mapNotNull null }
        Tournament(name, text(item, "meet"), text(item, "place"), first, last, clockOf(text(item, "start_time")),
            text(item, "stream_name"), link(text(item, "stream_url")), link(text(item, "results_url")),
            text(item, "today_date"), clockOf(text(item, "today_start")), clockOf(text(item, "today_end")),
            text(item, "today_note"))
    }
}

// Saved tournaments from JSON (empty for an empty or unreadable string)
fun tournamentsFromJson(json: String): List<Tournament> {
    // Nothing saved yet
    if (json.isBlank()) {
        return emptyList()
    }
    return runCatching { tournamentsFrom(MAPPER.readTree(json)) }.getOrDefault(emptyList())
}

// The JSON object in Claude's answer: inside <json> tags, else the last {...} in it; null when there is none
fun answerJson(answer: String): JsonNode? {
    val tagged = JSON_TAGS.findAll(answer).lastOrNull()?.groupValues?.get(1)
    val open = answer.indexOf('{')
    val close = answer.lastIndexOf('}')
    val candidate = tagged ?: if (open >= 0 && close > open) answer.substring(open, close + 1) else return null
    return runCatching { MAPPER.readTree(candidate.trim()) }.getOrNull()?.takeIf { it.isObject }
}

// Merge a new search into what was saved: the new search wins for each tournament, keeping today's details already
// found, and tournaments that are over are dropped
fun mergeSearch(saved: List<Tournament>, found: List<Tournament>, today: LocalDate): List<Tournament> {
    val old = saved.associateBy { tournamentKey(it) }
    val merged = found.map { new ->
        val before = old[tournamentKey(new)]
        if (before != null && before.todayDate == today.toString()) new.copy(todayDate = before.todayDate,
            todayStart = before.todayStart, todayEnd = before.todayEnd, todayNote = before.todayNote) else new
    }
    return merged.filter { !it.lastDay.isBefore(today) }.sortedBy { it.firstDay }
}

// Add what the event-day check found about today (matched by name and meet) to the saved tournaments
fun mergeToday(saved: List<Tournament>, found: List<Tournament>, today: LocalDate): List<Tournament> {
    return saved.map { tournament ->
        val match = found.firstOrNull { it.name.equals(tournament.name, ignoreCase = true) &&
            it.meet.equals(tournament.meet, ignoreCase = true) }
        if (match == null) tournament else tournament.copy(
            streamUrl = match.streamUrl.ifBlank { tournament.streamUrl },
            streamName = match.streamName.ifBlank { tournament.streamName },
            resultsUrl = match.resultsUrl.ifBlank { tournament.resultsUrl },
            todayDate = today.toString(), todayStart = match.todayStart, todayEnd = match.todayEnd,
            todayNote = match.todayNote)
    }
}

// Return true if a tournament is on a day
fun isOn(tournament: Tournament, day: LocalDate): Boolean {
    return !day.isBefore(tournament.firstDay) && !day.isAfter(tournament.lastDay)
}

// When today's stream starts and ends, in milliseconds: from the event-day check, else the usual start time (and 8
// hours later); null when neither is known or the tournament isn't on today
fun streamWindow(tournament: Tournament, today: LocalDate, zone: ZoneId): Pair<Long, Long>? {
    // Only on the tournament's days
    if (!isOn(tournament, today)) {
        return null
    }
    val checked = tournament.todayDate == today.toString()
    val start = runCatching { LocalTime.parse(if (checked) tournament.todayStart.ifBlank { tournament.startTime }
        else tournament.startTime) }.getOrNull() ?: return null
    val begin = today.atTime(start).atZone(zone).toInstant().toEpochMilli()
    val endTime = if (checked) runCatching { LocalTime.parse(tournament.todayEnd) }.getOrNull() else null
    val end = endTime?.let { today.atTime(it).atZone(zone).toInstant().toEpochMilli() }?.takeIf { it > begin }
        ?: (begin + LIVE_HOURS * 60 * 60 * 1000)
    return Pair(begin, end)
}

// The tiles for Home: tournaments on in the next 7 days, in order, each on today (when it's on) or its first day,
// with the live one marked
fun fencingTiles(tournaments: List<Tournament>, nowMillis: Long, zone: ZoneId): List<FencingTile> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val last = today.plusDays(WINDOW_DAYS - 1)
    return tournaments.filter { !it.lastDay.isBefore(today) && !it.firstDay.isAfter(last) }.map { tournament ->
        val window = streamWindow(tournament, today, zone)
        val live = window != null && nowMillis >= window.first && nowMillis < window.second
        FencingTile(tournament, if (isOn(tournament, today)) today else tournament.firstDay, live)
    }.sortedWith(compareBy({ !it.live }, { it.day }))
}

// The line for a week with nothing on: "No men's foil events this week. Next: Junior World Cup, Bochum · Sat Dec 5"
fun emptyWeekLine(tournaments: List<Tournament>, today: LocalDate): String {
    val next = tournaments.filter { it.firstDay.isAfter(today.plusDays(WINDOW_DAYS - 1)) }.minByOrNull { it.firstDay }
    val name = next?.let { listOf(it.meet.ifBlank { it.name }, it.place).filter { part -> part.isNotBlank() }
        .joinToString(", ") }
    return "No men's foil events this week." + if (next == null) "" else " Next: $name · ${next.firstDay.format(DAY)}."
}

// A tile's day in big letters ("Sat") and its days ("Fri–Sat", or one day)
fun tileDays(tile: FencingTile): Pair<String, String> {
    val first = tile.tournament.firstDay.format(DAY_NAME)
    val last = tile.tournament.lastDay.format(DAY_NAME)
    return Pair(tile.day.format(DAY_NAME), if (tile.tournament.lastDay == tile.tournament.firstDay) first
        else "$first–$last")
}

// A tile's time line: today's stage when live, else when it starts ("Starts 8:00 AM"; "" when unknown)
fun tileTime(tile: FencingTile): String {
    val tournament = tile.tournament

    // Live tiles say what's on
    if (tile.live) {
        return tournament.todayNote.ifBlank { "Streaming now" }
    }
    val start = runCatching { LocalTime.parse(tournament.todayStart.ifBlank { tournament.startTime }) }.getOrNull()
    return if (start == null) "" else "Starts ${start.format(CLOCK)}"
}

// The Monday search's instructions: what to find, for which days, in which time zone, and the answer's form
fun searchPrompt(today: LocalDate, zone: ZoneId): String {
    val until = today.plusDays(SEARCH_DAYS)
    return """Find every men's foil fencing competition from $today to $until at national level in the USA (USA
Fencing North American Cups, Junior Olympics, Summer Nationals, national championships) or international level (FIE
Junior World Cups, Senior World Cups and Grand Prix, zonal and world championships, the Olympics).
Only these categories: Junior (U20), Division I or Senior, Division IA, Division II. Skip cadet, youth, veteran,
women's events and other weapons. Each category at a meet is its own entry.
For each, find when men's foil starts on its days, converted to the time zone ${zone.id}, where it is streamed
and its live results page (FencingTimeLive, the FIE site). For streams, always check FencingTV's schedule at
https://www.fencing.tv as well as the FIE's channel, Olympics.com and the event's own site. Open the pages with web
fetch (each event's own page, its FencingTimeLive tournament page, its FIE competition page) to find the exact
stream_url and results_url for that event, not just a home page.
Never guess dates or links: leave a field empty when you can't find it.
Answer with only this JSON inside <json></json> tags:
{"events": [{"name": "Junior Men's Foil", "meet": "November NAC", "place": "Columbus, OH", "first_day": "YYYY-MM-DD",
"last_day": "YYYY-MM-DD", "start_time": "HH:MM", "stream_name": "", "stream_url": "", "results_url": ""}]}"""
}

// The event-day check's instructions: today's schedule and stream for the tournaments on today
fun todayPrompt(today: LocalDate, zone: ZoneId, onToday: List<Tournament>): String {
    val list = onToday.joinToString("\n") { "- ${it.name} · ${it.meet} · ${it.place}" }
    return """Today is $today. For each of these men's foil fencing events happening today:
$list
find today's live stream (its name and link), when today's stream starts and ends converted to the time zone
${zone.id}, the live results page, and what stage is on today (like "Table of 16" or "Finals"). Always check
FencingTV's schedule at https://www.fencing.tv for today's streams, as well as the FIE's channel and the event's own
site, and open the pages with web fetch to get the exact stream and results links for each event. Never guess: leave a
field empty when you can't find it.
Answer with only this JSON inside <json></json> tags, one entry per event above, with its name and meet as given:
{"events": [{"name": "", "meet": "", "first_day": "$today", "last_day": "$today", "stream_name": "", "stream_url": "",
"results_url": "", "today_start": "HH:MM", "today_end": "HH:MM", "today_note": ""}]}"""
}
