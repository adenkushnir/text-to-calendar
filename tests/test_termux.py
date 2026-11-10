# Tests for the Termux command wrappers, with the commands recorded instead of run
from types import SimpleNamespace
import pytest
from t2c import termux

# Stands in for subprocess.run: records each command and returns the set output
class Recorder:
    # Start with no calls and empty output
    def __init__(self):
        self.calls = []
        self.output = ""

    # Record the command and return a successful result
    def run(self, args, **kwargs):
        self.calls.append(args)
        return SimpleNamespace(returncode=0, stdout=self.output, stderr="")

# Replace subprocess.run in termux with a Recorder
@pytest.fixture
def recorder(monkeypatch):
    fake = Recorder()
    monkeypatch.setattr(termux.subprocess, "run", fake.run)
    return fake

# A suggestion notification has high priority, a tap action and numbered buttons
def test_notify_with_buttons(recorder):
    buttons = [("Add", "add"), ("Ignore", "ignore")]
    termux.notify("t2c-1", "Add to calendar?", "Dinner", tap_action="add", buttons=buttons)
    args = recorder.calls[0]
    assert args[args.index("--priority") + 1] == "high"
    assert args[args.index("--action") + 1] == "add"
    assert args[args.index("--button2") + 1] == "Ignore"
    assert args[args.index("--button2-action") + 1] == "ignore"
    assert "--ongoing" not in args

# The running notification is pinned, quiet and low priority
def test_notify_ongoing(recorder):
    termux.notify("t2c-running", "running", "Last checked", ongoing=True, alert_once=True, priority="low")
    args = recorder.calls[0]
    assert "--ongoing" in args
    assert "--alert-once" in args
    assert args[args.index("--priority") + 1] == "low"

# Notifications are read from the JSON that termux-notification-list prints
def test_list_notifications(recorder):
    recorder.output = '[{"packageName": "com.google.android.apps.messaging", "content": "hi"}]'
    assert termux.list_notifications() == [{"packageName": "com.google.android.apps.messaging", "content": "hi"}]

# An am error message counts as a failure even when am exits with 0
def test_start_activity_error(recorder):
    recorder.output = "Error: Activity not started, unable to resolve Intent"
    with pytest.raises(RuntimeError):
        termux.start_activity(["am", "start"])
