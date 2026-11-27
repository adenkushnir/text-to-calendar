# Datebook

An Android app that turns plans in your texts and emails into calendar events, and keeps your day in one place.

When a text says "dinner fri 7pm at Luigi's", Datebook asks Claude to read it and shows an **Add to calendar?**
notification. Tapping **Add** saves it straight to your calendar (your Google account's main calendar, or the one
picked in Settings → Calendars) with its title, time, full address and notes saying who it came from, then opens it
in your calendar app. Nothing is saved until you tap Add, and Datebook never deletes anything.

Datebook started as a Python script running in Termux (see [Legacy](#legacy-the-termux-script-v020) at the end).
It's a full Kotlin app, built with Jetpack Compose, in [`android/`](android/); the latest version is 1.1.1.

## What it does

**Catching plans**
- Reads new messages from the apps you choose: Google Messages, Samsung Messages, GroupMe, WhatsApp, Instagram,
  Discord and Gmail. Only messages with a date or time in them are sent to Claude; group chats need a clear one.
- **Amazon Shopping and Shop:** only their shipping notifications are read, and become packages (no tracking number
  needed); an email and a notification about the same package make one entry, and you're told when its day moves.
- **Share → Datebook** for any text, picture or PDF. A schedule (a syllabus, a team schedule) becomes a list you
  tick through and add in one go.
- **Gmail sign-in** (read-only) for reservations, tickets and appointments, with the confirmation code in the notes.
- **Automatic mode** (optional, off by default): also catches texts you send and new messages in an open chat.
- **Offline backup:** without internet, an on-phone guess waits on Home and Claude checks it once you're back online.

**Knowing your calendar**
- Warns about clashes, skips plans already on your calendar, and turns "moved to 8" or "cancelled" into a
  **Move Dinner to 8 PM?** prompt that opens the event.
- **Leave-by alerts** with travel time (drive, transit, walk, bike, campus bus or Uber) and **Open in Maps**. Each
  plan can have its own travel mode and **Be there early** minutes (on its card, or by telling Deliberate).
- **Flights** filled in from live flight data, with delay and gate alerts, and a leave-by alert for the airport: be
  there 60 minutes early (2 hours abroad) unless you say otherwise. One plan per flight, however many emails mention it.
- **NJ Transit** trains toward a plan: by transit, the leave-by comes from your train (its time, less the walk or
  drive to the station). If your train is cancelled or too late, Datebook picks another and updates the alert, or
  says no train makes it and gives the drive. **Track** links for UPS, FedEx, USPS, Amazon and DHL packages.
- **Rutgers campus buses** (Rutgers' own TripShot timetable and live times, fetched weekly, no account): by transit,
  a walk in your trip becomes a campus bus when the bus lets you leave at least 3 minutes later or get there 3 minutes
  sooner, with stops up to a 15-minute walk away; it works with NJ Transit too. The **Bus** travel choice (or "I'm
  taking the bus" in Deliberate) always takes one. The bus step shows its route in Rutgers' colour and "Scheduled",
  or its live time ("Live · 2 min late") in the 2 hours before; tap it for the other buses.
- **Uber:** the **Uber** travel choice (or "I'm taking an Uber, remind me to order it 10 minutes before" in
  Deliberate) times the ride as a drive plus 5 minutes for the car to reach you, and the leave-by alert becomes
  **Order your Uber**, with an **Open Uber** button, 10 minutes before it should leave (or as many as you said).
  Nothing is booked for you.
- Bills and assignments become reminders, not events. Loose plans ("we should get lunch next week") get a nudge to
  pin them down.

**Your day**
- **Home:** the Today card with the weather, today's events, reminders, bills and assignments, the next one lit and
  finished ones dimmed; plans waiting for you; the next few days; and a live NJ Transit board for your trip.
- **Lists:** packages on the way, assignments due in the next 5 days, trips, and bills. Bills, packages and trip
  bookings keep a link (Pay, Order, Booking), picked from the email or text or pasted in.
- **Notes:** notes and reminders. A reminder is a notification at a time, typed in Datebook or sent in a text
  ("remind me to call the dentist tomorrow at 9").
- **Widgets:** Next trains (the train that just left and the next 3; tapping a train opens the NJ Transit app) and
  Lists at a glance. They use your wallpaper's colours.
- **Quiet notifications:** an 8 AM summary on days with events, a Friday week ahead, and the evening before, what to
  bring ("Bring: cleats, $20") and a heads-up when an outdoor plan looks rainy or cold.
- **Problem alerts:** one quiet notification (and a banner on Home) if your Claude key stops working, Claude keeps
  failing, or notification access is turned off. Both clear themselves.

**Deliberate and school**
- **Deliberate** (a tab): ask about your plans, or tell Datebook how to plan them ("every practice takes 50 minutes to
  get to"). It looks things up, asks follow-up questions with choices, and shows a preview before it changes
  anything; every change can be undone. Its rules cover travel times, arriving early, what to bring, reminders before
  events, plans to ignore and habits Claude should assume. It can also search the web for anything else.
  Give it a flight number ("my flight tomorrow is B6 1234, I'm taking the train, 30 minutes early") and it looks the
  flight up live (asking which leg when the number flies more than one) and shows a flight card to confirm; "I'm
  driving" or "be there early" works for any one plan. It can change events already on your calendar (title, day
  and time, place, notes), updating the one you mean instead of adding a second; every change can be undone.
- **Canvas** (Settings → School): your Canvas Calendar Feed link brings assignments, quizzes and exams into Due soon,
  with a notification when one moves or is removed, and offers class events like plans from texts.
- **Fencing** (Settings → Fencing, off to start): men's foil tournaments to follow and watch, found by Claude's web
  search, as tiles at the bottom of Home with Watch and Results, and a quiet notification when a stream goes live.

## Nestor at home

[Nestor](https://github.com/adenkushnir/nestor) is a home speaker and wall screen app. On the home Wi-Fi, Datebook
finds it by itself (mDNS, `_nestor._tcp`) and, once paired, keeps it up to date:

- **Pairing:** on the computer running Nestor, run `python -m nestor --pair`, then type the 6-digit code in Settings →
  Nestor. Android 17 also asks to let Datebook find devices on your Wi-Fi.
- **What Nestor gets:** a snapshot of your waiting plans, lists, notes, reminders, train trip and the next 14 days of
  your calendar (with the train picked for each leave-by and each flight's live status), and this week's men's foil
  when fencing is on, sent again
  whenever something changes. Nestor uses it for its wall screen and its voice assistant, so it needs no Google login
  of its own.
- **What Nestor says:** leave-by alerts, travel changes, reminders and follow-ups, and the evening heads-ups. The
  morning summary, the week ahead and new plans aren't spoken.
- **What Nestor can ask for:** show a plan's Add to calendar? prompt, ignore a plan, mark a bill, assignment or
  reminder done, or read a new plan like something you typed. Adding still ends in your calendar app's **Save**.
- **Away from home** Datebook sends nothing and keeps nothing to send later. The link runs alongside notification
  access, and can be switched off or unpaired in Settings → Nestor.

## Install

Datebook needs Android 10 or newer.

1. Download `datebook-1.1.1.apk` from the [latest release](https://github.com/adenkushnir/text-to-calendar/releases/latest)
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

- Messages without a date or time never leave the phone, with one exception: a one-to-one message that sounds like a
  change ("sorry, have to cancel") is sent too when you have plans in the next 2 weeks, along with those plans' titles
  and times, so Datebook can offer to move or cancel one. A message is sent to Claude on its own: never other
  messages from the chat, and for group chats only the group's name, not the sender's.
- Datebook keeps what it suggested and what you did with it for 1 year, with the message each plan came from (so
  Deliberate can quote it); turn that off or delete the texts in Settings → Privacy. What Deliberate looks up for a
  question goes to Claude with it.
- With Nestor paired, your plans, lists, notes, reminders, the next 14 days of calendar events and this week's men's
  foil go to Nestor on your home Wi-Fi only, never over the internet.
- Other services only get what they need: place names (Google Maps), flight numbers and dates (AeroDataBox), station
  codes (NJ Transit), and your rough location for the weather ([Open-Meteo](https://open-meteo.com), no account).
- Rutgers bus timetables and live times are only downloaded (from TripShot); nothing about you is sent.

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
