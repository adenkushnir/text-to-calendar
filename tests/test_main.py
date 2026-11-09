# Tests for filtering and de-duplicating notifications in the main loop
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
    assert main.describe(fake_client(messages), {"content": "lol ok"}) == "no date"
    assert messages.calls == []

# Without an API key the date is found but nothing is sent
def test_describe_no_client():
    assert main.describe(None, GOOGLE) == "date found, no API key"

# A found event is described with its title, start and location
def test_describe_event():
    result = main.describe(fake_client(FakeMessages(response=reply(EVENT))), GOOGLE)
    assert result == "event: Dinner with Sam | 2026-11-13T19:00 to ? | Joe's"

# A reply with no event says so
def test_describe_no_event():
    assert main.describe(fake_client(FakeMessages(response=reply(NO_EVENT))), GOOGLE) == "date found, no event"

# A rejected API key is reported instead of crashing
def test_describe_bad_key():
    error = anthropic.AuthenticationError("bad key", response=httpx2.Response(401, request=REQUEST), body=None)
    result = main.describe(fake_client(FakeMessages(error=error)), GOOGLE)
    assert result == "date found, API key rejected (401): bad key"

# No internet is reported instead of crashing
def test_describe_no_connection():
    error = anthropic.APIConnectionError(request=REQUEST)
    assert main.describe(fake_client(FakeMessages(error=error)), GOOGLE) == "date found, no connection"
