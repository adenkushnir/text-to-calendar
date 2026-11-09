# Main loop: watches message notifications and logs each new message
import os
import subprocess
import time
from t2c import store, termux
MESSAGING_APPS = {"com.samsung.android.messaging", "com.google.android.apps.messaging"}
POLL_SECONDS = 15
DB_PATH = os.environ.get("T2C_DB", os.path.expanduser("~/.t2c.db"))

# Return only the notifications that come from the messaging apps and have text
def message_notifications(notes):
    messages = []

    # Check each notification
    for note in notes:
        # Skip other apps and notifications with no text
        if note.get("packageName") not in MESSAGING_APPS or not note.get("content"):
            continue
        messages.append(note)
    return messages

# Read the notifications once and return the messages not seen before
def check_once(connection):
    new_messages = []

    # Keep each message the first time it shows up
    for note in message_notifications(termux.list_notifications()):
        if store.mark_seen(connection, note):  # new message
            new_messages.append(note)
    return new_messages

# Poll forever and print each new message
def run():
    connection = store.open_store(DB_PATH)
    print("text-to-calendar: watching for messages", flush=True)

    # Check the notifications every POLL_SECONDS
    while True:
        # Read and log new messages, but keep running if Termux fails
        try:
            # Print each new message
            for note in check_once(connection):
                print(f"[{note.get('packageName')}] {note.get('title')}: {note.get('content')}", flush=True)
        except (subprocess.SubprocessError, ValueError) as error:  # command failed or bad JSON
            print(f"Could not read notifications: {error}", flush=True)
        time.sleep(POLL_SECONDS)

# Start the loop when run with python -m t2c.main
if __name__ == "__main__":
    run()
