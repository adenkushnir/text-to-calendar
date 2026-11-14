// The Settings screens from the mockup: a category menu, then Apps to watch, Claude & API key, Notifications &
// alerts, Privacy & history and About (Smart features and Automatic mode appear once their steps are built)
package io.github.adenkushnir.datebook.settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.DatebookCard
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.TopBar
import io.github.adenkushnir.datebook.calendar.PhoneCalendar
import io.github.adenkushnir.datebook.capture.WatchedApp
import io.github.adenkushnir.datebook.places.TRAVEL_MODES
import io.github.adenkushnir.datebook.summary.timeLabel
import io.github.adenkushnir.datebook.trains.Station
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GelSwitch
import io.github.adenkushnir.datebook.ui.GlassButton
import io.github.adenkushnir.datebook.ui.GlassField
import io.github.adenkushnir.datebook.ui.GlassTile
import io.github.adenkushnir.datebook.ui.PlainButton
import io.github.adenkushnir.datebook.ui.travelIcon
import kotlinx.coroutines.launch
const val GITHUB_URL = "https://github.com/adenkushnir/text-to-calendar"
const val PRIVACY_SUMMARY = "Datebook checks texts from the apps you switch on, on this phone. Texts without a date " +
    "are dropped straight away and leave no trace. If you sign in to Gmail, new emails in Primary and Updates are " +
    "checked the same way. Only a text with a date is sent to Claude, on its own, using your " +
    "API key (for group chats, with the group's name instead of the sender's). Datebook keeps the events it found " +
    "for a year, never the message text, and nothing is shared anywhere else. The one exception: when Claude " +
    "can't be reached, a text with a date waits on the phone (for up to a day) so Claude can check it later, and " +
    "its offline guess shows it until then. On Fridays, next week's event titles and times also go to Claude, " +
    "so it can write your week ahead."

// One item in the Settings menu: its glass tile icon and the icon's colour, its name, a one-line hint, and the page it
// opens
class SettingsItem(val icon: ImageVector, val tint: Color, val title: String, val hint: String, val open: () -> Unit)

// The Settings menu: one row per category on a glass card
@Composable
fun SettingsMenu(items: List<SettingsItem>, onBack: () -> Unit) {
    SettingsPage("Settings", onBack) {
        DatebookCard {
            // Show each category as a row that opens its page, with a line between rows
            items.forEachIndexed { index, item ->
                // A line above every row but the first
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = item.open).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GlassTile(item.icon, description = null, size = 34.dp, tint = item.tint)
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Muted(item.hint)
                    }
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                }
            }
        }
    }
}

// Apps to watch: an on/off switch for each messaging app Datebook can read
@Composable
fun AppsPage(apps: List<WatchedApp>, isOn: (String) -> Boolean, setOn: (String, Boolean) -> Unit, onBack: () -> Unit) {
    SettingsPage("Apps to watch", onBack) {
        Muted("Datebook only reads texts from the apps switched on here. Everything else is ignored.")
        DatebookCard {
            // One switch per app
            for (app in apps) {
                var on by remember(app.packageName) { mutableStateOf(isOn(app.packageName)) }
                SwitchRow(app.label, on) { checked ->
                    setOn(app.packageName, checked)
                    on = checked
                }
            }
        }
    }
}

