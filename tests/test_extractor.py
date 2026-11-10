# Tests for asking Claude about a message and reading its reply
from types import SimpleNamespace
from t2c import extractor
from tests.fakes import EVENT, MOVIE, FakeMessages, fake_client, reply
NOTE = {"packageName": "com.samsung.android.messaging", "title": "Sam", "content": "dinner fri 7pm at Joe's",
        "when": "2026-11-09 16:34:22"}

# The prompt carries the received time, weekday, sender and the text inside message tags
def test_build_prompt():
    prompt = extractor.build_prompt(NOTE)
    assert "Received: 2026-11-09 16:34:22 (Monday)" in prompt
    assert "From: Sam" in prompt
    assert "<message>\ndinner fri 7pm at Joe's\n</message>" in prompt

# A timestamp in an unexpected format gives no weekday instead of crashing
def test_weekday_bad_format():
    assert extractor.weekday("yesterday") == ""

# The request uses the chosen model, low effort, the events schema and refusal fallbacks
def test_request_settings():
    messages = FakeMessages(response=reply([EVENT]))
    extractor.extract_events(fake_client(messages), NOTE)
    request = messages.calls[0]
    assert request["model"] == "claude-opus-5-5"
    assert request["output_config"]["effort"] == "low"
    assert request["output_config"]["format"]["schema"] == extractor.EVENTS_SCHEMA
    assert request["fallbacks"] == "default"

# The schema is a list of events, each with every field required and nothing extra allowed
def test_events_schema_shape():
    schema = extractor.EVENTS_SCHEMA
    assert schema["required"] == ["events"]
    assert schema["additionalProperties"] is False
    item = schema["properties"]["events"]["items"]
    assert item["additionalProperties"] is False
    assert set(item["required"]) == set(item["properties"])

# The prompt asks for one event per plan and only the first choice when there is a fallback
def test_prompt_rules():
    assert "Give each separate plan its own event" in extractor.SYSTEM_PROMPT
    assert "list only the first choice" in extractor.SYSTEM_PROMPT

# Replies with none, one or two events come back as lists
def test_event_lists():
    assert extractor.extract_events(fake_client(FakeMessages(response=reply([]))), NOTE) == []
    assert extractor.extract_events(fake_client(FakeMessages(response=reply([EVENT]))), NOTE) == [EVENT]
    assert extractor.extract_events(fake_client(FakeMessages(response=reply([EVENT, MOVIE]))), NOTE) == [EVENT, MOVIE]

# Events without a start time are dropped, the rest are kept
def test_event_without_start():
    assert extractor.parse_response(reply([dict(EVENT, start=""), MOVIE])) == [MOVIE]

# Refusals and cut-off replies give no events
def test_refusal_and_max_tokens():
    assert extractor.parse_response(reply([EVENT], stop_reason="refusal")) == []
    assert extractor.parse_response(reply([EVENT], stop_reason="max_tokens")) == []

# A reply with no text block gives no events
def test_no_text_block():
    assert extractor.parse_response(SimpleNamespace(stop_reason="end_turn", content=[])) == []

# The phone's time zone is added to the prompt when known
def test_build_prompt_with_phone_zone():
    assert "Received: 2026-11-09 16:34:22 (Monday, phone time zone America/New_York)" in \
        extractor.build_prompt(NOTE, "America/New_York")

# The prompt explains how to turn a birthday into a yearly all-day event, and to ignore birthday wishes
def test_birthday_rules():
    assert "\"<Name>'s birthday\"" in extractor.SYSTEM_PROMPT
    assert "repeat FREQ=YEARLY" in extractor.SYSTEM_PROMPT
    assert "Use the From name for the sender's own birthday" in extractor.SYSTEM_PROMPT
    assert "Ignore birthday wishes" in extractor.SYSTEM_PROMPT
