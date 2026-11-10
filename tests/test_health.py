# Tests for the quiet alerts: thresholds, showing once, clearing, and surviving Termux failures
import subprocess
import pytest
from t2c import health, store, termux

# Record alert notifications instead of showing them
@pytest.fixture
def alerts(monkeypatch):
    calls = {"shown": [], "removed": []}
    monkeypatch.setattr(termux, "notify", lambda *args, **kwargs: calls["shown"].append((args, kwargs)))
    monkeypatch.setattr(termux, "remove_notification", calls["removed"].append)
    return calls

# A problem with a threshold of 3 alerts on the third failure in a row, not before
def test_alert_after_threshold(alerts):
    connection = store.open_store(":memory:")
    health.failed(connection, "notifications", "timed out")
    health.failed(connection, "notifications", "timed out")
    assert alerts["shown"] == []
    health.failed(connection, "notifications", "timed out")
    args, kwargs = alerts["shown"][0]
    assert args[0] == "t2c-alert-notifications"
    assert args[1] == "Can't read notifications"
    assert "timed out" in args[2]
    assert kwargs["priority"] == "low"
    assert kwargs["alert_once"] is True

# A key or credit problem alerts straight away
def test_key_problem_alerts_at_once(alerts):
    health.failed(store.open_store(":memory:"), "api_key", "401: invalid x-api-key")
    assert alerts["shown"][0][0][1] == "Claude API key or credit problem"

# An alert is shown once per problem, however many more failures follow
def test_alert_shown_once(alerts):
    connection = store.open_store(":memory:")

    # Fail well past the threshold
    for attempt in range(6):
        health.failed(connection, "api", "server error")
    assert len(alerts["shown"]) == 1

# A success removes an alert that was showing, and a success with no alert removes nothing
def test_success_clears_alert(alerts):
    connection = store.open_store(":memory:")
    health.succeeded(connection, "api_key")
    assert alerts["removed"] == []
    health.failed(connection, "api_key", "bad key")
    health.succeeded(connection, "api_key")
    assert alerts["removed"] == ["t2c-alert-api_key"]
    assert store.failure_count(connection, "api_key") == 0

# Failures that stop before the threshold are forgotten after a success
def test_success_resets_count(alerts):
    connection = store.open_store(":memory:")
    health.failed(connection, "api", "server error")
    health.failed(connection, "api", "server error")
    health.succeeded(connection, "api")
    health.failed(connection, "api", "server error")
    assert alerts["shown"] == []
    assert alerts["removed"] == []

# Stand-in for a Termux command that fails
def broken_termux(*args, **kwargs):
    raise subprocess.SubprocessError("termux-api missing")

# A Termux failure while showing or removing an alert is logged instead of crashing
def test_termux_failure_is_logged(monkeypatch, capsys):
    monkeypatch.setattr(termux, "notify", broken_termux)
    monkeypatch.setattr(termux, "remove_notification", broken_termux)
    connection = store.open_store(":memory:")
    health.failed(connection, "api_key", "bad key")
    health.succeeded(connection, "api_key")
    output = capsys.readouterr().out
    assert "could not show alert" in output
    assert "could not remove alert" in output
