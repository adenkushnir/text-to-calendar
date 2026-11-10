# Tests for the command that pretends a text arrived
from t2c import main, simulate

# The fake note carries the text, sender and the current time like a real notification
def test_fake_note():
    note = simulate.fake_note("dinner fri 7pm", "Sam")
    assert note["content"] == "dinner fri 7pm"
    assert note["title"] == "Sam"
    assert len(note["when"]) == len("2026-11-10 09:00:00")

# The fake text goes through the same handling as a real one
def test_run_handles_text(monkeypatch):
    handled = []
    monkeypatch.setattr(main, "make_client", lambda: None)
    monkeypatch.setattr(main, "handle", lambda client, connection, note: handled.append(note))
    monkeypatch.setattr(simulate.store, "DB_PATH", ":memory:")
    assert simulate.run(["simulate", "lunch sat at noon"]) == 0
    assert handled[0]["content"] == "lunch sat at noon"
    assert handled[0]["title"] == "Test sender"

# Missing or empty text is rejected
def test_run_rejects_bad_arguments():
    assert simulate.run(["simulate"]) == 2
    assert simulate.run(["simulate", "  "]) == 2
