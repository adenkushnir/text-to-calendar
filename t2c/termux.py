# Thin wrappers around Termux:API commands, the only code that talks to the phone
import json
import subprocess

# Return all current notifications as a list of dicts
def list_notifications():
    result = subprocess.run(["termux-notification-list"], capture_output=True, text=True, timeout=30)

    # Treat a failed or empty command as no notifications
    if result.returncode != 0 or not result.stdout.strip():
        return []
    return json.loads(result.stdout)

# Return the notification id used for a waiting event
def notification_id(pending_id):
    return f"t2c-{pending_id}"

# Show a notification; tap_action and each button action are shell commands Termux runs when tapped
def notify(notify_id, title, content, tap_action=None, buttons=(), ongoing=False, alert_once=False, priority="high"):
    args = ["termux-notification", "--id", notify_id, "--title", title, "--content", content, "--priority", priority]
    args += ["--ongoing"] if ongoing else []  # pinned, can't be swiped away
    args += ["--alert-once"] if alert_once else []  # no sound or pop-up when it is refreshed

    # Run tap_action when the notification itself is tapped
    if tap_action:
        args += ["--action", tap_action]

    # Add each button with its label and command
    for number, (label, action) in enumerate(buttons, start=1):
        args += [f"--button{number}", label, f"--button{number}-action", action]
    subprocess.run(args, check=True, capture_output=True, timeout=30)

# Remove a notification by id
def remove_notification(notify_id):
    subprocess.run(["termux-notification-remove", notify_id], capture_output=True, timeout=30)

# Start an Android activity with the given am arguments, raising an error if am reports one
def start_activity(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=30)
    output = (result.stdout + result.stderr).strip()

    # am sometimes prints an error but still exits with 0, so check the text too
    if result.returncode != 0 or output.startswith("Error") or "Exception" in output:
        raise RuntimeError(output or f"am exited with {result.returncode}")

# Return the phone's time zone as an IANA name like America/New_York, or an empty string if it can't be read
def phone_timezone():
    # Ask Android for the zone, treating any failure as unknown
    try:
        result = subprocess.run(["getprop", "persist.sys.timezone"], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):  # not on Android, or getprop failed
        return ""
    return result.stdout.strip()
