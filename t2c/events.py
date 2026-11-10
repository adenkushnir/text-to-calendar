# Turns an extracted event into calendar times, a short summary, and the Android intent for a prefilled event
import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
REPEAT_RULE = re.compile(r"FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;[A-Z]+=[A-Z0-9,+-]+)*")
DAY_MILLIS = 24 * 60 * 60 * 1000
REPEAT_UNITS = {"DAILY": "day", "WEEKLY": "week", "MONTHLY": "month", "YEARLY": "year"}
CALENDAR_INSERT = ["am", "start", "--user", "0", "-a", "android.intent.action.INSERT",
                   "-d", "content://com.android.calendar/events"]

# Return the zone for an IANA name like America/Los_Angeles, or None (the phone's local time) if empty or unknown
def zone_info(name):
    # Treat a missing or made-up zone name as local time
    try:
        return ZoneInfo(name) if name else None
    except (ZoneInfoNotFoundError, ValueError):  # not a real zone name
        return None

# Return the zone the event's times are written in: the zone named in the text, else the phone's zone at the time
def event_zone(event):
    # All-day events are plain dates on the phone's calendar
    if event.get("all_day"):
        return zone_info(event.get("phone_timezone"))
    return zone_info(event.get("timezone")) or zone_info(event.get("phone_timezone"))

# Convert a YYYY-MM-DD or YYYY-MM-DDTHH:MM time in the given zone (None means local time) to milliseconds since 1970
def to_millis(text, zone=None):
    moment = datetime.fromisoformat(text)
    moment = moment.replace(tzinfo=zone) if zone else moment  # times without a zone are local
    return int(moment.timestamp() * 1000)

# Return the start and end in milliseconds, using one hour (or one day) when there is no usable end
def event_times(event):
    zone = event_zone(event)
    begin = to_millis(event["start"], zone)

    # Use the given end when it comes after the start
    if event.get("end"):
        end = to_millis(event["end"], zone)
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
        begin = to_millis(event["start"], event_zone(event))
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

    # Tell the calendar which zone a timed event belongs to, when it is known
    if not event.get("all_day") and event_zone(event):
        args += ["--es", "eventTimezone", str(event_zone(event))]
    return args

# Format a time like 7:00 PM
def clock(moment):
    return f"{moment.hour % 12 or 12}:{moment:%M %p}"

# Describe the event in one line in the phone's time, like: Call · Tue Nov 10, 6:00 PM (3:00 PM PST) · repeats weekly
def summary(event):
    start = datetime.fromisoformat(event["start"])
    when = f"{start:%a %b} {start.day}"

    # Add the time unless it is an all-day event, converted to the phone's time
    if not event.get("all_day"):
        zone = event_zone(event)
        written = start.replace(tzinfo=zone) if zone else start.astimezone()  # the time as the text gave it
        local = written.astimezone(zone_info(event.get("phone_timezone")))  # the same moment in the phone's time
        when = f"{local:%a %b} {local.day}, {clock(local)}"

        # Show the original time too when the text used a different zone
        if written.utcoffset() != local.utcoffset():
            when += f" ({clock(written)} {written:%Z})"
    rule = repeat_rule(event)
    parts = [event.get("title") or "Event", when, event.get("location"), repeat_label(rule) if rule else ""]
    return " · ".join(part for part in parts if part)  # skip empty parts
