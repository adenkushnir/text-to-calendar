# Tests for the main loop: filtering, de-duplicating, describing and suggesting messages
import subprocess
from datetime import datetime
import anthropic
import httpx2
from t2c import main, store, termux
from tests.fakes import EVENT, MOVIE, FakeMessages, fake_client, reply
REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
SAMSUNG = {"packageName": "com.samsung.android.messaging", "title": "Mom", "content": "lunch tomorrow?", "when": "1"}
GOOGLE = {"packageName": "com.google.android.apps.messaging", "title": "Sam", "content": "dinner fri 7pm", "when": "2"}
OTHER_APP = {"packageName": "com.whatsapp", "title": "Group", "content": "party sat", "when": "3"}
NO_TEXT = {"packageName": "com.google.android.apps.messaging", "title": "Sam", "content": "", "when": "4"}
NO_SENDER = {"packageName": "com.google.android.apps.messaging", "title": "",
             "content": "Messages is doing work in the background", "when": "5"}

# Only notifications from the two messaging apps with a sender and text are kept
def test_filters_to_messaging_apps():
    assert main.message_notifications([SAMSUNG, GOOGLE, OTHER_APP, NO_TEXT, NO_SENDER]) == [SAMSUNG, GOOGLE]

# The same notification is only returned the first time it is seen
def test_check_once_skips_seen(monkeypatch):
    monkeypatch.setattr(termux, "list_notifications", lambda: [SAMSUNG, GOOGLE, OTHER_APP])
    connection = store.open_store(":memory:")
    assert main.check_once(connection) == [SAMSUNG, GOOGLE]
    assert main.check_once(connection) == []

# Texts with no date are never sent to Claude
def test_describe_no_date():
    messages = FakeMessages(response=reply([EVENT]))
    assert main.describe(fake_client(messages), {"content": "lol ok"}) == ([], ["no date"], False)
    assert messages.calls == []

# Without an API key the date is found but nothing is sent
def test_describe_no_client():
    assert main.describe(None, GOOGLE) == ([], ["date found, no API key"], False)

# A found event is described with its title, start and location
def test_describe_event():
    found, lines, retry = main.describe(fake_client(FakeMessages(response=reply([EVENT]))), GOOGLE)
    assert found == [dict(EVENT, phone_timezone=termux.phone_timezone())]
    assert lines == ["event: Dinner with Sam | 2037-11-13T19:00 to ? | Joe's"]
    assert retry is False

# A reply with no event says so
def test_describe_no_event():
    result = main.describe(fake_client(FakeMessages(response=reply([]))), GOOGLE)
    assert result == ([], ["date found, no event"], False)

# A rejected API key is reported instead of crashing
def test_describe_bad_key():
    error = anthropic.AuthenticationError("bad key", response=httpx2.Response(401, request=REQUEST), body=None)
    result = main.describe(fake_client(FakeMessages(error=error)), GOOGLE)
    assert result == ([], ["date found, API key rejected (401): bad key"], False)

# No internet is reported instead of crashing
def test_describe_no_connection():
    error = anthropic.APIConnectionError(request=REQUEST)
    assert main.describe(fake_client(FakeMessages(error=error)), GOOGLE) == ([], ["date found, no connection"], True)

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
    main.handle(fake_client(FakeMessages(response=reply([EVENT]))), store.open_store(":memory:"), GOOGLE)
    assert "could not show notification: no termux" in capsys.readouterr().out

# A repeating event shows its rule in the log line
def test_describe_repeating_event():
    standup = dict(EVENT, title="Standup", start="2037-11-16T09:00", location="", repeat="FREQ=WEEKLY;BYDAY=MO")
    found, lines, retry = main.describe(fake_client(FakeMessages(response=reply([standup]))), GOOGLE)
    assert lines == ["event: Standup | 2037-11-16T09:00 to ? | no location | repeat FREQ=WEEKLY;BYDAY=MO"]

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
    online = fake_client(FakeMessages(response=reply([EVENT])))
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

