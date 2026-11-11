// Temporary test tools for debug builds: notification access, the Claude API key, and pretend a text arrived
// (replaced by the setup and settings screens in step 5)
package io.github.adenkushnir.datebook
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.adenkushnir.datebook.capture.hasNotificationAccess
import io.github.adenkushnir.datebook.pipeline.IncomingText
import io.github.adenkushnir.datebook.pipeline.maskKey
import io.github.adenkushnir.datebook.pipeline.summary
import java.time.LocalDateTime
import kotlinx.coroutines.launch

// A card with a key box and a "pretend a text arrived" box, showing what Datebook did with the text
@Composable
fun TestToolsCard(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf(graph.keyStore.load()?.let { maskKey(it) } ?: "none saved") }
    var text by remember { mutableStateOf("dinner fri 7pm at joes") }
    var sender by remember { mutableStateOf("Test sender") }
    var results by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var access by remember { mutableStateOf(hasNotificationAccess(context)) }
    SectionLabel("Test tools (debug builds only)")
    DatebookCard {
        Muted("Notification access: ${if (access) "on" else "off"}")  // whether Datebook can read texts
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) {
            Text("Open notification access settings")
        }
        Button(onClick = { access = hasNotificationAccess(context) }) {
            Text("Check access again")
        }
        Muted("Saved key: $savedKey")
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Paste Claude API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = keyInput.isNotBlank(), onClick = {
            graph.keyStore.save(keyInput)
            savedKey = maskKey(keyInput.trim())
            keyInput = ""
        }) {
            Text("Save key")
        }
        Text("Pretend a text arrived", modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = sender, onValueChange = { sender = it }, label = { Text("From") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && text.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                val outcome = graph.pipeline.handle(IncomingText(sender, text, LocalDateTime.now(), "simulated"))
                results = outcome.lines + outcome.events.map { "prompt: ${summary(it)}" }  // what a prompt would say
                busy = false
            }
        }) {
            Text(if (busy) "Asking Claude…" else "Send through Datebook")  // show progress while waiting
        }

        // Show each result line, like the Python version's log
        for (line in results) {
            Text("-> $line", fontFamily = FontFamily.Monospace)
        }
    }
}
