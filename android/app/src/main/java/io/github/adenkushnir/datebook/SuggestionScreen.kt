// The suggestion card from the mockup: a read-only summary of one suggestion, with Add and Ignore
package io.github.adenkushnir.datebook
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.places.TRAVEL_MODES
import io.github.adenkushnir.datebook.prompts.changedNote
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.prompts.isChange
import io.github.adenkushnir.datebook.prompts.promptTitle
import io.github.adenkushnir.datebook.tracking.trackLinkFor
import io.github.adenkushnir.datebook.trains.TrainChoices
import io.github.adenkushnir.datebook.trains.TrainRow

// Show one suggestion; null while it is loading or after it was deleted
@Composable
fun SuggestionScreen(
    suggestion: Suggestion?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onIgnore: () -> Unit,
    onOpenMaps: (String) -> Unit = {},
    travelMode: String? = null,
    onTravelMode: (String) -> Unit = {},
    onTrack: (String) -> Unit = {},
    flightStatus: String? = null,
    trains: TrainChoices? = null,
    onPickTrain: (TrainRow) -> Unit = {},
    onSwitchOrigin: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Suggestion", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Show a short message until the suggestion has loaded, or if it is gone
            if (suggestion == null) {
                Muted("This suggestion is no longer available.")
                return@Column
            }
            DatebookCard(accent = true) {
                Text(promptTitle(suggestion), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Muted(whenAndWhere(suggestion))
                Muted("From ${fromLine(suggestion)}")
                changedNote(suggestion)?.let { Muted(it) }  // only when it changed in the chat
                ClashLine(suggestion.clash)

                // Show the flight's live status when it's known
                if (flightStatus != null) {
                    Text("✈ $flightStatus", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }

                Muted(statusText(suggestion))
            }

            // Show the plan's NJ Transit trains when it goes by transit
            if (trains != null) {
                TrainList(trains, onPickTrain, onSwitchOrigin)
            }

            // Offer the travel modes for new plans with a place (the leave-by alert uses the one picked)
            if (suggestion.location.isNotBlank() && travelMode != null && !isChange(suggestion)) {
                SectionLabel("Getting there")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // One chip per mode
                    for (option in TRAVEL_MODES) {
                        FilterChip(selected = option.id == travelMode, onClick = { onTravelMode(option.id) },
                            label = { Text(option.label) })
                    }
                }
            }

            val track = trackLinkFor(suggestion.flight, suggestion.packageCarrier, suggestion.trackingNumber)

            // Offer the package's tracking page or the flight's status
            if (track != null) {
                OutlinedButton(onClick = { onTrack(track.url) }) {
                    Text(track.label)
                }
            }

            // Offer directions when the plan has a place
            if (suggestion.location.isNotBlank()) {
                OutlinedButton(onClick = { onOpenMaps(suggestion.location) }) {
                    Text("OPEN IN MAPS")
                }
            }

            // Only waiting suggestions can still be added or ignored
            if (suggestion.status == Suggestion.STATUS_WAITING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAdd) {
                        Text(if (isChange(suggestion)) "OPEN EVENT" else "ADD")  // changes open the event instead
                    }
                    OutlinedButton(onClick = onIgnore) {
                        Text("IGNORE")
                    }
                }
                val help = if (isChange(suggestion)) "Open event shows it in your calendar app, where you can " +
                    "change or delete it." else "Add opens your calendar app with everything filled in, so you can " +
                    "change anything before saving."
                Muted(help)
            }
        }
    }
}

// A plan's NJ Transit trains: where they leave from (with a menu to switch to another nearby station), where they go,
// then each train with its time, state and arrival, marked ✓ yours, ★ Best or (late); tapping one makes it yours
@Composable
private fun TrainList(choices: TrainChoices, onPick: (TrainRow) -> Unit, onSwitch: (String) -> Unit) {
    var switching by remember { mutableStateOf(false) }
    SectionLabel("🚆 Trains")

    // Show the station the trains leave from, with a menu of the other nearby ones
    if (choices.from != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("From:")
            Box {
                TextButton(onClick = { switching = true }) {
                    Text("${choices.from.name} ▾")
                }
                DropdownMenu(expanded = switching, onDismissRequest = { switching = false }) {
                    // One choice per nearby station
                    for (station in choices.origins) {
                        DropdownMenuItem(text = { Text(station.name) }, onClick = {
                            switching = false
                            onSwitch(station.code)
                        })
                    }
                }
            }
        }
    }
    choices.to?.let { Muted("→ ${it.name}") }

    // Say why there are no trains, or list them
    if (choices.rows.isEmpty()) {
        Muted(choices.note)
    } else {
        DatebookCard {
            // One row per train; tapping it makes it yours
            for (row in choices.rows) {
                val marks = listOfNotNull("✓".takeIf { row.mine }, "★ Best".takeIf { row.best },
                    "(late)".takeIf { row.late }).joinToString(" ")
                val weight = if (row.mine) FontWeight.SemiBold else FontWeight.Normal  // yours stands out
                Row(Modifier.fillMaxWidth().clickable { onPick(row) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${row.leaves} · ${row.state}", fontWeight = weight, modifier = Modifier.weight(1f))
                    Text("${row.arrives} $marks".trim(), fontWeight = weight)
                }
            }
        }
        Muted("Tap a train to make it yours; the leave-by alert and delay alerts follow it.")
    }
}

// Describe a suggestion's status in words
private fun statusText(suggestion: Suggestion): String {
    // Pick the words for each status
    return when (suggestion.status) {
        Suggestion.STATUS_ADDED -> if (isChange(suggestion)) "Opened in your calendar" else "Added to your calendar"
        Suggestion.STATUS_IGNORED -> "Ignored"
        else -> "Waiting for you"
    }
}
