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

# A notification that only changed its timestamp is not a new message
def test_updated_timestamp_is_not_new():
    connection = store.open_store(":memory:")
    store.mark_seen(connection, NOTE)
    assert store.mark_seen(connection, dict(NOTE, when="2")) is False

# The same text counts as new again once it was last seen over an hour ago
def test_same_text_new_after_an_hour():
    connection = store.open_store(":memory:")
    store.mark_seen(connection, NOTE)
    connection.execute("UPDATE seen SET seen_at = seen_at - ?", (store.REPEAT_SECONDS + 1,))
    assert store.mark_seen(connection, NOTE) is True

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
