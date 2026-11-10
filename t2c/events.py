# Turns an extracted event into calendar times, a short summary, and the Android intent for a prefilled event
import re
from datetime import datetime, timedelta
REPEAT_RULE = re.compile(r"FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;[A-Z]+=[A-Z0-9,+-]+)*")
DAY_MILLIS = 24 * 60 * 60 * 1000
REPEAT_UNITS = {"DAILY": "day", "WEEKLY": "week", "MONTHLY": "month", "YEARLY": "year"}
CALENDAR_INSERT = ["am", "start", "--user", "0", "-a", "android.intent.action.INSERT",
                   "-d", "content://com.android.calendar/events"]

# Convert a local YYYY-MM-DD or YYYY-MM-DDTHH:MM time to milliseconds since 1970
def to_millis(text):
    return int(datetime.fromisoformat(text).timestamp() * 1000)

# Return the start and end in milliseconds, using one hour (or one day) when there is no usable end
def event_times(event):
    begin = to_millis(event["start"])

    # Use the given end when it comes after the start
    if event.get("end"):
        end = to_millis(event["end"])
        if end > begin:  # ignore ends before the start
            return begin, end
    length = timedelta(days=1) if event.get("all_day") else timedelta(hours=1)  # default length
    return begin, begin + int(length.total_seconds() * 1000)

# Return the event's repeat rule if it is a valid one, like FREQ=WEEKLY;BYDAY=MO, or an empty string
def repeat_rule(event):
    rule = (event.get("repeat") or "").strip().upper().removeprefix("RRULE:")
    return rule if REPEAT_RULE.fullmatch(rule) else ""  # drop anything that doesn't look like a rule

# Describe a repeat rule in words, like "repeats weekly" or "repeats every 2 weeks"
def repeat_label(rule):
    parts = dict(part.split("=", 1) for part in rule.split(";"))  # split each NAME=VALUE pair
    unit, interval = REPEAT_UNITS[parts["FREQ"]], parts.get("INTERVAL", "1")

    # Say "every N weeks" when it skips some
    if interval != "1":
        return f"repeats every {interval} {unit}s"
    return "repeats daily" if unit == "day" else f"repeats {unit}ly"  # daily, weekly, monthly, yearly

# Return True if a one-off event has already started (an all-day event counts from midnight, so today still counts)
def has_started(event, now_millis):
    # Repeating events still have future dates, so they are never too late
    if repeat_rule(event):
        return False

    # Compare the start with now, treating a start that can't be read as not started
    try:
        begin = to_millis(event["start"])
    except (KeyError, ValueError):  # missing or unreadable start
        return False
    return begin + DAY_MILLIS <= now_millis if event.get("all_day") else begin <= now_millis  # all-day lasts the day

# Build the am command that opens Google Calendar's new-event screen with the event filled in
def insert_intent_args(event):
    begin, end = event_times(event)
    all_day = "true" if event.get("all_day") else "false"  # am needs true or false as text
    args = CALENDAR_INSERT + ["--es", "title", event.get("title") or "Event", "--el", "beginTime", str(begin),
                              "--el", "endTime", str(end), "--ez", "allDay", all_day]

    # Add the location only when there is one
    if event.get("location"):
        args += ["--es", "eventLocation", event["location"]]

    # Add the repeat rule only when there is a valid one
    if repeat_rule(event):
        args += ["--es", "rrule", repeat_rule(event)]
    return args

# Describe the event in one line for the notification, like: Standup · Mon Nov 16, 9:00 AM · repeats weekly
def summary(event):
    start = datetime.fromisoformat(event["start"])
    when = f"{start:%a %b} {start.day}"

    # Add the time unless it is an all-day event
    if not event.get("all_day"):
        when += f", {start.hour % 12 or 12}:{start:%M %p}"
    rule = repeat_rule(event)
    parts = [event.get("title") or "Event", when, event.get("location"), repeat_label(rule) if rule else ""]
    return " · ".join(part for part in parts if part)  # skip empty parts
