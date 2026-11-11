// The bottom sheet shown over the app you long-pressed or shared from: the plans Claude found, each with Add and ✕
package io.github.adenkushnir.datebook.share
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.ShareResult
import io.github.adenkushnir.datebook.whenAndWhere

// Show the sheet's contents: a spinner while Claude reads it, then the plans found or why there are none; added and
// ignored hold the ids already handled here
@Composable
fun ShareSheet(
    result: ShareResult?,
    added: Set<Long>,
    ignored: Set<Long>,
    onAdd: (Long) -> Unit,
    onIgnore: (Long) -> Unit,
    onRetry: () -> Unit,
    onOpenDatebook: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("Datebook")
        Text("Add to calendar", fontWeight = FontWeight.Bold, fontSize = 20.sp)

        // Show progress, why nothing was found, or the plans
        if (result == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Muted("Looking for plans…")
            }
        } else if (result.suggestions.isEmpty()) {
            Muted(result.note)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Offer a way forward when there is one
                if (result.canRetry) {
                    Button(onClick = onRetry) {
                        Text("TRY AGAIN")
                    }
                } else if (result.needsKey) {
                    Button(onClick = onOpenDatebook) {
                        Text("OPEN DATEBOOK")
                    }
                }
                OutlinedButton(onClick = onClose) {
                    Text("CLOSE")
                }
            }
        } else {
            // Show each plan with Add and ✕, or what you already did with it
            for (suggestion in result.suggestions) {
                PlanRow(suggestion, suggestion.id in added, suggestion.id in ignored, onAdd, onIgnore)
            }
            Muted("Plans you don't add or ignore wait on Datebook's home screen.")
            OutlinedButton(onClick = onClose) {
                Text("DONE")
            }
        }
    }
}

// One plan in the sheet: its title, when and where, then Add and ✕ (or "Added ✓" / "Ignored" once handled)
@Composable
private fun PlanRow(suggestion: Suggestion, isAdded: Boolean, isIgnored: Boolean, onAdd: (Long) -> Unit,
    onIgnore: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(suggestion.title, fontWeight = FontWeight.SemiBold)
            Muted(whenAndWhere(suggestion))
        }

        // Show what happened to it, or the buttons
        if (isAdded) {
            Text("Added ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        } else if (isIgnored) {
            Muted("Ignored")
        } else {
            Button(onClick = { onAdd(suggestion.id) }) {
                Text("ADD")
            }
            TextButton(onClick = { onIgnore(suggestion.id) }) {
                Text("✕")
            }
        }
    }
}
