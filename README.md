# text-to-calendar

Turns dates in your texts into Google Calendar events on Android.

A Python script runs on the phone in [Termux](https://termux.dev). It watches Samsung Messages and Google Messages notifications. When a text mentions a date or time, it asks Claude to work out the event and shows an **Add event?** notification. Tapping **Add** opens Google Calendar's new-event screen already filled in.

## How it works

1. Polls `termux-notification-list` for new message notifications.
2. Skips any text without a date-like word, so most texts never leave the phone.
3. Sends the rest to the Claude API, which returns a structured event (title, start, end, location).
4. Shows a notification with **Add** / **Ignore** buttons.
5. **Add** opens the prefilled event in Google Calendar, ready to save.

## Status

Work in progress. See the build steps below.

- [ ] Notification reader
- [ ] Date pre-filter
- [ ] Claude extraction
- [ ] Add to calendar
- [ ] Start on boot

## Development

```bash
pip install -r requirements-dev.txt
python -m pytest
```

The full phone setup guide is added in the start-on-boot step.
