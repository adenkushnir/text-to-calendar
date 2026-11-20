// The tools Deliberate gives Claude: look things up (calendar, plans and their messages, lists, settings, weather and
// trains), ask you a question with choices, and propose changes for you to confirm; plus Deliberate's instructions
package io.github.adenkushnir.datebook.deliberate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
const val TOOL_FIND_EVENTS = "find_events"
const val TOOL_FIND_PLANS = "find_plans"
const val TOOL_FIND_ITEMS = "find_items"
const val TOOL_SETTINGS = "get_settings"
const val TOOL_EXTRAS = "get_weather_and_trains"
const val TOOL_TOURNAMENTS = "find_tournaments"
const val TOOL_ASK = "ask_choice"
const val TOOL_PROPOSE = "propose_changes"
const val ACT_NEW_PLAN = "new_plan"
const val ACT_EDIT_WAITING = "edit_waiting"
const val ACT_ADD_WAITING = "add_waiting"
const val ACT_IGNORE_WAITING = "ignore_waiting"
const val ACT_MOVE_EVENT = "move_event"
const val ACT_CANCEL_EVENT = "cancel_event"
const val ACT_ADD_BILL = "add_bill"
const val ACT_ADD_ASSIGNMENT = "add_assignment"
const val ACT_ADD_REMINDER = "add_reminder"
const val ACT_ADD_NOTE = "add_note"
const val ACT_ADD_PACKAGE = "add_package"
const val ACT_EDIT_ITEM = "edit_item"
const val ACT_MARK_DONE = "mark_done"
const val ACT_DELETE_ITEM = "delete_item"
const val ACT_PACKAGE_RECEIVED = "package_received"
const val ACT_ADD_RULE = "add_rule"
const val ACT_TURN_RULE = "turn_rule"
const val ACT_DELETE_RULE = "delete_rule"
const val ACT_PAUSE = "pause"
const val ACT_SETTING = "set_setting"
const val ACT_SET_LINK = "set_link"
const val TARGET_PLAN = "plan"
const val TARGET_ITEM = "item"
const val SET_SUMMARY_TIME = "summary_time"
const val SET_SUMMARY_ON = "summary_on"
const val SET_HOME = "home_address"
const val SET_TRAVEL_MODE = "travel_mode"
const val SET_FLIP_TRIP = "flip_train_trip"
const val SET_KEEP_TEXTS = "keep_texts"
val ACTIONS = listOf(ACT_NEW_PLAN, ACT_EDIT_WAITING, ACT_ADD_WAITING, ACT_IGNORE_WAITING, ACT_MOVE_EVENT,
    ACT_CANCEL_EVENT, ACT_ADD_BILL, ACT_ADD_ASSIGNMENT, ACT_ADD_REMINDER, ACT_ADD_NOTE, ACT_ADD_PACKAGE, ACT_EDIT_ITEM,
    ACT_MARK_DONE, ACT_DELETE_ITEM, ACT_PACKAGE_RECEIVED, ACT_ADD_RULE, ACT_TURN_RULE, ACT_DELETE_RULE, ACT_PAUSE,
    ACT_SETTING, ACT_SET_LINK)
val SETTINGS = listOf(SET_SUMMARY_TIME, SET_SUMMARY_ON, SET_HOME, SET_TRAVEL_MODE, SET_FLIP_TRIP, SET_KEEP_TEXTS)
private val TODAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a", Locale.US)
private val STRING = mapOf("type" to "string")
private val INTEGER = mapOf("type" to "integer")

// A string field with a description
private fun text(description: String): Map<String, Any> {
    return mapOf("type" to "string", "description" to description)
}

// A schema for an object with these fields, some required
private fun objectOf(properties: Map<String, Any>, required: List<String> = emptyList()): Map<String, Any> {
    return mapOf("type" to "object", "properties" to properties, "required" to required)
}

// One change in propose_changes: what to do and the fields it needs (unused fields are left out)
private val CHANGE = objectOf(mapOf(
    "action" to mapOf("type" to "string", "enum" to ACTIONS),
    "id" to mapOf("type" to "integer", "description" to "The id of the plan, item, rule or calendar event changed, " +
        "from a find tool"),
    "begin" to mapOf("type" to "integer", "description" to "For move_event and cancel_event: the event's begin " +
        "(milliseconds) from find_events"),
    "title" to STRING,
    "date" to text("YYYY-MM-DD"),
    "time" to text("HH:MM, 24-hour; empty for all day"),
    "end_date" to text("YYYY-MM-DD"),
    "end_time" to text("HH:MM, 24-hour"),
    "place" to STRING,
    "amount" to text("For bills, like \$45"),
    "repeat" to text("An RRULE like FREQ=MONTHLY or FREQ=WEEKLY;BYDAY=MO; empty for none"),
    "text" to text("A note's text"),
    "carrier" to mapOf("type" to "string", "enum" to listOf("", "ups", "fedex", "usps", "amazon", "dhl")),
    "tracking_number" to STRING,
    "rule_kind" to mapOf("type" to "string", "enum" to RULE_KINDS),
    "words" to text("For rules: words in event or plan titles it covers, lowercase; several phrases split by |"),
    "sender" to text("For ignore rules: a person or group chat name"),
    "value" to text("For rules: minutes for travel_minutes, arrive_early and alert_earlier; DRIVE, TRANSIT, WALK " +
        "or BICYCLE for travel_mode; things to bring; minutes before or \"evening\" for remind_before; the plan kind " +
        "(event, due, assignment, reminder, loose) or empty for ignore; the habit in words. For turn_rule: on or " +
        "off. For set_setting: the new value"),
    "pause" to mapOf("type" to "string", "enum" to PAUSE_KINDS),
    "until" to text("For pause: YYYY-MM-DDTHH:MM, when the pause ends"),
    "setting" to mapOf("type" to "string", "enum" to SETTINGS),
    "target" to mapOf("type" to "string", "enum" to listOf(TARGET_PLAN, TARGET_ITEM), "description" to "For " +
        "set_link: plan for a package or trip booking (from find_plans or find_items' packages), item for a bill " +
        "or other list item"),
    "link" to text("For set_link: the web link to save, or empty to remove it"),
), listOf("action"))

