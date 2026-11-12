// The suggestion card from the mockup: a read-only summary of one suggestion, with Add and Ignore
package io.github.adenkushnir.datebook
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.places.TRAVEL_MODES
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.prompts.isChange
import io.github.adenkushnir.datebook.prompts.promptTitle
import io.github.adenkushnir.datebook.tracking.trackLinkFor

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
    trainLine: String? = null,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Suggestion", onBack = onBack)
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Show a short message until the suggestion has loaded, or if it is gone
            if (suggestion == null) {
                Muted("This suggestion is no longer available.")
                return@Column
            }
            DatebookCard(accent = true) {
                Text(promptTitle(suggestion), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Muted(whenAndWhere(suggestion))
                Muted("From ${fromLine(suggestion)}")
                ClashLine(suggestion.clash)

                // Show the flight's live status when it's known
                if (flightStatus != null) {
                    Text("✈ $flightStatus", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }

                // Show the suggested NJ Transit train for transit plans
                if (trainLine != null) {
                    Text("🚆 $trainLine", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Muted(statusText(suggestion))
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

// Describe a suggestion's status in words
private fun statusText(suggestion: Suggestion): String {
    // Pick the words for each status
    return when (suggestion.status) {
        Suggestion.STATUS_ADDED -> if (isChange(suggestion)) "Opened in your calendar" else "Added to your calendar"
        Suggestion.STATUS_IGNORED -> "Ignored"
        else -> "Waiting for you"
    }
}
