# text-to-calendar

Turns dates in your texts into Google Calendar events on Android.

A Python script runs on the phone in [Termux](https://termux.dev). It watches Samsung Messages and Google Messages notifications. When a text mentions a date or time, it asks Claude to work out the event and shows an **Add to calendar?** notification. Tapping **Add** opens Google Calendar's new-event screen already filled in, ready to save.

## How it works

1. Every 5 seconds, it reads the phone's notifications with `termux-notification-list` and keeps new texts from the messaging apps. After 10 minutes with no new texts it checks every 30 seconds instead, to save battery, and goes back to every 5 seconds as soon as a text arrives.
2. It skips any text without a date-like word, so most texts never leave the phone.
3. It sends the rest to the Claude API, which returns a structured event: title, start, end, all-day, location and repeat rule.
4. It shows a notification with **Add** and **Ignore** buttons.
5. **Add** opens the prefilled event in Google Calendar. Nothing is saved until you tap **Save** there.

## Status

- [x] Notification reader
- [x] Date pre-filter
- [x] Claude extraction
- [x] Add to calendar (including repeating events)
- [x] Start on boot

## Setup

These steps are needed once per phone.

### 1. Install the apps

Install **Termux**, **Termux:API** and **Termux:Boot** from [F-Droid](https://f-droid.org). All three must come from the same place: the Play Store versions are outdated and can't work with the F-Droid ones. Open **Termux:Boot** once after installing it. Android only lets it run at start-up after it has been opened.

### 2. Install the packages and the code

In Termux:

```bash
pkg upgrade -y && pkg install -y python git termux-api rust binutils
export ANDROID_API_LEVEL=$(getprop ro.build.version.sdk)
git clone https://github.com/adenkushnir/text-to-calendar.git ~/text-to-calendar
cd ~/text-to-calendar && pip install -r requirements.txt
```

`rust` is needed because two of the Anthropic SDK's libraries are compiled on the phone. The first `pip install` can take 10–20 minutes; keep Termux open.

### 3. Phone permissions

| Setting | Why | How |
|---|---|---|
| Notification access for **Termux:API** | Read message notifications | Run `am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` and turn on Termux:API. If Android says "Restricted setting", open Settings → Apps → Termux:API → ⋮ → **Allow restricted settings** first. |
| Notifications for **Termux:API** | Show the **Add to calendar?** prompt | Settings → Apps → Termux:API → Notifications → on |
| **Display over other apps** for Termux | Let the **Add** button open Google Calendar | Run `am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION -d package:com.termux` and turn it on |
| `allow-external-apps` in Termux | Let notification buttons run commands | Run `mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties && termux-reload-settings` |
| Battery **Unrestricted** for Termux, Termux:API and Termux:Boot | Stop Samsung from closing the app in the background | Settings → Apps → each app → Battery → **Unrestricted**. Also make sure none of them is in Settings → Battery → Background usage limits → **Sleeping / Deep sleeping apps**. |

Check notification access with `timeout 10 termux-notification-list; echo $?`. It should print your notifications and `0`. `124` means it hung, which usually means access isn't really granted.

### 4. Claude API key

This repo doesn't include an API key. Each person who runs the app uses their own key, and API usage is billed to that key's account.

1. Create a key at [console.anthropic.com](https://console.anthropic.com) under **API keys**, and add credit to the account.
2. Save it on the phone in `~/.t2c_env`, in your Termux home folder, outside the repo:

   ```bash
   pkg install -y nano
   rm -f ~/.t2c_env && nano ~/.t2c_env
   ```

   Type `export ANTHROPIC_API_KEY='`, paste your key (long-press → Paste), and type a closing `'`, all on one line. Save with **Volume Down + O**, then Enter, and exit with **Volume Down + X**. Volume Down works as CTRL in Termux.
3. Lock the file and check the key works:

   ```bash
   chmod 600 ~/.t2c_env && source ~/.t2c_env
   echo "${ANTHROPIC_API_KEY:0:7}... length ${#ANTHROPIC_API_KEY}"
   python -c "import anthropic; print(anthropic.Anthropic().models.list().data[0].id)"
   ```

   A real key is about 100 characters long, and the last line prints a model name. `invalid x-api-key` means the key was mistyped or is only a placeholder.

Never put the key in a file inside the repo or commit it.

**Without a key**, the app still runs. It reads notifications and runs the on-phone date check, but sends nothing to Claude and costs nothing. Texts with a date are logged as `date found, no API key`, and no **Add to calendar?** prompt appears.

**Cost:** only texts that pass the on-phone date check are sent to Claude, using Claude Opus 5.5 on low effort, typically under a cent per text. You can set a monthly spend limit in the Anthropic Console.

### 5. Start on boot

Link the start script into Termux:Boot's folder, then start it once now:

```bash
mkdir -p ~/.termux/boot
ln -sf ~/text-to-calendar/boot/start.sh ~/.termux/boot/start-t2c.sh
sh ~/text-to-calendar/boot/start.sh
```

From then on, Termux:Boot runs it whenever the phone starts. Because it's a link, a `git pull` updates the boot script too.

### 6. Check it works

- A pinned **text-to-calendar is running** notification appears, showing "Last checked" with a time that updates every minute.
- `tail ~/t2c.log` shows `text-to-calendar: watching for messages`.
- Have someone text you "dinner fri 7pm". Within a few seconds an **Add to calendar?** prompt appears.
- Restart the phone, wait a minute, and check the running notification is back.

## Everyday use

There's nothing to do day to day. Two notifications stay in the notification bar:

- **text-to-calendar is running**, with the time of its last check and how often it is checking (every 5 s or every 30 s). If that time stops updating, the app has stopped.
- Termux's own notification ("wake lock held"). It keeps Termux alive; don't tap **Exit** on it.

| Command (in Termux) | What it does |
|---|---|
| `sh ~/text-to-calendar/boot/start.sh` | Start it (does nothing if it's already running) |
| `sh ~/text-to-calendar/boot/stop.sh` | Stop it |
| `sh ~/text-to-calendar/boot/restart.sh` | Restart it |
| `tail -f ~/t2c.log` | Watch the log live (stop watching with Volume Down + C) |
| `pgrep -f t2c.main` | Prints a number if it's running |

If the app crashes, it restarts itself after 30 seconds. If a text with a date arrives while there's no internet, or while Claude is busy or having problems, the text is saved and tried again every minute for up to 24 hours, and the prompt appears once it goes through. The log is in `~/t2c.log`, and starts over in a new file once it passes 1 MB. It stays on the phone and is never committed.

The log records which app and sender each text came from and what happened to it, including any event Claude found (title, time, location). It does **not** record the text itself. To include message texts while testing, add `export T2C_LOG_TEXT=1` to `~/.t2c_env` and restart. Delete that line again afterwards.

## When it has to be started again

| Situation | What to do |
|---|---|
| The phone restarts or the battery dies | Nothing. Termux:Boot starts it. |
| The app crashes | Nothing. It restarts after 30 seconds. |
| You tapped **Exit** on Termux's notification, or force-stopped Termux | Run `start.sh`, or restart the phone. |
| Samsung closed it anyway (often after a One UI update resets battery settings) | Set the three apps back to **Unrestricted**, then run `start.sh`. |
| An Android update turned off notification access | Turn notification access for Termux:API back on (see Setup step 3), then run `restart.sh`. |
| New code version | `cd ~/text-to-calendar && git pull`, then run `restart.sh`. |
| New API key, or the account ran out of credit | Edit `~/.t2c_env` (or add credit), then run `restart.sh`. |
| Termux reinstalled, or a new phone | Do the whole setup again. |

## Battery settings

Checking notifications often is what uses most of the battery. These settings go in `~/.t2c_env`, one `export` line each, and take effect after `restart.sh`:

| Setting | Default | What it does |
|---|---|---|
| `T2C_POLL_SECONDS` | `5` | How often it checks while texts are coming in. Higher saves battery, but texts sent close together are more likely to be missed. |
| `T2C_SLOW_POLL_SECONDS` | `30` | How often it checks once it has been quiet. A text that arrives then gets its prompt up to this many seconds later. |
| `T2C_IDLE_MINUTES` | `10` | How long without a new text before it switches to the slow checks. |

For example, to check every 10 seconds normally and every minute when quiet:

```bash
echo "export T2C_POLL_SECONDS=10" >> ~/.t2c_env
echo "export T2C_SLOW_POLL_SECONDS=60" >> ~/.t2c_env
sh ~/text-to-calendar/boot/restart.sh
```

The log shows each switch (`now checking every 30 s`). To see how much battery the app uses, open Settings → Battery and check **Termux** and **Termux:API**.

## Changing the model

The app uses `claude-opus-5-5` by default. The default is set near the top of [`t2c/extractor.py`](t2c/extractor.py):

```python
MODEL = os.environ.get("T2C_MODEL", "claude-opus-5-5")
```

To use a different model without editing the code, which would conflict with the next `git pull`, add a `T2C_MODEL` line to `~/.t2c_env` and restart:

```bash
echo "export T2C_MODEL=claude-haiku-5-5" >> ~/.t2c_env
sh ~/text-to-calendar/boot/restart.sh
```

`claude-haiku-5-5` costs much less per text but may be less accurate on casual or unclear messages. The current models and prices are listed at [docs.claude.com](https://docs.claude.com/en/docs/about-claude/models). To go back to the default, delete the `T2C_MODEL` line from `~/.t2c_env`.

## Known limits

- Texts you **send**, and texts that arrive while their chat is open, don't post a notification, so the app never sees them.
- A text with two plans ("dinner at 7, then a movie at 10") gives one event.
- If someone sends several texts between two checks (5 seconds, or 30 seconds when quiet), only the newest may be read.
- Numeric dates are read as month/day (1/6 = January 6).

## Development

```bash
pip install -r requirements-dev.txt
python -m pytest
```

The tests use a fake Claude client and fake Termux commands, so they run on any computer and never call the API.

To try a text on the phone without anyone texting you, pretend one arrived:

```bash
cd ~/text-to-calendar && source ~/.t2c_env
python -m t2c.simulate "dinner fri 7pm at joes"
python -m t2c.simulate "lunch tomorrow at noon" "Sam"
```

It goes through the same date check, Claude call, retry queue and **Add to calendar?** prompt as a real text, but doesn't read the phone's notifications. The second argument is the sender's name. The default is "Test sender". To see the text in its output too, run it with `T2C_LOG_TEXT=1` in front, like `T2C_LOG_TEXT=1 python -m t2c.simulate "..."`.
