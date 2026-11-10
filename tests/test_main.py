# Tests for the main loop: filtering, de-duplicating, describing and suggesting messages
from datetime import datetime
import anthropic
import httpx2
from t2c import main, store, termux
from tests.fakes import EVENT, NO_EVENT, FakeMessages, fake_client, reply
REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
SAMSUNG = {"packageName": "com.samsung.android.messaging", "title": "Mom", "content": "lunch tomorrow?", "when": "1"}
GOOGLE = {"packageName": "com.google.android.apps.messaging", "title": "Sam", "content": "dinner fri 7pm", "when": "2"}
OTHER_APP = {"packageName": "com.whatsapp", "title": "Group", "content": "party sat", "when": "3"}
NO_TEXT = {"packageName": "com.google.android.apps.messaging", "title": "Sam", "content": "", "when": "4"}

# Only notifications from the two messaging apps with text are kept
def test_filters_to_messaging_apps():
    assert main.message_notifications([SAMSUNG, GOOGLE, OTHER_APP, NO_TEXT]) == [SAMSUNG, GOOGLE]

# The same notification is only returned the first time it is seen
def test_check_once_skips_seen(monkeypatch):
    monkeypatch.setattr(termux, "list_notifications", lambda: [SAMSUNG, GOOGLE, OTHER_APP])
    connection = store.open_store(":memory:")
    assert main.check_once(connection) == [SAMSUNG, GOOGLE]
    assert main.check_once(connection) == []

# Texts with no date are never sent to Claude
def test_describe_no_date():
    messages = FakeMessages(response=reply(EVENT))
    assert main.describe(fake_client(messages), {"content": "lol ok"}) == (None, "no date")
    assert messages.calls == []

# Without an API key the date is found but nothing is sent
def test_describe_no_client():
    assert main.describe(None, GOOGLE) == (None, "date found, no API key")

# A found event is described with its title, start and location
def test_describe_event():
    event, result = main.describe(fake_client(FakeMessages(response=reply(EVENT))), GOOGLE)
    assert event == EVENT
    assert result == "event: Dinner with Sam | 2026-11-13T19:00 to ? | Joe's"

# A reply with no event says so
def test_describe_no_event():
    result = main.describe(fake_client(FakeMessages(response=reply(NO_EVENT))), GOOGLE)
    assert result == (None, "date found, no event")

# A rejected API key is reported instead of crashing
def test_describe_bad_key():
    error = anthropic.AuthenticationError("bad key", response=httpx2.Response(401, request=REQUEST), body=None)
    result = main.describe(fake_client(FakeMessages(error=error)), GOOGLE)
    assert result == (None, "date found, API key rejected (401): bad key")

# No internet is reported instead of crashing
def test_describe_no_connection():
    error = anthropic.APIConnectionError(request=REQUEST)
    assert main.describe(fake_client(FakeMessages(error=error)), GOOGLE) == (None, "date found, no connection")

# A button command runs the action module with the id, from the repo folder
def test_action_command():
    command = main.action_command("add", 7)
    assert command.startswith("cd ")
    assert command.endswith("-m t2c.action add 7")
    assert "T2C_DB=" in command

# A found event is saved and shown with Add and Ignore buttons
def test_suggest_shows_notification(monkeypatch):
    shown = []
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: shown.append((args, kwargs)))
    connection = store.open_store(":memory:")
    main.suggest(connection, EVENT)
    args, kwargs = shown[0]
    assert args[0] == "t2c-1"
    assert args[1] == "Add to calendar?"
    assert args[2] == "Dinner with Sam · Fri Nov 13, 7:00 PM · Joe's"
    assert [label for label, action in kwargs["buttons"]] == ["Add", "Ignore"]  # button labels
    assert store.get_pending(connection, 1) == EVENT

# Stand-in for termux.notify that fails like a missing Termux command
def failing_notify(*args, **kwargs):
    raise OSError("no termux")

# A failing notification is printed instead of stopping the loop
def test_handle_survives_notify_failure(monkeypatch, capsys):
    monkeypatch.setattr(termux, "notify", failing_notify)
    main.handle(fake_client(FakeMessages(response=reply(EVENT))), store.open_store(":memory:"), GOOGLE)
    assert "could not show notification: no termux" in capsys.readouterr().out

# A repeating event shows its rule in the log line
def test_describe_repeating_event():
    standup = dict(EVENT, title="Standup", start="2026-11-16T09:00", location="", repeat="FREQ=WEEKLY;BYDAY=MO")
    event, result = main.describe(fake_client(FakeMessages(response=reply(standup))), GOOGLE)
    assert result == "event: Standup | 2026-11-16T09:00 to ? | no location | repeat FREQ=WEEKLY;BYDAY=MO"

# The running notification shows the time of the last check
def test_status_text():
    assert main.status_text(datetime(2026, 11, 9, 21, 5)) == "Last checked 9:05 PM"
    assert main.status_text(datetime(2026, 11, 9, 0, 30)) == "Last checked 12:30 AM"
