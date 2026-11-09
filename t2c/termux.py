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