// Calendars: which of the phone's calendars Datebook reads for clash warnings and skipping plans already added, or
// a button to allow calendar access
@Composable
fun CalendarsPage(
    access: Boolean,
    calendars: List<PhoneCalendar>,
    isOn: (Long) -> Boolean,
    setOn: (Long, Boolean) -> Unit,
    askAccess: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Calendars", onBack) {
        Muted("Datebook reads the calendars switched on here to warn about clashes and skip plans you've already " +
            "added. It never adds, changes or deletes anything.")

        // Without access there's nothing to list, only a way to allow it
        if (!access) {
            DatebookCard {
                Text("Datebook can't see your calendar yet.", fontWeight = FontWeight.SemiBold)
                GelButton(onClick = askAccess) {
                    Text("Allow")
                }
                Muted("If nothing happens, allow Calendar in Android's settings for Datebook.")
            }
            return@SettingsPage
        }
        DatebookCard {
            // Say so when the phone has no calendars
            if (calendars.isEmpty()) {
                Muted("No calendars found on this phone.")
            }

            // One switch per calendar, with the account it belongs to
            for (calendar in calendars) {
                var on by remember(calendar.id) { mutableStateOf(isOn(calendar.id)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(calendar.name.ifBlank { "Calendar" })
                        Muted(calendar.account)
                    }
                    GelSwitch(checked = on, onCheckedChange = { checked ->
                        setOn(calendar.id, checked)
                        on = checked
                    })
                }
            }
        }
    }
}

// Claude & API key: the saved key (masked), replacing it, and which model to use
@Composable
fun ClaudePage(
    maskedKey: String?,
    model: String,
    onModel: (String) -> Unit,
    checkAndSaveKey: suspend (String) -> String?,
    onBack: () -> Unit,
) {
    SettingsPage("Claude & API key", onBack) {
        SectionLabel("API key")
        Muted(maskedKey ?: "No key saved")
        KeyReplaceBox(checkAndSaveKey)
        SectionLabel("Model")
        DatebookCard {
            // One choice per model
            for (option in MODEL_OPTIONS) {
                val pick = Modifier.fillMaxWidth().clickable { onModel(option.id) }
                Row(pick, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(option.name, fontWeight = FontWeight.SemiBold)
                        Muted(option.note)
                    }
                    RadioButton(selected = model == option.id, onClick = { onModel(option.id) })
                }
            }
        }
    }
}

// Look: the Moving backgrounds switch
@Composable
fun LookPage(moving: Boolean, setMoving: (Boolean) -> Unit, onBack: () -> Unit) {
    SettingsPage("Look", onBack) {
        Muted("Datebook's scenes follow the real sun all day. Moving backgrounds add rippling water, swaying plants, " +
            "glints, bubbles and fish.")
        DatebookCard {
            SwitchRow("Moving backgrounds", moving, setMoving)
            Muted("They pause in battery saver and when Android's animations are turned off. Switched off, the scenes " +
                "still change with the time of day.")
        }
    }
}

