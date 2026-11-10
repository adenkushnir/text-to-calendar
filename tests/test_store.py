# Tests for the seen-messages store
from t2c import store
NOTE = {"packageName": "com.google.android.apps.messaging", "title": "Sam", "content": "dinner fri 7pm", "when": "1"}

# A new message is reported once, then ignored
def test_mark_seen_only_once():
    connection = store.open_store(":memory:")
    assert store.mark_seen(connection, NOTE) is True
    assert store.mark_seen(connection, NOTE) is False

# A different message text counts as a new message
def test_different_content_is_new():
    connection = store.open_store(":memory:")
    store.mark_seen(connection, NOTE)
    assert store.mark_seen(connection, dict(NOTE, content="actually sat 8pm")) is True

# The hash does not contain the message text itself
def test_hash_hides_text():
    assert "dinner" not in store.message_hash(NOTE)

# A waiting event can be saved, read back by id, and deleted
def test_pending_round_trip():
    connection = store.open_store(":memory:")
    event = {"title": "Dinner", "start": "2026-11-13T19:00"}
    pending_id = store.save_pending(connection, event)
    assert store.get_pending(connection, pending_id) == event
    store.delete_pending(connection, pending_id)
    assert store.get_pending(connection, pending_id) is None
