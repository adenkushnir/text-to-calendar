# Remembers which messages were handled and which events are waiting for Add or Ignore, in a small sqlite database
import hashlib
import json
import os
import sqlite3
DB_PATH = os.environ.get("T2C_DB", os.path.expanduser("~/.t2c.db"))

# Open the database and create the tables if they are missing
def open_store(path):
    connection = sqlite3.connect(path)
    connection.execute("CREATE TABLE IF NOT EXISTS seen (hash TEXT PRIMARY KEY, seen_at INTEGER)")
    pending_table = "pending (id INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT, created_at INTEGER)"
    connection.execute(f"CREATE TABLE IF NOT EXISTS {pending_table}")
    connection.commit()
    return connection

# Make a fingerprint of a notification so the same message is only handled once
def message_hash(note):
    key = "|".join(str(note.get(field, "")) for field in ("packageName", "title", "content", "when"))  # join each field
    return hashlib.sha256(key.encode("utf-8")).hexdigest()

# Record a message as seen and return True if it was new
def mark_seen(connection, note):
    sql = "INSERT OR IGNORE INTO seen (hash, seen_at) VALUES (?, strftime('%s', 'now'))"
    cursor = connection.execute(sql, (message_hash(note),))
    connection.commit()
    return cursor.rowcount == 1

# Save an event waiting for Add or Ignore and return its id
def save_pending(connection, event):
    sql = "INSERT INTO pending (event, created_at) VALUES (?, strftime('%s', 'now'))"
    cursor = connection.execute(sql, (json.dumps(event),))
    connection.commit()
    return cursor.lastrowid

# Return a waiting event by id, or None if it is gone
def get_pending(connection, pending_id):
    row = connection.execute("SELECT event FROM pending WHERE id = ?", (pending_id,)).fetchone()
    return json.loads(row[0]) if row else None  # None when there is no such id

# Remove a waiting event once Add or Ignore was tapped
def delete_pending(connection, pending_id):
    connection.execute("DELETE FROM pending WHERE id = ?", (pending_id,))
    connection.commit()
