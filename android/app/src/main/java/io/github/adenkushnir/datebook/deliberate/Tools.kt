// The tools Deliberate gives Claude: look things up (calendar, plans and their messages, lists, settings, weather and
// trains, live flights), ask you a question with choices, and propose changes for you to confirm; plus Deliberate's
// instructions
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
const val TOOL_FIND_FLIGHT = "find_flight"
const val TOOL_FIND_JOURNEY = "find_journey"
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
const val ACT_SET_PLAN_TRAVEL = "set_plan_travel"
const val ACT_EDIT_EVENT = "edit_event"
const val TARGET_PLAN = "plan"
const val TARGET_ITEM = "item"
const val TARGET_EVENT = "event"
const val MAX_EARLY_MINUTES = 180
val TRAVEL_CHOICES = listOf("", "DRIVE", "TRANSIT", "WALK", "BICYCLE")
const val SET_SUMMARY_TIME = "summary_time"
const val SET_SUMMARY_ON = "summary_on"
const val SET_HOME = "home_address"
const val SET_TRAVEL_MODE = "travel_mode"
const val SET_FLIP_TRIP = "flip_train_trip"
const val SET_KEEP_TEXTS = "keep_texts"
val ACTIONS = listOf(ACT_NEW_PLAN, ACT_EDIT_WAITING, ACT_ADD_WAITING, ACT_IGNORE_WAITING, ACT_MOVE_EVENT,
    ACT_CANCEL_EVENT, ACT_ADD_BILL, ACT_ADD_ASSIGNMENT, ACT_ADD_REMINDER, ACT_ADD_NOTE, ACT_ADD_PACKAGE, ACT_EDIT_ITEM,
    ACT_MARK_DONE, ACT_DELETE_ITEM, ACT_PACKAGE_RECEIVED, ACT_ADD_RULE, ACT_TURN_RULE, ACT_DELETE_RULE, ACT_PAUSE,
    ACT_SETTING, ACT_SET_LINK, ACT_SET_PLAN_TRAVEL, ACT_EDIT_EVENT)
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
    "begin" to mapOf("type" to "integer", "description" to "For move_event, edit_event and cancel_event: the " +
        "event's begin (milliseconds) from find_events"),
    "title" to STRING,
    "date" to text("YYYY-MM-DD"),
    "time" to text("HH:MM, 24-hour; empty for all day"),
    "end_date" to text("YYYY-MM-DD"),
    "end_time" to text("HH:MM, 24-hour"),
    "place" to STRING,
    "amount" to text("For bills, like \$45"),
    "repeat" to text("An RRULE like FREQ=MONTHLY or FREQ=WEEKLY;BYDAY=MO; empty for none"),
    "text" to text("A note's text; for edit_event, lines to add to the event's notes"),
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
    "target" to mapOf("type" to "string", "enum" to listOf(TARGET_PLAN, TARGET_ITEM, TARGET_EVENT), "description" to
        "For set_link: plan for a package or trip booking (from find_plans or find_items' packages), item for a bill " +
        "or other list item. For set_plan_travel: plan for a plan from find_plans, event for a calendar event (with " +
        "its begin)"),
    "link" to text("For set_link: the web link to save, or empty to remove it"),
    "flight" to text("For new_plan: the flight number, airline code and number with no space, like B61234; Datebook " +
        "fills in the title, times and airport from live data"),
    "to" to text("For new_plan with a flight: where the leg the user means lands, as its airport code (like BOS), " +
        "when the number flies more than one leg that day"),
    "travel" to mapOf("type" to "string", "enum" to TRAVEL_CHOICES, "description" to "For new_plan and " +
        "set_plan_travel: how the user gets there, for this plan only (TRANSIT means NJ Transit trains); empty to " +
        "leave it"),
    "early" to mapOf("type" to "integer", "description" to "For new_plan and set_plan_travel: minutes early to be " +
        "there, for this plan only (0 to $MAX_EARLY_MINUTES); leave it out to keep the default"),
    "station" to text("For new_plan and set_plan_travel: the NJ Transit station to leave from, for this plan only"),
    "boarding" to text("For a flight (new_plan, edit_event or set_plan_travel): the boarding time the user gives, " +
        "HH:MM in 24-hour time"),
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
    ToolSpec(TOOL_FIND_FLIGHT, "Look up a flight live by number and departure date: every leg it flies that day, " +
        "each with its airports and countries, scheduled and current times, terminal, gate and status, and how " +
        "early Datebook aims to get the user there. Says when it isn't found.",
        objectOf(mapOf("flight" to text("Airline code and number, like B61234 or UA 1234"),
            "date" to text("YYYY-MM-DD, the local departure day")), listOf("flight", "date"))),
    ToolSpec(TOOL_FIND_JOURNEY, "Plan how to get to a place, step by step: walks, each train or bus (line, where it's " +
        "headed, the stops to get on and off at, and when), transfers, or a drive, leaving at a time (now when not " +
        "given) or arriving by one. The user sees the journey as a card with every step and an Add as a plan button, " +
        "so answer in a line or two instead of listing the steps again.",
        objectOf(mapOf("to" to text("The place, as an address or a name Google Maps knows"),
            "mode" to mapOf("type" to "string", "enum" to listOf("TRANSIT", "WALK", "DRIVE")),
            "only" to mapOf("type" to "string", "enum" to listOf("", "TRAIN", "BUS"), "description" to "For transit: " +
                "trains only or buses only, when the user asks"),
            "leave_at" to text("YYYY-MM-DDTHH:MM, when leaving (empty for now)"),
            "arrive_by" to text("YYYY-MM-DDTHH:MM, when to be there (empty when leaving at a time or now)"),
            "title" to text("What the trip is for, like \"Dinner with Sam\" (empty: the place)")),
            listOf("to", "mode"))),
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
- Once the user confirms, Datebook writes to their calendar itself and opens the event in their calendar app:
  new_plan adds the event, move_event changes its time, and edit_event changes an existing event's title, day and
  time, place or notes (or, with a flight number, fills in that flight's live details). cancel_event makes a prompt
  that opens the event for the user to delete. Repeating events can't be changed.
- Before new_plan, check find_events around that day. When the plan is already on the calendar (like "Flight to
  Boston" when the user gives the flight number), use edit_event on that event instead of making a second one.
- When the request is unclear (which event, which day, how long), call ask_choice with 2 to 4 short choices.
- Rules cover future events too. Pick title words that match exactly the events meant: check them with
  find_events, and ask when two kinds of event would match. "Plans like this one" means an ignore rule with words
  from that plan's title and its sender.
- travel_minutes replaces Google Maps' travel time for leave-by alerts; arrive_early adds minutes before the start;
  alert_earlier sends the alert sooner; no_alerts stops leave-by, bring and weather alerts for an event series.
- Habits are things Claude should assume when it reads new texts, like "dinners last 2 hours".
- Pauses end at the time given; "this weekend" ends Monday at 6:00.
- set_link saves a web link on a bill (Pay), a package (Order) or a trip booking (Booking).
- Flights: call find_flight first. When it's found, make the plan with new_plan and its flight number (Datebook
  fills in the title, times and airport), the date it leaves. One number can fly several legs a day: when
  find_flight lists more than one and the user hasn't said where they're going, ask which with ask_choice, one
  choice per leg ("Newark → Boston, 5:40 PM"); then give that leg's arrival code in new_plan's to. When it isn't found, say so and ask for the departure
  time and airport with ask_choice, then make it with new_plan with the flight number, date and time anyway. Check
  find_plans for the same flight number on that day first: if there is one, change it with set_plan_travel instead
  of making a second plan. For flights, early counts from boarding (about 35 minutes before departure, 45 abroad):
  Datebook aims for 30 minutes before boarding (75 abroad) unless the user says otherwise; "30 minutes early" for a
  flight means 30 minutes before boarding.
- "How do I get to X", "plan a trip to X by bus", "what would getting there by 5 look like": call find_journey.
  If the user then wants it, they tap Add as a plan on the card (or ask you, and you make the new_plan with its
  travel).
- "My boarding time is 3:38": set boarding (15:38) on set_plan_travel for that flight's plan (or on new_plan /
  edit_event); it replaces the usual guess of 35 minutes before departure.
- "I'm taking the train", "drive me", "be there 30 minutes early" for one plan: put travel, early and station on the
  new_plan, or use set_plan_travel for a plan or calendar event that exists. Use rules only when the user means
  every plan like it.
- For anything outside Datebook's data (any fencing tournament, past or future, its results or stream, a venue,
  opening hours, news), search the web with web_search and read pages with web_fetch. Give the useful link in
  your answer.
- You can't send messages, email, pay anything, or change apps other than Datebook."""
}
