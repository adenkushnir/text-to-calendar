# Tests for filtering and de-duplicating notifications in the main loop
from t2c import main, store, termux
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
