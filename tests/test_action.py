# Tests for the Add and Ignore buttons
from t2c import action, store, termux
EVENT = {"title": "Dinner", "start": "2026-11-13T19:00", "end": "", "all_day": False, "location": ""}

# Record calls to the Termux wrappers instead of running them
def fake_termux(monkeypatch, start_error=None):
    calls = {"removed": [], "started": [], "notified": []}

    # Record an activity start, or fail like Android blocking it
    def start_activity(args):
        calls["started"].append(args)

        # Simulate am refusing to open the calendar
        if start_error:
            raise start_error
    monkeypatch.setattr(termux, "remove_notification", calls["removed"].append)
    monkeypatch.setattr(termux, "start_activity", start_activity)
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: calls["notified"].append(args))
    return calls

# Add opens the calendar, removes the notification and forgets the event
def test_add_opens_calendar(monkeypatch):
    calls = fake_termux(monkeypatch)
    connection = store.open_store(":memory:")
    pending_id = store.save_pending(connection, EVENT)
    action.handle(connection, "add", pending_id)
    assert calls["removed"] == ["t2c-1"]
    assert "android.intent.action.INSERT" in calls["started"][0]
    assert store.get_pending(connection, pending_id) is None

# Ignore removes the notification without opening anything
def test_ignore_does_not_open(monkeypatch):
    calls = fake_termux(monkeypatch)
    connection = store.open_store(":memory:")
    pending_id = store.save_pending(connection, EVENT)
    action.handle(connection, "ignore", pending_id)
    assert calls["removed"] == ["t2c-1"]
    assert calls["started"] == []
    assert store.get_pending(connection, pending_id) is None

# Tapping Add twice only opens the calendar once
def test_add_twice(monkeypatch):
    calls = fake_termux(monkeypatch)
    connection = store.open_store(":memory:")
    pending_id = store.save_pending(connection, EVENT)
    action.handle(connection, "add", pending_id)
    action.handle(connection, "add", pending_id)
    assert len(calls["started"]) == 1

# If Android blocks the calendar, a notification explains why
def test_add_blocked_shows_error(monkeypatch):
    calls = fake_termux(monkeypatch, start_error=RuntimeError("Error: Activity not started"))
    connection = store.open_store(":memory:")
    action.handle(connection, "add", store.save_pending(connection, EVENT))
    assert calls["notified"][0][1] == "Could not open Google Calendar"
    assert "Activity not started" in calls["notified"][0][2]

# Bad command lines are rejected
def test_run_rejects_bad_arguments():
    assert action.run(["action", "delete", "1"]) == 2
    assert action.run(["action", "add", "abc"]) == 2
    assert action.run(["action", "add"]) == 2