// Places & travel: the Google Maps key, the default way of getting around, the home address travel times fall back to,
// and location access
@Composable
fun PlacesPage(
    maskedKey: String?,
    saveKey: (String) -> Unit,
    mode: String,
    onMode: (String) -> Unit,
    home: String,
    saveHome: (String) -> Unit,
    location: Boolean,
    askLocation: () -> Unit,
    allTheTime: Boolean,
    askAllTheTime: () -> Unit,
    onBack: () -> Unit,
) {
    var homeInput by remember { mutableStateOf(home) }
    var homeSaved by remember { mutableStateOf(false) }
    SettingsPage("Places & travel", onBack) {
        SectionLabel("Google Maps key")
        Muted(maskedKey ?: "No key saved. Without one, places stay as the text wrote them.")
        KeyReplaceBox { key ->
            saveKey(key)
            null
        }
        SectionLabel("Getting there")
        DatebookCard {
            // One choice per travel mode
            for (option in TRAVEL_MODES) {
                val pick = Modifier.fillMaxWidth().clickable { onMode(option.id) }
                val gap = Arrangement.spacedBy(10.dp)
                Row(pick, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
                    Icon(travelIcon(option.id), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(option.name, modifier = Modifier.weight(1f))
                    RadioButton(selected = mode == option.id, onClick = { onMode(option.id) })
                }
            }
        }
        Muted("Every plan starts with this. You can change it for one plan on its card.")
        SectionLabel("Home")
        GlassField(value = homeInput, onValueChange = { homeInput = it; homeSaved = false },
            label = { Text("Home address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        GlassButton(onClick = { saveHome(homeInput); homeSaved = true }, enabled = homeInput.trim() != home.trim()) {
            Text("Save")
        }

        // Say so once the address is saved
        if (homeSaved) {
            Muted("Saved.")
        }
        Muted("Used to find places near you, and for travel times when your location isn't known.")
        DatebookCard {
            StatusRow("Your location", location, askLocation)
            StatusRow("All the time (for leave-by alerts)", allTheTime, askAllTheTime)
        }
        Muted("Leave-by alerts come 10 minutes before you need to leave for an event with a place: its travel time " +
            "plus 5 spare minutes. They measure from where you are, or from home when your location isn't known.")
        Muted("Finding a place sends Google only its name (like \"Luigi's\") and roughly where you are, never your " +
            "texts.")
    }
}

// Flights & trains: the flight-data key (AeroDataBox through RapidAPI), the NJ Transit developer account, and the saved
// trip for the Next trains card
@Composable
fun FlightsPage(
    maskedKey: String?,
    saveKey: (String) -> Unit,
    njtUser: String?,
    saveNjt: (String, String) -> Unit,
    stations: List<Station>,
    trip: Pair<String, String>?,
    setTrip: (String, String) -> Unit,
    loadStations: suspend () -> List<Station>,
    onBack: () -> Unit,
) {
    SettingsPage("Flights & trains", onBack) {
        SectionLabel("Flight data (AeroDataBox)")
        Muted(maskedKey ?: "No key saved. Flights get a status link instead of live times.")
        KeyReplaceBox { key ->
            saveKey(key)
            null
        }
        Muted("To get a free key: make a RapidAPI account, subscribe to AeroDataBox's free Basic plan, and paste " +
            "your RapidAPI key here. Datebook sends only flight numbers and dates.")
        SectionLabel("NJ Transit")
        NjtAccountBox(njtUser, saveNjt)
        SectionLabel("Next trains trip")
        TripBox(stations, trip, setTrip, loadStations)
        Muted("Datebook sends NJ Transit only station codes. Your account details stay encrypted on this phone.")
    }
}

// The NJ Transit developer account: who's saved, and boxes to save a username and password
@Composable
private fun NjtAccountBox(njtUser: String?, saveNjt: (String, String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(njtUser) }
    Muted(saved?.let { "Account saved: $it" } ?: "No account saved. Register at developer.njtransit.com (RailData " +
        "and GTFS-RAIL), then enter it here.")
    GlassField(value = user, onValueChange = { user = it }, label = { Text("NJ Transit username") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    GlassField(value = password, onValueChange = { password = it }, label = { Text("Password") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    GlassButton(enabled = user.isNotBlank() && password.isNotBlank(), onClick = {
        saveNjt(user.trim(), password)
        saved = user.trim()
        user = ""
        password = ""
    }) {
        Text("Save account")
    }
}

// The saved trip: a From and a To station picked from NJ Transit's list, or a button to load the list
@Composable
private fun TripBox(stations: List<Station>, trip: Pair<String, String>?, setTrip: (String, String) -> Unit,
    loadStations: suspend () -> List<Station>) {
    val scope = rememberCoroutineScope()
    var known by remember { mutableStateOf(stations) }
    var from by remember { mutableStateOf(trip?.first) }
    var to by remember { mutableStateOf(trip?.second) }
    var picking by remember { mutableStateOf<String?>(null) }  // "from" or "to" while choosing
    var loading by remember { mutableStateOf(false) }

    // Without stations, offer to load them
    if (known.isEmpty()) {
        Muted("NJ Transit's stations load after your account is saved.")
        GlassButton(enabled = !loading, onClick = {
            loading = true
            scope.launch {
                known = loadStations()
                loading = false
            }
        }) {
            Text(if (loading) "Loading…" else "Load stations")
        }
        return
    }
    DatebookCard {
        // One row for each end of the trip
        for ((label, code) in listOf("From" to from, "To" to to)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f))
                PlainButton(onClick = { picking = label }) {
                    Text(known.firstOrNull { it.code == code }?.name ?: "Choose")
                }
            }
        }
    }

    // Show the station list while choosing one end
    if (picking != null) {
        StationDialog(known, onDismiss = { picking = null }) { station ->
            // Save the end being chosen, and the trip once both ends are set
            if (picking == "From") {
                from = station.code
            } else {
                to = station.code
            }
            picking = null
            val start = from
            val end = to

            // Save the trip once both ends are chosen
            if (start != null && end != null) {
                setTrip(start, end)
            }
        }
    }
}

// A searchable list of stations in a dialog
@Composable
private fun StationDialog(stations: List<Station>, onDismiss: () -> Unit, onPick: (Station) -> Unit) {
    var search by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PlainButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Choose a station") },
        text = {
            Column {
                GlassField(value = search, onValueChange = { search = it }, label = { Text("Search") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    // One row per matching station
                    for (station in stations.filter { it.name.contains(search, ignoreCase = true) }) {
                        PlainButton(onClick = { onPick(station) }, modifier = Modifier.fillMaxWidth()) {
                            Text(station.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
    )
}

// Automatic mode: whether it's on (turned on and off in Android's accessibility settings), what it reads, and the
// apps it reads
@Composable
fun AutomaticPage(on: Boolean, apps: List<String>, openSettings: () -> Unit, onBack: () -> Unit) {
    SettingsPage("Automatic mode", onBack) {
        Muted("Automatic mode lets Datebook catch plans in texts you send, and in new messages while a chat is open " +
            "(those don't send notifications).")
        DatebookCard {
            StatusRow("Automatic mode", on, openSettings)
        }
        SectionLabel("What it reads")
        Muted("Only the apps switched on in Apps to watch: ${apps.joinToString(", ").ifBlank { "none yet" }}.")
        Muted("Only new messages and texts you send, while one of those apps is open. Never what's already showing " +
            "when you open a chat, never what you're typing, never other apps, and never screenshots.")
        Muted("New messages with a date go to Claude one at a time, like notifications. Group chats need a clear " +
            "date and send only the group's name.")
        SectionLabel("Turning it on")
        Muted("Tap TURN ON, then Installed apps (or Downloaded apps) → Datebook automatic mode → On. If Android says " +
            "\"Restricted setting\": Settings → Apps → Datebook → ⋮ → Allow restricted settings, then try again.")
    }
}

// Gmail: whether Datebook is signed in (and as which address), signing in or out, what it reads, and the weekly
// sign-in Google asks for; problem is what went wrong with the last sign-in ("" when nothing did)
@Composable
fun GmailPage(signedIn: Boolean, address: String, lapsed: Boolean, problem: String, gmailWatched: Boolean,
    onSignIn: () -> Unit, onSignOut: () -> Unit, onBack: () -> Unit) {
    SettingsPage("Gmail", onBack) {
        Muted("Datebook reads new emails for plans, like reservations, appointments, tickets, pickups and " +
            "deliveries, and offers each one like a text, with any confirmation code in the event's notes.")
        DatebookCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gmail")
                    val state = when {
                        !signedIn -> "Not signed in"
                        lapsed -> "Sign-in ended: sign in again"
                        address.isNotBlank() -> "Signed in as $address"
                        else -> "Signed in"
                    }
                    Muted(state)
                }

                // Offer to sign in (or again, once it lapsed), or to sign out
                if (!signedIn || lapsed) {
                    GelButton(onClick = onSignIn, small = true) {
                        Text("Sign in")
                    }
                } else {
                    GlassButton(onClick = onSignOut, small = true) {
                        Text("Sign out")
                    }
                }
            }

            // Say what went wrong with the last sign-in
            if (problem.isNotBlank()) {
                Muted(problem)
            }
        }

        // New emails are checked the moment they arrive only when Gmail's notifications are watched
        if (signedIn && !gmailWatched) {
            Muted("Turn on Gmail in Apps to watch so new emails are checked as soon as they arrive. Until then " +
                "they're checked every 15 minutes.")
        }
        SectionLabel("What it reads")
        Muted("Only new emails in Primary and Updates, never Promotions or Social, and never mail from before you " +
            "signed in. Only emails with a date go to Claude, one at a time. Datebook never keeps an email's text " +
            "and never sends, changes or deletes mail.")
        SectionLabel("Every week or so")
        Muted("Google ends the sign-in about every 7 days for apps like this one. Datebook then shows a quiet \"Sign " +
            "in to Gmail again\" notification; emails that arrived meanwhile are checked once you're back.")
    }
}

// A box to paste a new key, checked for free before it replaces the saved one
@Composable
private fun KeyReplaceBox(checkAndSaveKey: suspend (String) -> String?) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    GlassField(value = input, onValueChange = { input = it }, label = { Text("Paste a new key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    GlassButton(enabled = input.isNotBlank() && !busy, onClick = {
        busy = true
        scope.launch {
            val problem = checkAndSaveKey(input)
            result = if (problem == null) "Key replaced." else "That key didn't work: $problem"  // what happened
            input = if (problem == null) "" else input  // clear the box once the key is saved
            busy = false
        }
    }) {
        Text(if (busy) "Checking…" else "Replace key")  // show progress while checking
    }

    // Show what happened to the last key
    if (result.isNotEmpty()) {
        Muted(result)
    }
}

// Notifications & alerts: whether Datebook can read texts and show prompts, with buttons to fix either
@Composable
fun NotificationsPage(
    access: Boolean,
    canNotify: Boolean,
    openAccess: () -> Unit,
    openNotifications: () -> Unit,
    summaryOn: Boolean,
    summaryHour: Int,
    summaryMinute: Int,
    setSummary: (on: Boolean, hour: Int, minute: Int) -> Unit,
    onBack: () -> Unit,
    weekOn: Boolean = true,
    weekHour: Int = 18,
    weekMinute: Int = 0,
    setWeekAhead: (on: Boolean, hour: Int, minute: Int) -> Unit = { _, _, _ -> },
) {
    var on by remember { mutableStateOf(summaryOn) }
    var hour by remember { mutableIntStateOf(summaryHour) }
    var minute by remember { mutableIntStateOf(summaryMinute) }
    var picking by remember { mutableStateOf(false) }
    var weekIsOn by remember { mutableStateOf(weekOn) }
    var weekAt by remember { mutableStateOf(Pair(weekHour, weekMinute)) }
    var pickingWeek by remember { mutableStateOf(false) }
    SettingsPage("Notifications & alerts", onBack) {
        DatebookCard {
            StatusRow("Reading texts (notification access)", access, openAccess)
            StatusRow("Showing \"Add to calendar?\" prompts", canNotify, openNotifications)
        }
        SectionLabel("Morning summary")
        DatebookCard {
            SwitchRow("Morning summary", on) { checked ->
                on = checked
                setSummary(on, hour, minute)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Time", modifier = Modifier.weight(1f))
                PlainButton(onClick = { picking = true }, enabled = on) {
                    Text(timeLabel(hour, minute))
                }
            }
        }
        Muted("A silent notification with the day's events, only on days that have any. It stays until you swipe " +
            "it away.")
        SectionLabel("Friday week ahead")
        DatebookCard {
            SwitchRow("Week ahead on Fridays", weekIsOn) { checked ->
                weekIsOn = checked
                setWeekAhead(weekIsOn, weekAt.first, weekAt.second)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Time", modifier = Modifier.weight(1f))
                PlainButton(onClick = { pickingWeek = true }, enabled = weekIsOn) {
                    Text(timeLabel(weekAt.first, weekAt.second))
                }
            }
        }
        Muted("A quiet look at the next 7 days and the bills due, every Friday evening. Loose plans and bills are " +
            "nudged on their own, and plans still waiting get a \"Still on?\" the evening before.")
        Muted("Alerts for problems (like a rejected key) arrive in a later update.")
    }

    // Show the clock to pick the week ahead's time
    if (pickingWeek) {
        TimeDialog(weekAt.first, weekAt.second, onDismiss = { pickingWeek = false }) { pickedHour, pickedMinute ->
            weekAt = Pair(pickedHour, pickedMinute)
            pickingWeek = false
            setWeekAhead(weekIsOn, pickedHour, pickedMinute)
        }
    }

    // Show the clock to pick a new time
    if (picking) {
        TimeDialog(hour, minute, onDismiss = { picking = false }) { pickedHour, pickedMinute ->
            hour = pickedHour
            minute = pickedMinute
            picking = false
            setSummary(on, hour, minute)
        }
    }
}

// A clock in a dialog for choosing a time of day
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PlainButton(onClick = { onPick(state.hour, state.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            PlainButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = { TimePicker(state) },
    )
}

// Privacy & history: what Datebook keeps, and clearing old suggestions
@Composable
fun PrivacyPage(clearHistory: suspend () -> Int, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    SettingsPage("Privacy & history", onBack) {
        Muted(PRIVACY_SUMMARY)
        SectionLabel("History")
        Muted("Clear history deletes ignored suggestions and added plans that are already over. Plans still waiting " +
            "or coming up are kept.")
        GlassButton(onClick = { confirming = true }) {
            Text("Clear history")
        }

        // Show how many were deleted
        if (result.isNotEmpty()) {
            Muted(result)
        }

        // Ask before deleting anything
        if (confirming) {
            AlertDialog(
                onDismissRequest = { confirming = false },
                title = { Text("Clear history?") },
                text = { Text("Ignored suggestions and finished plans will be deleted. This can't be undone.") },
                confirmButton = {
                    PlainButton(onClick = {
                        confirming = false
                        scope.launch { result = "Deleted ${clearHistory()} old suggestion(s)." }
                    }) {
                        Text("Clear")
                    }
                },
                dismissButton = {
                    PlainButton(onClick = { confirming = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

// About: version, GitHub link, privacy summary, and the pretend-a-text box in debug builds only
@Composable
fun AboutPage(version: String, openGitHub: () -> Unit, pretend: (suspend (String, String) -> List<String>)?,
    onBack: () -> Unit) {
    SettingsPage("About Datebook", onBack) {
        DatebookCard {
            Text("Datebook $version", fontWeight = FontWeight.SemiBold)
            PlainButton(onClick = openGitHub) {
                Text(GITHUB_URL.removePrefix("https://"))
            }
        }
        SectionLabel("Privacy")
        Muted(PRIVACY_SUMMARY)

        // The test tool only exists in debug builds
        if (pretend != null) {
            PretendBox(pretend)
        }
    }
}

// Debug builds only: type a text and sender to run them through Datebook as if a text arrived
@Composable
private fun PretendBox(pretend: suspend (String, String) -> List<String>) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("dinner fri 7pm at joes") }
    var sender by remember { mutableStateOf("Test sender") }
    var results by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    SectionLabel("Test tools (debug builds only)")
    DatebookCard {
        Text("Pretend a text arrived", fontWeight = FontWeight.SemiBold)
        GlassField(value = text, onValueChange = { text = it }, label = { Text("Text") },
            modifier = Modifier.fillMaxWidth())
        GlassField(value = sender, onValueChange = { sender = it }, label = { Text("From") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        GelButton(enabled = !busy && text.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                results = pretend(text, sender)
                busy = false
            }
        }) {
            Text(if (busy) "Asking Claude…" else "Send through Datebook")  // show progress while waiting
        }

        // Show each result line, like the log
        for (line in results) {
            Text("-> $line", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

// A row with a label and an on/off switch
@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        GelSwitch(checked = on, onCheckedChange = onChange)
    }
}

// A row saying whether something is on, with a button to change it in Android's settings
@Composable
private fun StatusRow(label: String, on: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Muted(if (on) "On" else "Off")
        }
        PlainButton(onClick = open) {
            Text(if (on) "Settings" else "Turn on")  // offer to fix it when it's off
        }
    }
}

// A Settings page: the teal bar with a back arrow, then the page's content in a scrolling column
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
        TopBar(title, onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
