# Quiet phone alerts for problems the app can see itself, shown once per problem and removed when it clears
import subprocess
from datetime import datetime
from t2c import store, termux
KINDS = {
    "notifications": (3, "Can't read notifications", "Turn notification access for Termux:API back on."),
    "api_key": (1, "Claude API key or credit problem", "Check the key in ~/.t2c_env, or add credit in the Console."),
    "api": (3, "Claude API keeps failing", "Texts with a date will be tried again."),
}

# Return the notification id for a kind of alert
def alert_id(kind):
    return f"t2c-alert-{kind}"

# Write a timestamped line to the log
def log(text):
    print(f"{datetime.now():%Y-%m-%d %H:%M:%S} {text}", flush=True)

# Count a failure, and show a quiet alert the moment the count reaches the kind's threshold
def failed(connection, kind, detail=""):
    threshold, title, advice = KINDS[kind]

    # Only alert when the threshold is first reached, so each problem is shown once
    if store.record_failure(connection, kind) != threshold:
        return
    content = f"{advice} ({detail})" if detail else advice  # add what went wrong when known

    # Show the alert, keeping going if Termux fails
    try:
        termux.notify(alert_id(kind), title, content[:300], priority="low", alert_once=True)
        log(f"alert: {title}")
    except (OSError, subprocess.SubprocessError) as error:  # Termux failed
        log(f"could not show alert '{title}': {error}")

# Reset the count after a success, and remove the alert if one was showing
def succeeded(connection, kind):
    threshold, title, advice = KINDS[kind]

    # Nothing to remove unless the alert had been shown
    if store.record_success(connection, kind) < threshold:
        return

    # Remove the alert, keeping going if Termux fails
    try:
        termux.remove_notification(alert_id(kind))
        log(f"alert cleared: {title}")
    except (OSError, subprocess.SubprocessError) as error:  # Termux failed
        log(f"could not remove alert '{title}': {error}")
