# Main loop: watches message notifications and asks Claude about texts that mention a date
import os
import subprocess
import time
import anthropic
from t2c import extractor, heuristics, store, termux
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

# Make a Claude client, or None if there is no API key yet
def make_client():
    # Without a key, dates are still found but nothing is sent to Claude
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("No ANTHROPIC_API_KEY set: texts will not be sent to Claude", flush=True)
        return None
    return anthropic.Anthropic(timeout=60)

# Describe what happened to one message, asking Claude when the text mentions a date
def describe(client, note):
    # Texts with no date never leave the phone
    if not heuristics.has_date_hint(note.get("content")):
        return "no date"

    # Without an API key we can only say a date was found
    if client is None:
        return "date found, no API key"

    # Ask Claude, but report problems instead of crashing
    try:
        event = extractor.extract_event(client, note)
    except (anthropic.AuthenticationError, anthropic.PermissionDeniedError):  # bad key or no access
        return "date found, API key rejected"
    except anthropic.RateLimitError:  # too many requests
        return "date found, rate limited"
    except anthropic.APIStatusError as error:  # any other API error, like no credit left
        return f"date found, API error {error.status_code}: {error.message}"
    except anthropic.APIConnectionError:  # no internet
        return "date found, no connection"
    except ValueError:  # reply was not valid JSON
        return "date found, unreadable reply"

    # Report whether Claude found an event
    if event is None:
        return "date found, no event"
    return f"event: {event['title']} | {event['start']} to {event['end'] or '?'} | {event['location'] or 'no location'}"

# Poll forever and print what happened to each new message
def run():
    connection = store.open_store(DB_PATH)
    client = make_client()
    print("text-to-calendar: watching for messages", flush=True)

    # Check the notifications every POLL_SECONDS
    while True:
        # Read and handle new messages, but keep running if Termux fails
        try:
            # Print each new message and its result
            for note in check_once(connection):
                line = f"[{note.get('packageName')}] {note.get('title')}: {note.get('content')}"
                print(f"{line}\n    -> {describe(client, note)}", flush=True)
        except (OSError, subprocess.SubprocessError, ValueError) as error:  # command missing, failed or bad JSON
            print(f"Could not read notifications: {error}", flush=True)
        time.sleep(POLL_SECONDS)

# Start the loop when run with python -m t2c.main
if __name__ == "__main__":
    run()
