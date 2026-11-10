# Tests for turning an event into calendar times, a summary and the calendar intent
from datetime import datetime
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
