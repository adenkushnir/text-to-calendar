// The Notes tab: a search box, the reminders still to go off (when, and how they repeat), the notes (tap one to edit
// it), and a box to type a note or "remind me to…", which Datebook turns into a reminder, a plan or a note
package io.github.adenkushnir.datebook.notes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.adenkushnir.datebook.DatebookCard
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.TopBar
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.reminderWhen
import io.github.adenkushnir.datebook.data.repeatLabel
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GlassField
import io.github.adenkushnir.datebook.ui.GlassIconButton
import io.github.adenkushnir.datebook.ui.PlainButton
import java.time.ZoneId
import kotlinx.coroutines.launch

// What the Notes tab's buttons do: save what was typed (returning what was made of it), delete a reminder or note, and
// change a note's text
class NotesActions(
    val onSave: suspend (String) -> String = { "" },
    val onDelete: (Long) -> Unit = {},
    val onEditNote: (FollowUp, String) -> Unit = { _, _ -> },
)

// Return true if a reminder or note matches what's searched for (its title or text, ignoring case)
fun matches(item: FollowUp, search: String): Boolean {
    return search.isBlank() || item.title.contains(search.trim(), ignoreCase = true) ||
        item.body.contains(search.trim(), ignoreCase = true)
}

// Show the Notes tab
@Composable
fun NotesScreen(reminders: List<FollowUp>, notes: List<FollowUp>, zone: ZoneId, actions: NotesActions,
    onSettings: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FollowUp?>(null) }
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
        TopBar("Notes", onSettings = onSettings)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassField(value = search, onValueChange = { search = it }, label = { Text("Search notes and reminders") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val shownReminders = reminders.filter { matches(it, search) }
            val shownNotes = notes.filter { matches(it, search) }
            SectionLabel("Reminders")
            DatebookCard {
                // Say so when there are none
                if (shownReminders.isEmpty()) {
                    val empty = if (search.isBlank()) "No reminders. Type \"remind me to…\" below."
                        else "No reminders match."
                    Muted(empty)
                }

                // One row per reminder, a line between them
                shownReminders.forEachIndexed { index, reminder ->
                    // A line above every reminder but the first
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reminder.title, fontWeight = FontWeight.SemiBold)
                            val repeat = repeatLabel(reminder.repeat)
                            Muted(reminderWhen(reminder, zone) + if (repeat.isBlank()) "" else " · $repeat")
                        }
                        GlassIconButton(DatebookIcons.Close, "Delete", onClick = { actions.onDelete(reminder.id) })
                    }
                }
            }
            SectionLabel("Notes")
            DatebookCard {
                // Say so when there are none
                if (shownNotes.isEmpty()) {
                    Muted(if (search.isBlank()) "No notes yet." else "No notes match.")
                }

                // One row per note, a line between them; tapping one edits it
                shownNotes.forEachIndexed { index, note ->
                    // A line above every note but the first
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { editing = note }) {
                            Text(note.title, fontWeight = FontWeight.SemiBold)
                            val rest = note.body.lines().drop(1).joinToString(" ").trim()

                            // The start of the rest of the note, when it has more than one line
                            if (rest.isNotBlank()) {
                                Muted(rest.take(90))
                            }
                        }
                        GlassIconButton(DatebookIcons.Close, "Delete", onClick = { actions.onDelete(note.id) })
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassField(value = typed, onValueChange = { typed = it },
                label = { Text("A note, or \"remind me to…\"") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GelButton(enabled = typed.isNotBlank() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        result = actions.onSave(typed)
                        typed = ""
                        busy = false
                    }
                }) {
                    Text(if (busy) "Saving…" else "Save")
                }

                // Say what was made of the last thing saved
                if (result.isNotBlank()) {
                    Muted(result)
                }
            }
        }
    }

    // Edit a note in a dialog
    editing?.let { note ->
        NoteEditor(note, onDismiss = { editing = null }) { text ->
            actions.onEditNote(note, text)
            editing = null
        }
    }
}

// A dialog to change a note's text
@Composable
private fun NoteEditor(note: FollowUp, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(note.body.ifBlank { note.title }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit note") },
        text = { GlassField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            PlainButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            PlainButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
