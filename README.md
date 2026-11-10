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

- [x] Notification reader
- [x] Date pre-filter
- [ ] Claude extraction
- [ ] Add to calendar
- [ ] Start on boot

## Claude API key

This repo doesn't include an API key. Each person who runs the app uses their own key, and API usage is billed to that key's account.

1. Create a key at [console.anthropic.com](https://console.anthropic.com) under **API keys**, and add credit to the account.
2. Save the key on the phone. Run this in Termux, then paste your key when it asks and press Enter. Nothing shows while you paste, and the key stays out of your command history:

   ```bash
   read -rsp "Paste API key: " KEY && echo "export ANTHROPIC_API_KEY='$KEY'" > ~/.t2c_env && chmod 600 ~/.t2c_env && unset KEY && source ~/.t2c_env
   ```

   This saves the key to `~/.t2c_env` in your Termux home folder, outside the repo, and `chmod 600` makes the file readable only by Termux. Never put the key in a file inside the repo or commit it.
3. Check the key works:

   ```bash
   echo "${ANTHROPIC_API_KEY:0:7}... length ${#ANTHROPIC_API_KEY}"
   python -c "import anthropic; print(anthropic.Anthropic().models.list().data[0].id)"
   ```

   A real key is about 100 characters long, and the second line prints a model name. `invalid x-api-key` means the key was mistyped or is only a placeholder; run step 2 again.
4. Run `source ~/.t2c_env` in each new Termux session before `python -m t2c.main`. Once the start-on-boot step is done, the boot script does this for you.

**Without a key**, the app still runs. It reads notifications and runs the on-phone date check, but sends nothing to Claude and costs nothing. Texts with a date are logged as `date found, no API key`, and no **Add event?** prompt appears.

**Cost:** only texts that pass the on-phone date check are sent to Claude, using Claude Opus 5.5 on low effort, typically under a cent per text. You can set a monthly spend limit in the Anthropic Console.

## Changing the model

The app uses `claude-opus-5-5` by default. The default is set near the top of [`t2c/extractor.py`](t2c/extractor.py):

```python
MODEL = os.environ.get("T2C_MODEL", "claude-opus-5-5")
```

To use a different model without editing the code, which would conflict with the next `git pull`, add a `T2C_MODEL` line to `~/.t2c_env` in Termux:

```bash
echo "export T2C_MODEL=claude-haiku-5-5" >> ~/.t2c_env
source ~/.t2c_env
```

Then restart the app. `claude-haiku-5-5` costs much less per text but may be less accurate on casual or unclear messages. The current models and prices are listed at [docs.claude.com](https://docs.claude.com/en/docs/about-claude/models). To go back to the default, delete the `T2C_MODEL` line from `~/.t2c_env`.

## Development

```bash
pip install -r requirements-dev.txt
python -m pytest
```

The full phone setup guide is added in the start-on-boot step.
