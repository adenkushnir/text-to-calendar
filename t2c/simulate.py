# Pretends a text arrived, for testing without a real message: python -m t2c.simulate "dinner fri 7pm" [sender]
import sys
from datetime import datetime
from t2c import main, store

# Build a note that looks like a message notification from the given sender
def fake_note(text, sender="Test sender"):
    return {"packageName": "simulated", "title": sender, "content": text, "when": f"{datetime.now():%Y-%m-%d %H:%M:%S}"}

# Check the command line and handle the fake text like a real one
def run(argv):
    # Expect the text, and optionally a sender name
    if len(argv) not in (2, 3) or not argv[1].strip():
        print('usage: python -m t2c.simulate "dinner fri 7pm" [sender]', file=sys.stderr)
        return 2
    note = fake_note(*argv[1:])
    main.handle(main.make_client(), store.open_store(store.DB_PATH), note)
    return 0

# Start when run with python -m t2c.simulate
if __name__ == "__main__":
    sys.exit(run(sys.argv))
