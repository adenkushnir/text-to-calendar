// The step-by-step setup pages from the mockup: notification access, notifications and the Claude key are required;
// battery, calendar and the Maps key can be skipped; the last page offers a sample prompt
package io.github.adenkushnir.datebook.setup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.GlassTile
import io.github.adenkushnir.datebook.ui.TILE_AQUA
import io.github.adenkushnir.datebook.ui.TILE_BLUE
import io.github.adenkushnir.datebook.ui.TILE_LEAF
import io.github.adenkushnir.datebook.ui.TILE_WARN
import kotlinx.coroutines.launch

// What each setup page can do; MainActivity supplies these because they open Android settings and permission pop-ups
class SetupActions(
    val openAccess: () -> Unit,
    val askNotifications: () -> Unit,
    val checkAndSaveKey: suspend (String) -> String?,
    val openBattery: () -> Unit,
    val askCalendar: () -> Unit,
    val saveMapsKey: (String) -> Unit,
)

// One setup page: its icon and the icon's colour, title, explanation, whether it must be done, and how to tell it is
// done
private class SetupPage(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val text: String,
    val required: Boolean,
    val done: (SetupStatus) -> Boolean,
)
private val PAGES = listOf(
    SetupPage(DatebookIcons.Bell, TILE_BLUE, "Let Datebook read your notifications",
        "So it can spot plans in texts as they arrive. Texts without a date never leave your phone.", true) {
        it.access
    },
    SetupPage(DatebookIcons.Check, TILE_LEAF, "Allow Datebook to show notifications",
        "That's how \"Add to calendar?\" suggestions reach you.", true) { it.notifications },
    SetupPage(DatebookIcons.Key, TILE_AQUA, "Paste your Claude API key",
        "Create one at console.anthropic.com. It's stored encrypted on this phone and never shared.", true) { it.key },
    SetupPage(DatebookIcons.Bolt, TILE_WARN, "Keep Datebook running",
        "Set battery to Unrestricted so Samsung doesn't put Datebook to sleep. It uses almost no battery: Android " +
            "tells it about new texts.", false) { it.battery },
    SetupPage(DatebookIcons.Calendar, TILE_BLUE, "Let Datebook see your calendar",
        "For clash warnings, changed plans and your morning summary (coming in a later update). Datebook never adds " +
            "or changes events itself.", false) { it.calendar },
    SetupPage(DatebookIcons.Pin, TILE_LEAF, "Add a Google Maps key",
        "For full addresses, travel times and \"leave by\" alerts (coming in a later update). You can skip this and " +
            "add it later in Settings.", false) { it.mapsKey },
)

// Show the setup pages one at a time, then the "You're set" page
@Composable
fun SetupScreen(status: SetupStatus, actions: SetupActions, onSample: () -> Unit, onFinish: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding()  // see-through, so the scene behind shows
            .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // After the last step, show the finish page
        if (index >= PAGES.size) {
            FinishPage(onSample, onFinish, onBack = { index = PAGES.size - 1 })
            return@Column
        }
        val page = PAGES[index]
        val done = page.done(status)
        Progress(index)
        SectionLabel("Step ${index + 1} of ${PAGES.size}${if (page.required) "" else " · optional"}")
        Art(page.icon, page.tint)
        Text(page.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Muted(page.text)
        PageAction(index, status, actions)

        // Show when this step is done
        if (done) {
            Text("✓ Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { index -= 1 }, enabled = index > 0) {
                Text("BACK")
            }
            val label = if (done || page.required) "NEXT" else "SKIP"  // optional steps can be skipped
            Button(onClick = { index += 1 }, enabled = done || !page.required) {
                Text(label)
            }
        }
    }
}

// The button (and box) each page needs to get its step done
@Composable
private fun PageAction(index: Int, status: SetupStatus, actions: SetupActions) {
    // Pick the action for this page
    when (index) {
        0 -> {
            Button(onClick = actions.openAccess) {
                Text("OPEN SETTINGS")
            }
            Muted("If Android says \"Restricted setting\": Settings → Apps → Datebook → ⋮ → Allow restricted " +
                "settings, then try again.")
        }
        1 -> Button(onClick = actions.askNotifications, enabled = !status.notifications) {
            Text("ALLOW")
        }
        2 -> KeyBox("Paste Claude API key", "CHECK KEY", actions.checkAndSaveKey)
        3 -> Button(onClick = actions.openBattery) {
            Text("OPEN BATTERY SETTINGS")
        }
        4 -> Button(onClick = actions.askCalendar, enabled = !status.calendar) {
            Text("ALLOW")
        }
        else -> KeyBox("Paste Maps API key", "SAVE") { key ->
            actions.saveMapsKey(key)
            null
        }
    }
}

// A hidden-text box for a key with a button that checks or saves it, showing the result underneath
@Composable
private fun KeyBox(label: String, button: String, submit: suspend (String) -> String?) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Button(enabled = input.isNotBlank() && !busy, onClick = {
        busy = true
        scope.launch {
            val problem = submit(input)
            result = if (problem == null) "Saved." else "That key didn't work: $problem"  // what happened
            input = if (problem == null) "" else input  // clear the box once the key is saved
            busy = false
        }
    }) {
        Text(if (busy) "CHECKING…" else button)  // show progress while checking
    }

    // Show what happened to the last key
    if (result.isNotEmpty()) {
        Muted(result)
    }
}

// The six-part progress bar, filled up to the current step
@Composable
private fun Progress(index: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Draw one segment per step
        for (step in PAGES.indices) {
            val color = if (step <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Box(Modifier.weight(1f).height(4.dp).background(color, MaterialTheme.shapes.extraSmall))
        }
    }
}

// The large glass tile icon at the top of each page
@Composable
private fun Art(icon: ImageVector, tint: Color) {
    GlassTile(icon, description = null, size = 76.dp, tint = tint)
}

// The last page: everything needed is done, with an optional sample prompt
@Composable
private fun FinishPage(onSample: () -> Unit, onFinish: () -> Unit, onBack: () -> Unit) {
    Art(DatebookIcons.Check, TILE_LEAF)
    Text("You're set", fontWeight = FontWeight.Bold, fontSize = 22.sp)
    Muted("Datebook now watches your texts for plans. When one has a date, you'll get an \"Add to calendar?\" " +
        "prompt. Want to see what one looks like?")
    OutlinedButton(onClick = onSample) {
        Text("SHOW ME A SAMPLE")
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = onBack) {
            Text("BACK")
        }
        Button(onClick = onFinish) {
            Text("GO TO DATEBOOK")
        }
    }
}
