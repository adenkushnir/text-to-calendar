# Turns an extracted event into calendar times, a short summary, and the Android intent for a prefilled event
from datetime import datetime, timedelta
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

# Build the am command that opens Google Calendar's new-event screen with the event filled in
def insert_intent_args(event):
    begin, end = event_times(event)
    all_day = "true" if event.get("all_day") else "false"  # am needs true or false as text
    args = CALENDAR_INSERT + ["--es", "title", event.get("title") or "Event", "--el", "beginTime", str(begin),
                              "--el", "endTime", str(end), "--ez", "allDay", all_day]

    # Add the location only when there is one
    if event.get("location"):
        args += ["--es", "eventLocation", event["location"]]
    return args

# Describe the event in one line for the notification, like: Dinner at Joe's · Fri Nov 13, 7:00 PM · Joe's
def summary(event):
    start = datetime.fromisoformat(event["start"])
    when = f"{start:%a %b} {start.day}"

    # Add the time unless it is an all-day event
    if not event.get("all_day"):
        when += f", {start.hour % 12 or 12}:{start:%M %p}"
    parts = [event.get("title") or "Event", when, event.get("location")]
    return " · ".join(part for part in parts if part)  # skip empty parts
