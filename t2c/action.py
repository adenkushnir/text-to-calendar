# Handles the Add and Ignore buttons on an "Add to calendar?" notification
import subprocess
import sys
from t2c import events, store, termux

# Run the tapped button: add opens the prefilled event in Google Calendar, ignore just drops it
def handle(connection, command, pending_id):
    event = store.get_pending(connection, pending_id)
    termux.remove_notification(termux.notification_id(pending_id))
    store.delete_pending(connection, pending_id)

    # Only Add with a saved event opens the calendar
    if command != "add" or event is None:
        return

    # Open the calendar, and show a notification if Android blocks it
    try:
        termux.start_activity(events.insert_intent_args(event))
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError) as error:  # am missing, blocked or bad date
        termux.notify(termux.notification_id(pending_id), "Could not open Google Calendar", str(error)[:200])

# Check the command line and run the button
def run(argv):
    # Expect exactly add or ignore and a numeric id
    if len(argv) != 3 or argv[1] not in ("add", "ignore") or not argv[2].isdigit():
        print("usage: python -m t2c.action add|ignore <id>", file=sys.stderr)
        return 2
    handle(store.open_store(store.DB_PATH), argv[1], int(argv[2]))
    return 0

# Start when run with python -m t2c.action
if __name__ == "__main__":
    sys.exit(run(sys.argv))
