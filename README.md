# Datebook

An Android app that turns plans in your texts and emails into calendar events, and keeps your day in one place.

When a text says "dinner fri 7pm at Luigi's", Datebook asks Claude to read it and shows an **Add to calendar?**
notification. Tapping **Add** opens your calendar app's new-event screen already filled in: title, time, full
address, and notes saying who it came from. Datebook never writes to your calendar itself; nothing is saved until you
tap **Save** there.

Datebook started as a Python script running in Termux (see [Legacy](#legacy-the-termux-script-v020) at the end).
Version 1.0.0 is a full Kotlin app, built with Jetpack Compose, in [`android/`](android/).

## What it does

**Catching plans**
- Reads new messages from the apps you choose: Google Messages, Samsung Messages, GroupMe, WhatsApp, Instagram,
  Discord and Gmail. Only messages with a date or time in them are sent to Claude; group chats need a clear one.
- **Share → Datebook** for any text, picture or PDF. A schedule (a syllabus, a team schedule) becomes a list you
  tick through and add in one go.
- **Gmail sign-in** (read-only) for reservations, tickets and appointments, with the confirmation code in the notes.
- **Automatic mode** (optional, off by default): also catches texts you send and new messages in an open chat.
- **Offline backup:** without internet, an on-phone guess waits on Home and Claude checks it once you're back online.

**Knowing your calendar**
- Warns about clashes, skips plans already on your calendar, and turns "moved to 8" or "cancelled" into a
  **Move Dinner to 8 PM?** prompt that opens the event.
- **Leave-by alerts** with travel time (drive, transit, walk or bike) and **Open in Maps**.
- **Flights** filled in from live flight data, with delay and gate alerts. **NJ Transit** trains toward a plan, with
  your train on the leave-by alert. **Track** links for UPS, FedEx, USPS, Amazon and DHL packages.
- Bills and assignments become reminders, not events. Loose plans ("we should get lunch next week") get a nudge to
  pin them down.

**Your day**
- **Home:** the Today card with the weather, today's events, reminders, bills and assignments, the next one lit and
  finished ones dimmed; plans waiting for you; the next few days; and a live NJ Transit board for your trip.
- **Lists:** packages on the way, assignments due soon, trips, and bills.
- **Notes:** notes and reminders. A reminder is a notification at a time, typed in Datebook or sent in a text
  ("remind me to call the dentist tomorrow at 9").
- **Widgets:** Next trains (the train that just left and the next 3; tapping a train opens the NJ Transit app) and
  Lists at a glance. They use your wallpaper's colours.
- **Quiet notifications:** an 8 AM summary on days with events, a Friday week ahead, and the evening before, what to
  bring ("Bring: cleats, $20") and a heads-up when an outdoor plan looks rainy or cold.
- **Problem alerts:** one quiet notification (and a banner on Home) if your Claude key stops working, Claude keeps
  failing, or notification access is turned off. Both clear themselves.

## Install

Datebook needs Android 10 or newer.

1. Download `datebook-1.0.0.apk` from the [latest release](https://github.com/adenkushnir/text-to-calendar/releases/latest)
   on your phone.
2. Open it and allow your browser or Files app to install apps when Android asks.
3. Open Datebook and follow the six setup pages:
   1. **Let Datebook read your notifications**, so it sees new messages.
   2. **Allow Datebook to show notifications**, for the prompts and alerts.
   3. **Paste your Claude API key** (see below).
   4. **Keep Datebook running**: set its battery use to Unrestricted, so Android doesn't put it to sleep.
   5. **Let Datebook see your calendar**, for clashes, Home and leave-by alerts.
   6. **Add a Google Maps key** (optional), for addresses and travel times.

On Android 13 and newer, an app installed from a file may need **Allow restricted settings** (in the app's info page,
⋮ menu) before you can turn on notification access or automatic mode.

## Keys and accounts

Datebook has no server. Everything runs on your phone with your own keys, stored encrypted with the Android Keystore.

| What | Needed for | Where to get it |
|---|---|---|
| **Claude API key** (required) | Reading texts, emails, pictures and PDFs | [console.anthropic.com](https://console.anthropic.com) |
| Google Maps key (optional) | Full addresses, travel times, leave-by alerts | Google Cloud: enable Places API (New) and Routes API |
| AeroDataBox key (optional) | Live flight times, delays and gates | RapidAPI, free Basic plan |
| NJ Transit account (optional) | Live trains | [developer.njtransit.com](https://developer.njtransit.com) (RailData and GTFS-RAIL) |
| Gmail (optional) | Plans from email | Sign in from Settings → Gmail |

If you restrict your Maps key to Android apps, use the package name `io.github.adenkushnir.datebook` and the release
certificate's SHA-1:

```
CA:E1:CF:38:51:8C:2C:2A:14:29:BF:18:DA:7B:4C:4F:8B:20:2F:77
```

Gmail sign-in uses this project's Google Cloud app, which is in Google's testing mode: it only works for Google
accounts added as testers, and signing in lapses about once a week (Datebook reminds you).

**Cost:** Claude is the only paid part, usually about $3–8 a month with Claude Opus 5.5, less with a smaller model
(Settings → Claude & API key). Google Maps normally stays within its free monthly allowance for one person.

## Privacy

- Messages without a date or time never leave the phone. A message that does is sent to Claude on its own: never
  other messages from the chat, and for group chats only the group's name, not the sender's.
- Datebook keeps what it suggested and what you did with it for 1 year, never the message text itself (except a
  message waiting up to a day for Claude while you're offline).
- Other services only get what they need: place names (Google Maps), flight numbers and dates (AeroDataBox), station
  codes (NJ Transit), and your rough location for the weather ([Open-Meteo](https://open-meteo.com), no account).

## Known limits

- Datebook can't add events by itself; every event goes through your calendar app's **Save**.
- Numeric dates are read as month/day (1/6 = January 6).
- "Remind me to buy milk" in a text you send has no date, so it isn't caught; type it in the Notes tab instead.
- The NJ Transit app has no link to a single train, so tapping a train on the widget opens the app's start screen.

## Development

The app is in [`android/`](android/). Open it in Android Studio, or build from Git Bash:

```bash
cd android
./gradlew test assembleDebug
```

GitHub Actions runs the unit tests and builds the debug app on every pull request.

Debug builds have a **Pretend a text arrived** box in Settings → About, and adb commands that run things now instead
of waiting for their time:

```bash
adb shell am broadcast -a io.github.adenkushnir.datebook.SHOW_SUMMARY -n io.github.adenkushnir.datebook/.ShowSummaryReceiver
adb shell am broadcast -a io.github.adenkushnir.datebook.LEAVE_BY_NOW -n io.github.adenkushnir.datebook/.LeaveByNowReceiver
adb shell am broadcast -a io.github.adenkushnir.datebook.CHECK_GMAIL -n io.github.adenkushnir.datebook/.CheckGmailReceiver
adb shell am broadcast -a io.github.adenkushnir.datebook.FOLLOW_UPS_NOW -n io.github.adenkushnir.datebook/.FollowUpsNowReceiver
adb shell am broadcast -a io.github.adenkushnir.datebook.EVENING_NOW -n io.github.adenkushnir.datebook/.EveningNowReceiver
```

**Release builds** (`./gradlew assembleRelease`, giving `app/build/outputs/apk/release/app-release.apk`) leave out
the debug tools and are signed with the developer PC's debug key. Datebook isn't on the Play Store, so this keeps
one key for every version: a new release installs over the old one, and the SHA-1 above stays the same. A release
built on another PC is signed with that PC's key and can't update an install from this one.

The design notes are in [`design/DESIGN.md`](design/DESIGN.md), and the clickable mockup the app was built from is in
[`android/mockup/`](android/mockup/).

## Legacy: the Termux script (v0.2.0)

Before Datebook, this project was a Python script that ran on the phone in [Termux](https://termux.dev). It read
notifications every few seconds, asked Claude about texts with dates, and showed the same **Add to calendar?**
prompt. The code is still in [`t2c/`](t2c/), [`tests/`](tests/) and [`boot/`](boot/), but it's frozen and no longer
updated.

Its full setup and usage instructions are in the
[v0.2.0 README](https://github.com/adenkushnir/text-to-calendar/tree/v0.2.0#readme). To stop an old install:

```bash
sh ~/text-to-calendar/boot/stop.sh
rm ~/.termux/boot/start-t2c.sh
```

## Credits

Built by [adenkushnir](https://github.com/adenkushnir), with help from [Claude](https://claude.com/claude-code),
Anthropic's AI assistant, using Claude Code. Claude helped with:

- **All the tests:** the Python test suite in `tests/` and the Kotlin unit tests for the Android app.
- **The design:** the app's look and screens, and the clickable mockups in `android/mockup/`.
- **Building the Android app**, Datebook, in `android/`.

Commits Claude made end with a `Co-Authored-By: Claude` line. All other commits are by adenkushnir.

Weather data by [Open-Meteo](https://open-meteo.com). Train data from NJ Transit's RailData API.
