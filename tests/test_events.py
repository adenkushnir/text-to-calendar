# Tests for turning an event into calendar times, a summary and the calendar intent
from datetime import datetime, timezone
from t2c import events
DINNER = {"title": "Dinner at Joe's", "start": "2026-11-13T19:00", "end": "", "all_day": False, "location": "Joe's"}
DENTIST = {"title": "Dentist", "start": "2026-11-10T15:30", "end": "2026-11-10T16:30", "all_day": False, "location": ""}
PARTY = {"title": "Party", "start": "2026-11-14", "end": "", "all_day": True, "location": ""}
HOUR = 60 * 60 * 1000

# Times are read as local time on the phone
def test_to_millis_uses_local_time():
    assert events.to_millis("2026-11-13T19:00") == int(datetime(2026, 11, 13, 19, 0).timestamp() * 1000)

# A given end time is used as is
def test_event_times_with_end():
    begin, end = events.event_times(DENTIST)
    assert end - begin == HOUR

# With no end time, a timed event lasts one hour and an all-day event one day
def test_event_times_defaults():
    begin, end = events.event_times(DINNER)
    assert end - begin == HOUR
    begin, end = events.event_times(PARTY)
    assert end - begin == 24 * HOUR

# An end before the start is ignored
def test_event_times_end_before_start():
    begin, end = events.event_times(dict(DENTIST, end="2026-11-10T09:00"))
    assert end - begin == HOUR

# The intent opens the calendar insert screen with title, times, all-day flag and location
def test_insert_intent_args():
    args = events.insert_intent_args(DINNER)
    begin, end = events.event_times(DINNER)
    assert args[:2] == ["am", "start"]
    assert "android.intent.action.INSERT" in args
    assert args[args.index("title") + 1] == "Dinner at Joe's"
    assert args[args.index("beginTime") + 1] == str(begin)
    assert args[args.index("endTime") + 1] == str(end)
    assert args[args.index("allDay") + 1] == "false"
    assert args[args.index("eventLocation") + 1] == "Joe's"

# No location means no location argument, and all-day events say so
def test_insert_intent_all_day_without_location():
    args = events.insert_intent_args(PARTY)
    assert "eventLocation" not in args
    assert args[args.index("allDay") + 1] == "true"

# The summary shows title, day, time and location
def test_summary():
    assert events.summary(DINNER) == "Dinner at Joe's · Fri Nov 13, 7:00 PM · Joe's"
    assert events.summary(DENTIST) == "Dentist · Tue Nov 10, 3:30 PM"
    assert events.summary(PARTY) == "Party · Sat Nov 14"

# A valid repeat rule is passed to the calendar and shown in the summary
def test_repeat_rule_in_intent_and_summary():
    standup = {"title": "Standup", "start": "2026-11-16T09:00", "end": "", "all_day": False, "location": "",
               "repeat": "FREQ=WEEKLY;BYDAY=MO"}
    args = events.insert_intent_args(standup)
    assert args[args.index("rrule") + 1] == "FREQ=WEEKLY;BYDAY=MO"
    assert events.summary(standup) == "Standup · Mon Nov 16, 9:00 AM · repeats weekly"

# Repeat rules are cleaned up, and anything that isn't a rule is dropped
def test_repeat_rule_cleanup():
    assert events.repeat_rule({"repeat": "rrule:freq=monthly;bymonthday=1"}) == "FREQ=MONTHLY;BYMONTHDAY=1"
    assert events.repeat_rule({"repeat": "every monday"}) == ""
    assert events.repeat_rule({"repeat": ""}) == ""
    assert "rrule" not in events.insert_intent_args(DINNER)

# Repeat labels read naturally
def test_repeat_labels():
    assert events.repeat_label("FREQ=DAILY") == "repeats daily"
    assert events.repeat_label("FREQ=MONTHLY;BYMONTHDAY=1") == "repeats monthly"
    assert events.repeat_label("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR") == "repeats every 2 weeks"

# One-off events in the past count as started, future ones don't, and an all-day event counts for its whole day
def test_has_started():
    dinner_start = events.to_millis(DINNER["start"])
    assert events.has_started(DINNER, dinner_start - 1) is False
    assert events.has_started(DINNER, dinner_start) is True
    party_start = events.to_millis(PARTY["start"])
    assert events.has_started(PARTY, party_start + 12 * HOUR) is False
    assert events.has_started(PARTY, party_start + 24 * HOUR) is True

# Repeating events and unreadable starts are never treated as started
def test_has_started_repeating_or_unreadable():
    weekly = dict(DINNER, repeat="FREQ=WEEKLY;BYDAY=FR")
    assert events.has_started(weekly, events.to_millis(DINNER["start"]) + 100 * 24 * HOUR) is False
    assert events.has_started(dict(DINNER, start="someday"), 0) is False

# A zone named in the text is converted to the phone's time, with the original shown in brackets
def test_zone_in_text():
    call = {"title": "Call", "start": "2026-11-10T15:00", "end": "", "all_day": False, "location": "",
            "timezone": "America/Los_Angeles", "phone_timezone": "America/New_York"}
    begin, end = events.event_times(call)
    assert begin == int(datetime(2026, 11, 10, 23, 0, tzinfo=timezone.utc).timestamp() * 1000)
    assert events.summary(call) == "Call · Tue Nov 10, 6:00 PM (3:00 PM PST)"
    args = events.insert_intent_args(call)
    assert args[args.index("eventTimezone") + 1] == "America/Los_Angeles"

# When travelling, a plain time means the phone's current zone, with no brackets
def test_travelling_uses_phone_zone():
    dinner = dict(DINNER, timezone="", phone_timezone="Europe/London")
    begin, end = events.event_times(dinner)
    assert begin == int(datetime(2026, 11, 13, 19, 0, tzinfo=timezone.utc).timestamp() * 1000)
    assert events.summary(dinner) == "Dinner at Joe's · Fri Nov 13, 7:00 PM · Joe's"

# A flight landing in another zone shows in the phone's time, even when the date changes
def test_flight_arrival_zone():
    flight = {"title": "Pick up Mom", "start": "2026-12-18T06:40", "end": "", "all_day": False, "location": "LAX",
              "timezone": "America/Los_Angeles", "phone_timezone": "America/New_York"}
    assert events.summary(flight) == "Pick up Mom · Fri Dec 18, 9:40 AM (6:40 AM PST) · LAX"
    late = dict(flight, start="2026-12-18T22:30")
    assert events.summary(late).startswith("Pick up Mom · Sat Dec 19, 1:30 AM (10:30 PM PST)")

# Unknown zone names fall back to the phone's zone, and all-day events ignore the text's zone
def test_zone_fallbacks():
    assert events.zone_info("Mars/Base") is None
    assert events.zone_info("") is None
    odd = {"start": "2026-11-13T19:00", "timezone": "Mars/Base", "phone_timezone": "America/New_York"}
    assert str(events.event_zone(odd)) == "America/New_York"
    party = dict(PARTY, timezone="Asia/Tokyo", phone_timezone="America/New_York")
    assert str(events.event_zone(party)) == "America/New_York"
    assert "eventTimezone" not in events.insert_intent_args(party)
