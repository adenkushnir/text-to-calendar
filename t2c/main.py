# Main loop: watches message notifications, asks Claude about texts with a date, and offers found events
import os
import shlex
import signal
import subprocess
import sys
import time
from datetime import datetime
import anthropic
from t2c import events, extractor, heuristics, store, termux
MESSAGING_APPS = {"com.samsung.android.messaging", "com.google.android.apps.messaging"}
STATUS_SECONDS = 60
STATUS_ID = "t2c-running"
REPO_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Read a number setting from the environment (set in ~/.t2c_env), falling back to the default if missing or invalid
def read_setting(name, default):
    # Use the default when the value isn't a positive number
    try:
        value = float(os.environ.get(name, default))
        return value if value > 0 else default  # zero or negative makes no sense here
    except ValueError:  # not a number
        print(f"{name} must be a number, using {default}", flush=True)
        return default
POLL_SECONDS = read_setting("T2C_POLL_SECONDS", 5)
SLOW_POLL_SECONDS = read_setting("T2C_SLOW_POLL_SECONDS", 30)
IDLE_SECONDS = read_setting("T2C_IDLE_MINUTES", 10) * 60
RETRY_SECONDS = 60
GIVE_UP_SECONDS = 24 * 60 * 60

# Return only the notifications that come from the messaging apps and have text
def message_notifications(notes):
    messages = []

    # Check each notification
    for note in notes:
        # Skip other apps, notifications with no text, and system notices with no sender
        if note.get("packageName") not in MESSAGING_APPS or not note.get("content") or not note.get("title"):
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

# Work out one message: return the event (or None), a line describing what happened, and whether to try again later
def describe(client, note):
    # Texts with no date never leave the phone
    if not heuristics.has_date_hint(note.get("content")):
        return None, "no date", False

    # Without an API key we can only say a date was found
    if client is None:
        return None, "date found, no API key", False

    # Ask Claude, but report problems instead of crashing
    try:
        event = extractor.extract_event(client, note)
    except (anthropic.AuthenticationError, anthropic.PermissionDeniedError) as error:  # bad key or no access
        return None, f"date found, API key rejected ({error.status_code}): {error.message}", False
    except anthropic.RateLimitError:  # too many requests, worth trying again later
        return None, "date found, rate limited", True
    except anthropic.APIStatusError as error:  # other API errors; server errors (5xx) are worth trying again
        return None, f"date found, API error {error.status_code}: {error.message}", error.status_code >= 500
    except anthropic.APIConnectionError:  # no internet, worth trying again later
        return None, "date found, no connection", True
    except ValueError:  # reply was not valid JSON
        return None, "date found, unreadable reply", False

    # Report whether Claude found an event
    if event is None:
        return None, "date found, no event", False
    details = f"{event['title']} | {event['start']} to {event['end'] or '?'} | {event['location'] or 'no location'}"
    repeat = f" | repeat {event['repeat']}" if event.get("repeat") else ""  # only shown for repeating events

    # Don't offer events that have already started
    if events.has_started(event, time.time() * 1000):
        return None, f"already started, skipped: {details}", False
    return event, f"event: {details}{repeat}", False

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

# Show the notification for a found event, but keep running if the date can't be read or Termux fails
def offer(connection, event):
    # Stop here when there is nothing to offer
    if event is None:
        return

    # Show the notification
    try:
        suggest(connection, event)
    except (OSError, subprocess.SubprocessError, ValueError) as error:  # notification failed or bad date
        print(f"    -> could not show notification: {error}", flush=True)

# Describe a new message, print the result, and offer the event, or save the message to try again later
def handle(client, connection, note):
    event, result, retry = describe(client, note)
    line = f"{datetime.now():%Y-%m-%d %H:%M:%S} [{note.get('packageName')}] {note.get('title')}: {note.get('content')}"
    print(f"{line}\n    -> {result}", flush=True)

    # Save messages that failed for a reason that may clear up, like no internet
    if retry:
        now = time.time()
        store.save_retry(connection, note, now, now + RETRY_SECONDS)
        print(f"    -> will try again every {seconds_label(RETRY_SECONDS)}", flush=True)
        return
    offer(connection, event)