# An event that already started is skipped instead of offered
def test_describe_past_event():
    past = dict(EVENT, start="2020-01-03T19:00")
    found, lines, retry = main.describe(fake_client(FakeMessages(response=reply([past]))), GOOGLE)
    assert found == []
    assert lines[0].startswith("already started, skipped: Dinner with Sam | 2020-01-03T19:00")

# Cleaning up removes the notifications of old suggestions
def test_clean_up_removes_old_notifications(monkeypatch, capsys):
    removed = []
    monkeypatch.setattr(termux, "remove_notification", removed.append)
    monkeypatch.setattr(store, "clean_up", lambda connection, now: [3, 4])
    main.clean_up(store.open_store(":memory:"))
    assert removed == ["t2c-3", "t2c-4"]
    assert "removed 2 old suggestion(s)" in capsys.readouterr().out

# The log shows app and sender only, and adds the message text when T2C_LOG_TEXT=1
def test_message_label(monkeypatch):
    monkeypatch.setattr(main, "LOG_TEXT", False)
    assert main.message_label(GOOGLE) == "[com.google.android.apps.messaging] Sam"
    monkeypatch.setattr(main, "LOG_TEXT", True)
    assert main.message_label(GOOGLE) == "[com.google.android.apps.messaging] Sam: dinner fri 7pm"

# A handled message is logged without its text by default
def test_handle_keeps_text_out_of_log(monkeypatch, capsys):
    monkeypatch.setattr(main, "LOG_TEXT", False)
    main.handle(None, store.open_store(":memory:"), GOOGLE)
    output = capsys.readouterr().out
    assert "dinner fri 7pm" not in output
    assert "date found, no API key" in output

# The phone's zone is sent to Claude and kept with the event, and a zone named in the text shows in the log
def test_describe_with_time_zones(monkeypatch):
    monkeypatch.setattr(termux, "phone_timezone", lambda: "America/New_York")
    call = dict(EVENT, title="Call", start="2037-01-08T15:00", location="", timezone="America/Los_Angeles")
    messages = FakeMessages(response=reply([call]))
    found, lines, retry = main.describe(fake_client(messages), GOOGLE)
    assert "phone time zone America/New_York" in messages.calls[0]["messages"][0]["content"]
    assert found[0]["phone_timezone"] == "America/New_York"
    assert lines == ["event: Call | 2037-01-08T15:00 to ? | no location | zone America/Los_Angeles"]

# A text with two plans gives two log lines and two prompts
def test_handle_two_events(monkeypatch, capsys):
    shown = []
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: shown.append(args))
    main.handle(fake_client(FakeMessages(response=reply([EVENT, MOVIE]))), store.open_store(":memory:"), GOOGLE)
    summaries = [args[2] for args in shown]  # the text of each prompt
    assert summaries == ["Dinner with Sam · Fri Nov 13, 7:00 PM · Joe's", "Movie · Fri Nov 13, 10:00 PM"]
    output = capsys.readouterr().out
    assert "-> event: Dinner with Sam" in output
    assert "-> event: Movie" in output

# When one plan already started and the other hasn't, only the future one gets a prompt
def test_handle_past_and_future(monkeypatch, capsys):
    shown = []
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: shown.append(args))
    past = dict(EVENT, start="2020-01-03T19:00")
    main.handle(fake_client(FakeMessages(response=reply([past, MOVIE]))), store.open_store(":memory:"), GOOGLE)
    assert [args[2] for args in shown] == ["Movie · Fri Nov 13, 10:00 PM"]
    assert "already started, skipped: Dinner with Sam" in capsys.readouterr().out

# A retried text with two plans gives two prompts once it goes through
def test_retry_with_two_events(monkeypatch):
    shown = []
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: shown.append(args))
    connection = store.open_store(":memory:")
    store.save_retry(connection, GOOGLE, 0, 0)
    main.retry_waiting(fake_client(FakeMessages(response=reply([EVENT, MOVIE]))), connection, 1)
    assert len(shown) == 2

