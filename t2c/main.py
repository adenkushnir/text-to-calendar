# Main loop: watches message notifications, asks Claude about texts with a date, and offers found events
import os
import shlex
import subprocess
import sys
import time
import anthropic
from t2c import events, extractor, heuristics, store, termux
MESSAGING_APPS = {"com.samsung.android.messaging", "com.google.android.apps.messaging"}
POLL_SECONDS = 5
REPO_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

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

# Make a Claude client, or None if there is no API key yet
def make_client():
    # Without a key, dates are still found but nothing is sent to Claude
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("No ANTHROPIC_API_KEY set: texts will not be sent to Claude", flush=True)
        return None
    return anthropic.Anthropic(timeout=60)

# Work out one message: return the event (or None) and a line describing what happened
def describe(client, note):
    # Texts with no date never leave the phone
    if not heuristics.has_date_hint(note.get("content")):
        return None, "no date"

    # Without an API key we can only say a date was found
    if client is None:
        return None, "date found, no API key"

    # Ask Claude, but report problems instead of crashing
    try:
        event = extractor.extract_event(client, note)
    except (anthropic.AuthenticationError, anthropic.PermissionDeniedError) as error:  # bad key or no access
        return None, f"date found, API key rejected ({error.status_code}): {error.message}"
    except anthropic.RateLimitError:  # too many requests
        return None, "date found, rate limited"
    except anthropic.APIStatusError as error:  # any other API error, like no credit left
        return None, f"date found, API error {error.status_code}: {error.message}"
    except anthropic.APIConnectionError:  # no internet
        return None, "date found, no connection"
    except ValueError:  # reply was not valid JSON
        return None, "date found, unreadable reply"

    # Report whether Claude found an event
    if event is None:
        return None, "date found, no event"
    details = f"{event['title']} | {event['start']} to {event['end'] or '?'} | {event['location'] or 'no location'}"
    repeat = f" | repeat {event['repeat']}" if event.get("repeat") else ""  # only shown for repeating events
    return event, f"event: {details}{repeat}"

# Build the shell command a notification button runs, with full paths so it works from any folder
def action_command(name, pending_id):
    python, repo, db = shlex.quote(sys.executable), shlex.quote(REPO_DIR), shlex.quote(store.DB_PATH)
    return f"cd {repo} && T2C_DB={db} {python} -m t2c.action {name} {pending_id}"

# Save the event and show an "Add to calendar?" notification with Add and Ignore buttons
def suggest(connection, event):
    text = events.summary(event)
    pending_id = store.save_pending(connection, event)
    add = action_command("add", pending_id)
    buttons = [("Add", add), ("Ignore", action_command("ignore", pending_id))]
    termux.notify(termux.notification_id(pending_id), "Add to calendar?", text, tap_action=add, buttons=buttons)

# Describe a new message, print the result, and offer the event if one was found
def handle(client, connection, note):
    event, result = describe(client, note)
    print(f"[{note.get('packageName')}] {note.get('title')}: {note.get('content')}\n    -> {result}", flush=True)

    # Stop here when there is nothing to offer
    if event is None:
        return

    # Show the notification, but keep running if the date can't be read or Termux fails
    try:
        suggest(connection, event)
    except (OSError, subprocess.SubprocessError, ValueError) as error:  # notification failed or bad date
        print(f"    -> could not show notification: {error}", flush=True)

# Poll forever and handle each new message
def run():
    connection = store.open_store(store.DB_PATH)
    client = make_client()
    print("text-to-calendar: watching for messages", flush=True)

    # Check the notifications every POLL_SECONDS
    while True:
        # Read and handle new messages, but keep running if Termux fails
        try:
            # Handle each new message
            for note in check_once(connection):
                handle(client, connection, note)
        except (OSError, subprocess.SubprocessError, ValueError) as error:  # command missing, failed or bad JSON
            print(f"Could not read notifications: {error}", flush=True)
        time.sleep(POLL_SECONDS)

# Start the loop when run with python -m t2c.main
if __name__ == "__main__":
    run()
