// The Settings screens from the mockup: a category menu, then Apps to watch, Claude & API key, Notifications &
// alerts, Privacy & history and About (Smart features and Automatic mode appear once their steps are built)
package io.github.adenkushnir.datebook.settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
const val GITHUB_URL = "https://github.com/adenkushnir/text-to-calendar"
const val PRIVACY_SUMMARY = "Datebook checks texts from the apps you switch on, on this phone. Texts without a date " +
    "are dropped straight away and leave no trace. Only a text with a date is sent to Claude, on its own, using your " +
    "API key (for group chats, with the group's name instead of the sender's). Datebook keeps the events it found " +
    "for a year, never the message text, and nothing is shared anywhere else."

// One item in the Settings menu: a short icon, its name, a one-line hint, and the page it opens
class SettingsItem(val icon: String, val title: String, val hint: String, val open: () -> Unit)

// The Settings menu: one row per category
@Composable
fun SettingsMenu(items: List<SettingsItem>, onBack: () -> Unit) {
    SettingsPage("Settings", onBack) {
        // Show each category as a row that opens its page
        for (item in items) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = item.open).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val tile = MaterialTheme.colorScheme.primaryContainer
                Box(Modifier.size(28.dp).background(tile, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center) {
                    Text(item.icon, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold)
                    Muted(item.hint)
                }
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
                Button(onClick = askAccess) {
                    Text("ALLOW")
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
                    Switch(checked = on, onCheckedChange = { checked ->
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

// A box to paste a new key, checked for free before it replaces the saved one
@Composable
private fun KeyReplaceBox(checkAndSaveKey: suspend (String) -> String?) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Paste a new key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    OutlinedButton(enabled = input.isNotBlank() && !busy, onClick = {
        busy = true
        scope.launch {
            val problem = checkAndSaveKey(input)
            result = if (problem == null) "Key replaced." else "That key didn't work: $problem"  // what happened
            input = if (problem == null) "" else input  // clear the box once the key is saved
            busy = false
        }
    }) {
        Text(if (busy) "CHECKING…" else "REPLACE KEY")  // show progress while checking
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
    onBack: () -> Unit,
) {
    SettingsPage("Notifications & alerts", onBack) {
        DatebookCard {
            StatusRow("Reading texts (notification access)", access, openAccess)
            StatusRow("Showing \"Add to calendar?\" prompts", canNotify, openNotifications)
        }
        Muted("Alerts for problems (like a rejected key) and the morning summary arrive in a later update.")
    }
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
        OutlinedButton(onClick = { confirming = true }) {
            Text("CLEAR HISTORY")
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
                    TextButton(onClick = {
                        confirming = false
                        scope.launch { result = "Deleted ${clearHistory()} old suggestion(s)." }
                    }) {
                        Text("CLEAR")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirming = false }) {
                        Text("CANCEL")
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
            TextButton(onClick = openGitHub) {
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
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = sender, onValueChange = { sender = it }, label = { Text("From") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && text.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                results = pretend(text, sender)
                busy = false
            }
        }) {
            Text(if (busy) "ASKING CLAUDE…" else "SEND THROUGH DATEBOOK")  // show progress while waiting
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
        Switch(checked = on, onCheckedChange = onChange)
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
        TextButton(onClick = open) {
            Text(if (on) "SETTINGS" else "TURN ON")  // offer to fix it when it's off
        }
    }
}

// A Settings page: the teal bar with a back arrow, then the page's content in a scrolling column
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(title, onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
