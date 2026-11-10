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
    assert main.describe(fake_client(messages), {"content": "lol ok"}) == (None, "no date", False)
    assert messages.calls == []

# Without an API key the date is found but nothing is sent
def test_describe_no_client():
    assert main.describe(None, GOOGLE) == (None, "date found, no API key", False)

# A found event is described with its title, start and location
def test_describe_event():
    event, result, retry = main.describe(fake_client(FakeMessages(response=reply(EVENT))), GOOGLE)
    assert event == EVENT
    assert result == "event: Dinner with Sam | 2026-11-13T19:00 to ? | Joe's"
    assert retry is False

# A reply with no event says so
def test_describe_no_event():
    result = main.describe(fake_client(FakeMessages(response=reply(NO_EVENT))), GOOGLE)
    assert result == (None, "date found, no event", False)

# A rejected API key is reported instead of crashing
def test_describe_bad_key():
    error = anthropic.AuthenticationError("bad key", response=httpx2.Response(401, request=REQUEST), body=None)
    result = main.describe(fake_client(FakeMessages(error=error)), GOOGLE)
    assert result == (None, "date found, API key rejected (401): bad key", False)

# No internet is reported instead of crashing
def test_describe_no_connection():
    error = anthropic.APIConnectionError(request=REQUEST)
    assert main.describe(fake_client(FakeMessages(error=error)), GOOGLE) == (None, "date found, no connection", True)

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
    event, result, retry = main.describe(fake_client(FakeMessages(response=reply(standup))), GOOGLE)
    assert result == "event: Standup | 2026-11-16T09:00 to ? | no location | repeat FREQ=WEEKLY;BYDAY=MO"

# The running notification shows the time of the last check and how often it checks
def test_status_text():
    assert main.status_text(datetime(2026, 11, 9, 21, 5), 5) == "Last checked 9:05 PM · every 5 s"
    assert main.status_text(datetime(2026, 11, 9, 0, 30), 30.0) == "Last checked 12:30 AM · every 30 s"

# Checks are fast after a recent text and slow once it has been quiet for the idle time
def test_poll_interval(monkeypatch):
    monkeypatch.setattr(main, "POLL_SECONDS", 5)
    monkeypatch.setattr(main, "SLOW_POLL_SECONDS", 30)
    monkeypatch.setattr(main, "IDLE_SECONDS", 600)
    assert main.poll_interval(0) == 5
    assert main.poll_interval(599) == 5
    assert main.poll_interval(600) == 30

# Settings come from the environment, with the default used when missing, invalid, zero or negative
def test_read_setting(monkeypatch):
    monkeypatch.setenv("T2C_TEST_SETTING", "12")
    assert main.read_setting("T2C_TEST_SETTING", 5) == 12
    monkeypatch.setenv("T2C_TEST_SETTING", "fast")
    assert main.read_setting("T2C_TEST_SETTING", 5) == 5
    monkeypatch.setenv("T2C_TEST_SETTING", "0")
    assert main.read_setting("T2C_TEST_SETTING", 5) == 5
    monkeypatch.delenv("T2C_TEST_SETTING")
    assert main.read_setting("T2C_TEST_SETTING", 5) == 5

# Server errors are worth retrying, but other API errors like a bad request are not
def test_describe_retry_only_server_errors():
    server_error = anthropic.InternalServerError("down", response=httpx2.Response(500, request=REQUEST), body=None)
    assert main.describe(fake_client(FakeMessages(error=server_error)), GOOGLE)[2] is True
    bad_request = anthropic.BadRequestError("no credit", response=httpx2.Response(400, request=REQUEST), body=None)
    assert main.describe(fake_client(FakeMessages(error=bad_request)), GOOGLE)[2] is False

# A message that fails with no internet is saved, then offered once the connection is back
def test_retry_after_no_connection(monkeypatch):
    shown = []
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: shown.append(args))
    connection = store.open_store(":memory:")
    offline = FakeMessages(error=anthropic.APIConnectionError(request=REQUEST))
    main.handle(fake_client(offline), connection, GOOGLE)
    assert shown == []
    assert len(store.due_retries(connection, float("inf"))) == 1
    online = fake_client(FakeMessages(response=reply(EVENT)))
    main.retry_waiting(online, connection, float("inf"))
    assert shown[0][1] == "Add to calendar?"
    assert store.due_retries(connection, float("inf")) == []

# A saved message is not retried before it is due, and is rescheduled while still offline
def test_retry_waits_and_reschedules():
    connection = store.open_store(":memory:")
    store.save_retry(connection, GOOGLE, 1000, 1060)
    offline = fake_client(FakeMessages(error=anthropic.APIConnectionError(request=REQUEST)))
    main.retry_waiting(offline, connection, 1030)
    assert offline.beta.messages.calls == []
    main.retry_waiting(offline, connection, 1060)
    assert len(offline.beta.messages.calls) == 1
    assert store.due_retries(connection, 1060) == []
    assert len(store.due_retries(connection, 1060 + main.RETRY_SECONDS)) == 1

# A message still failing after GIVE_UP_SECONDS is dropped
def test_retry_gives_up(capsys):
    connection = store.open_store(":memory:")
    store.save_retry(connection, GOOGLE, 0, 0)
    offline = fake_client(FakeMessages(error=anthropic.APIConnectionError(request=REQUEST)))
    main.retry_waiting(offline, connection, main.GIVE_UP_SECONDS + 1)
    assert store.due_retries(connection, float("inf")) == []
    assert "gave up (date found, no connection)" in capsys.readouterr().out
