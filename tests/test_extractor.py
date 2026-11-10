# Tests for asking Claude about a message and reading its reply
from types import SimpleNamespace
from t2c import extractor
from tests.fakes import EVENT, NO_EVENT, FakeMessages, fake_client, reply
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

# The request uses the chosen model, low effort, the event schema and refusal fallbacks
def test_request_settings():
    messages = FakeMessages(response=reply(EVENT))
    extractor.extract_event(fake_client(messages), NOTE)
    request = messages.calls[0]
    assert request["model"] == "claude-opus-5-5"
    assert request["output_config"]["effort"] == "low"
    assert request["output_config"]["format"]["schema"] == extractor.EVENT_SCHEMA
    assert request["fallbacks"] == "default"

# A reply with an event comes back as a dict
def test_event_found():
    assert extractor.extract_event(fake_client(FakeMessages(response=reply(EVENT))), NOTE) == EVENT

# A reply with no event gives None
def test_no_event():
    assert extractor.extract_event(fake_client(FakeMessages(response=reply(NO_EVENT))), NOTE) is None

# An event without a start time gives None
def test_event_without_start():
    assert extractor.parse_response(reply(dict(EVENT, start=""))) is None

# Refusals and cut-off replies give None
def test_refusal_and_max_tokens():
    assert extractor.parse_response(reply(EVENT, stop_reason="refusal")) is None
    assert extractor.parse_response(reply(EVENT, stop_reason="max_tokens")) is None

# A reply with no text block gives None
def test_no_text_block():
    assert extractor.parse_response(SimpleNamespace(stop_reason="end_turn", content=[])) is None

# The phone's time zone is added to the prompt when known
def test_build_prompt_with_phone_zone():
    assert "Received: 2026-11-09 16:34:22 (Monday, phone time zone America/New_York)" in \
        extractor.build_prompt(NOTE, "America/New_York")
