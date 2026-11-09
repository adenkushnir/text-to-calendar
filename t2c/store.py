# Remembers which messages were already handled, in a small sqlite database
import hashlib
import sqlite3

# Open the database and create the tables if they are missing
def open_store(path):
    connection = sqlite3.connect(path)
    connection.execute("CREATE TABLE IF NOT EXISTS seen (hash TEXT PRIMARY KEY, seen_at INTEGER)")
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
