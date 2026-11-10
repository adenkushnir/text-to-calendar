# Fake Claude client and replies, so tests never call the real API
import json
from types import SimpleNamespace

# Stands in for client.beta.messages: records the request and returns a set reply or raises a set error
class FakeMessages:
    # Store the reply or error to give back
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error
        self.calls = []

    # Record the request, then raise the error or return the reply
    def create(self, **kwargs):
        self.calls.append(kwargs)

        # Simulate an API failure when an error was given
        if self.error is not None:
            raise self.error
        return self.response

# Build a fake client whose beta.messages is the given FakeMessages
def fake_client(messages):
    return SimpleNamespace(beta=SimpleNamespace(messages=messages))

# Build a fake reply holding the given event as JSON text
def reply(event, stop_reason="end_turn"):
    return SimpleNamespace(stop_reason=stop_reason, content=[SimpleNamespace(type="text", text=json.dumps(event))])
EVENT = {"has_event": True, "title": "Dinner with Sam", "start": "2026-11-13T19:00", "end": "", "all_day": False,
         "location": "Joe's"}
NO_EVENT = {"has_event": False, "title": "", "start": "", "end": "", "all_day": False, "location": ""}