// Every tool Deliberate offers
val DELIBERATE_TOOLS = listOf(
    ToolSpec(TOOL_FIND_EVENTS, "Find calendar events between two days (at most a year apart), optionally only those " +
        "whose title, place or notes contain some words. Returns each event's id and begin (needed to move or cancel " +
        "it), time, place and notes, plus what Deliberate's rules change for it.",
        objectOf(mapOf("from" to text("YYYY-MM-DD"), "to" to text("YYYY-MM-DD"),
            "words" to text("Optional words to look for")), listOf("from", "to"))),
    ToolSpec(TOOL_FIND_PLANS, "Find plans Datebook found in texts and emails (waiting for you, added, or ignored), " +
        "with who they came from and the message text when it was kept. Use it to answer what someone said.",
        objectOf(mapOf("status" to mapOf("type" to "string", "enum" to listOf("waiting", "added", "ignored", "any")),
            "words" to text("Optional words to look for in the title, sender, group or message")), listOf("status"))),
    ToolSpec(TOOL_FIND_ITEMS, "Find Datebook's lists: bills, assignments, packages, reminders, notes and loose plans " +
        "still to pin down, with their ids.",
        objectOf(mapOf("kind" to mapOf("type" to "string", "enum" to listOf("bills", "assignments", "packages",
            "reminders", "notes", "loose", "all")), "words" to text("Optional words to look for")), listOf("kind"))),
    ToolSpec(TOOL_SETTINGS, "Read Datebook's settings, rules (with ids), habits and pauses.", objectOf(emptyMap())),
    ToolSpec(TOOL_EXTRAS, "Read the weather forecast for the next days and the saved NJ Transit trip's next trains.",
        objectOf(emptyMap())),
    ToolSpec(TOOL_TOURNAMENTS, "Read the men's foil fencing tournaments Datebook found for the coming weeks (the " +
        "user follows and watches them): days, start times, today's stream times and stage, and Watch and Results " +
        "links.", objectOf(emptyMap())),
    ToolSpec(TOOL_ASK, "Ask the user a question with 2 to 4 short choices to tap (they can also type an answer). " +
        "Use it whenever the request is unclear, then wait for the answer.",
        objectOf(mapOf("question" to STRING, "choices" to mapOf("type" to "array", "items" to STRING)),
            listOf("question", "choices"))),
    ToolSpec(TOOL_PROPOSE, "Propose every change for this request at once. The app shows a preview with Confirm and " +
        "Cancel and makes the changes only when the user confirms; the result says what happened.",
        objectOf(mapOf("title" to text("A short heading, like \"New rule\" or \"Move practice\""),
            "summary" to text("One line saying what will change, like \"50 min travel to every soccer practice\""),
            "changes" to mapOf("type" to "array", "items" to CHANGE)), listOf("title", "summary", "changes"))),
)

// Deliberate's instructions, with today's date and time
fun deliberateSystem(now: ZonedDateTime): String {
    return """You are Deliberate, the assistant inside Datebook, an Android app that finds plans in the user's texts
and emails, keeps lists (bills, assignments, packages, reminders, notes) and sends leave-by alerts, an 8 AM summary,
evening heads-ups and a Friday week ahead. Today is ${now.format(TODAY)} (${now.zone.id}).

How to work:
- Look things up with the find tools before answering or changing anything. Never guess an id.
- Answer in a few short plain sentences, no markdown. Quote a message's text when asked what someone said.
- To change anything, call propose_changes once with every change. Nothing changes until the user confirms, so
  never say a change is done before its result says so. Every confirmed change can be undone.
- Datebook never writes calendar events itself. new_plan makes an "Add to calendar?" prompt; move_event and
  cancel_event make a prompt that opens the event in the calendar app. Say so in the summary.
- When the request is unclear (which event, which day, how long), call ask_choice with 2 to 4 short choices.
- Rules cover future events too. Pick title words that match exactly the events meant: check them with
  find_events, and ask when two kinds of event would match. "Plans like this one" means an ignore rule with words
  from that plan's title and its sender.
- travel_minutes replaces Google Maps' travel time for leave-by alerts; arrive_early adds minutes before the start;
  alert_earlier sends the alert sooner; no_alerts stops leave-by, bring and weather alerts for an event series.
- Habits are things Claude should assume when it reads new texts, like "dinners last 2 hours".
- Pauses end at the time given; "this weekend" ends Monday at 6:00.
- set_link saves a web link on a bill (Pay), a package (Order) or a trip booking (Booking).
- For anything outside Datebook's data (any fencing tournament, past or future, its results or stream, a venue,
  opening hours, news), search the web with web_search and read pages with web_fetch. Give the useful link in
  your answer.
- You can't send messages, email, pay anything, or change apps other than Datebook."""
}