# A birthday party text gives two prompts: the party, and the yearly birthday
def test_handle_party_and_birthday(monkeypatch):
    shown = []
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: shown.append(args))
    party = dict(EVENT, title="Jake's birthday party", start="2037-11-14T20:00", location="Jake's place")
    birthday = dict(EVENT, title="Jake's birthday", start="2037-11-14", all_day=True, location="", repeat="FREQ=YEARLY")
    jake = dict(GOOGLE, title="Mom", content="jakes birthday is saturday, party at 8 at his place")
    main.handle(fake_client(FakeMessages(response=reply([party, birthday]))), store.open_store(":memory:"), jake)
    summaries = [args[2] for args in shown]  # the text of each prompt
    assert summaries == ["Jake's birthday party · Sat Nov 14, 8:00 PM · Jake's place",
                         "Jake's birthday · Sat Nov 14 · repeats yearly"]

# Record alert notifications by their id instead of showing them
def record_alerts(monkeypatch):
    shown = []
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: shown.append(args[0]))
    return shown

# Running out of credit counts as a key problem and alerts straight away
def test_out_of_credit_alerts(monkeypatch):
    shown = record_alerts(monkeypatch)
    message = "Your credit balance is too low to access the Anthropic API."
    error = anthropic.BadRequestError(message, response=httpx2.Response(400, request=REQUEST), body=None)
    connection = store.open_store(":memory:")
    main.describe(fake_client(FakeMessages(error=error)), GOOGLE, connection)
    assert shown == ["t2c-alert-api_key"]

# Other API errors alert only after three in a row
def test_api_errors_alert_after_three(monkeypatch):
    shown = record_alerts(monkeypatch)
    error = anthropic.InternalServerError("down", response=httpx2.Response(500, request=REQUEST), body=None)
    connection = store.open_store(":memory:")
    main.describe(fake_client(FakeMessages(error=error)), GOOGLE, connection)
    main.describe(fake_client(FakeMessages(error=error)), GOOGLE, connection)
    assert shown == []
    main.describe(fake_client(FakeMessages(error=error)), GOOGLE, connection)
    assert shown == ["t2c-alert-api"]

# No internet never alerts, because retries cover it
def test_no_connection_does_not_alert(monkeypatch):
    shown = record_alerts(monkeypatch)
    connection = store.open_store(":memory:")

    # Fail with no internet well past any threshold
    for attempt in range(5):
        offline = fake_client(FakeMessages(error=anthropic.APIConnectionError(request=REQUEST)))
        main.describe(offline, GOOGLE, connection)
    assert shown == []

# A good answer from Claude clears a key alert
def test_success_clears_key_alert(monkeypatch):
    removed = []
    record_alerts(monkeypatch)
    monkeypatch.setattr(termux, "remove_notification", removed.append)
    error = anthropic.AuthenticationError("bad key", response=httpx2.Response(401, request=REQUEST), body=None)
    connection = store.open_store(":memory:")
    main.describe(fake_client(FakeMessages(error=error)), GOOGLE, connection)
    main.describe(fake_client(FakeMessages(response=reply([EVENT]))), GOOGLE, connection)
    assert removed == ["t2c-alert-api_key"]

# Stand-in for termux-notification-list timing out, like when notification access is off
def timed_out_list():
    raise subprocess.TimeoutExpired("termux-notification-list", 30)

# Three failed reads in a row alert, and the next good read clears it
def test_notification_read_alerts(monkeypatch):
    shown = record_alerts(monkeypatch)
    removed = []
    monkeypatch.setattr(termux, "remove_notification", removed.append)
    monkeypatch.setattr(termux, "list_notifications", timed_out_list)
    connection = store.open_store(":memory:")

    # Fail to read three times
    for attempt in range(3):
        assert main.read_messages(connection) == []
    assert shown == ["t2c-alert-notifications"]
    monkeypatch.setattr(termux, "list_notifications", lambda: [GOOGLE])
    assert main.read_messages(connection) == [GOOGLE]
    assert removed == ["t2c-alert-notifications"]