# Try the saved messages again once they are due, giving up on any first saved more than GIVE_UP_SECONDS ago
def retry_waiting(client, connection, now):
    # Try each message that is due
    for retry_id, note, saved_at in store.due_retries(connection, now):
        event, result, retry = describe(client, note)

        # Wait for the next round if it still fails and isn't too old yet
        if retry and now - saved_at < GIVE_UP_SECONDS:
            store.reschedule_retry(connection, retry_id, now + RETRY_SECONDS)
            continue
        store.delete_retry(connection, retry_id)
        outcome = f"gave up ({result})" if retry else result  # still failing after GIVE_UP_SECONDS
        line = f"{datetime.now():%Y-%m-%d %H:%M:%S} retried [{note.get('title')}]: {note.get('content')}"
        print(f"{line}\n    -> {outcome}", flush=True)
        offer(connection, event)

# Return how long to wait before the next check: fast after a recent text, slow once it has been quiet for a while
def poll_interval(seconds_since_text):
    return POLL_SECONDS if seconds_since_text < IDLE_SECONDS else SLOW_POLL_SECONDS  # quiet means slow

# Format an interval for people, like 5 s or 30 s
def seconds_label(seconds):
    return f"{seconds:g} s"

# Return the status line for the pinned notification, like: Last checked 9:41 PM · every 5 s
def status_text(now, interval):
    return f"Last checked {now.hour % 12 or 12}:{now:%M %p} · every {seconds_label(interval)}"

# Show or refresh the pinned "running" notification; a stale time means the app has stopped
def show_status(interval):
    # Keep running even if the notification can't be shown
    try:
        text = status_text(datetime.now(), interval)
        termux.notify(STATUS_ID, "text-to-calendar is running", text, ongoing=True, alert_once=True, priority="low")
    except (OSError, subprocess.SubprocessError) as error:  # Termux failed
        print(f"Could not show the running notification: {error}", flush=True)

# Turn a stop signal (from pkill or restart.sh) into a normal exit, so the running notification is removed
def stop_on_signal(signal_number, frame):
    sys.exit(0)

# Poll forever and handle each new message, checking less often when no texts have arrived for a while
def run():
    connection = store.open_store(store.DB_PATH)
    client = make_client()
    signal.signal(signal.SIGTERM, stop_on_signal)
    print(f"{datetime.now():%Y-%m-%d %H:%M:%S} text-to-calendar: watching for messages", flush=True)
    last_status, last_text, interval = 0, time.time(), POLL_SECONDS

    # Check the notifications in a loop, and remove the running notification however the loop ends
    try:
        # Keep checking until stopped
        while True:
            # Refresh the running notification every STATUS_SECONDS
            if time.time() - last_status >= STATUS_SECONDS:
                show_status(interval)
                last_status = time.time()

            # Read and handle new messages, but keep running if Termux fails
            try:
                new_messages = check_once(connection)

                # Handle each new message
                for note in new_messages:
                    handle(client, connection, note)
                last_text = time.time() if new_messages else last_text  # a new text resets the quiet timer
                retry_waiting(client, connection, time.time())
            except (OSError, subprocess.SubprocessError, ValueError) as error:  # command missing, failed or bad JSON
                print(f"Could not read notifications: {error}", flush=True)
            new_interval = poll_interval(time.time() - last_text)

            # Log and show the change when switching between fast and slow checks
            if new_interval != interval:
                interval, last_status = new_interval, 0
                print(f"{datetime.now():%Y-%m-%d %H:%M:%S} now checking every {seconds_label(interval)}", flush=True)
            time.sleep(interval)
    finally:
        termux.remove_notification(STATUS_ID)

# Start the loop when run with python -m t2c.main
if __name__ == "__main__":
    run()
