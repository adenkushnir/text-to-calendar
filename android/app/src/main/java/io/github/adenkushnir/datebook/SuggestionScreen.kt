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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.prompts.fromLine

// Show one suggestion; null while it is loading or after it was deleted
@Composable
fun SuggestionScreen(suggestion: Suggestion?, onBack: () -> Unit, onAdd: () -> Unit, onIgnore: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Suggestion", onBack = onBack)
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Show a short message until the suggestion has loaded, or if it is gone
            if (suggestion == null) {
                Muted("This suggestion is no longer available.")
                return@Column
            }
            DatebookCard(accent = true) {
                Text(suggestion.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Muted(whenAndWhere(suggestion))
                Muted("From ${fromLine(suggestion)}")
                ClashLine(suggestion.clash)
                Muted(statusText(suggestion.status))
            }

            // Only waiting suggestions can still be added or ignored
            if (suggestion.status == Suggestion.STATUS_WAITING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAdd) {
                        Text("ADD")
                    }
                    OutlinedButton(onClick = onIgnore) {
                        Text("IGNORE")
                    }
                }
                Muted("Add opens your calendar app with everything filled in, so you can change anything before " +
                    "saving.")
            }
        }
    }
}

// Describe a suggestion's status in words
private fun statusText(status: String): String {
    // Pick the words for each status
    return when (status) {
        Suggestion.STATUS_ADDED -> "Added to your calendar"
        Suggestion.STATUS_IGNORED -> "Ignored"
        else -> "Waiting for you"
    }
}
