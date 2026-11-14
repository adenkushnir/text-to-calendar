// The bottom sheet shown over the app you long-pressed or shared from: the plans Claude found, each with Add and Ignore
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.ClashLine
import io.github.adenkushnir.datebook.DatebookCard
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.ShareResult
import io.github.adenkushnir.datebook.prompts.isChange
import io.github.adenkushnir.datebook.prompts.promptTitle
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GlassButton
import io.github.adenkushnir.datebook.ui.GlassIconButton
import io.github.adenkushnir.datebook.ui.TEXT_SHADOW
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
        Text("Add to calendar", fontWeight = FontWeight.Bold, fontSize = 20.sp,
            style = LocalTextStyle.current.copy(shadow = TEXT_SHADOW))

        // Show progress, why nothing was found, or the plans
        if (result == null) {
            DatebookCard {
                val gap = Arrangement.spacedBy(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Muted("Looking for plans…")
                }
            }
        } else if (result.suggestions.isEmpty()) {
            DatebookCard {
                Muted(result.note)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Offer a way forward when there is one
                if (result.canRetry) {
                    GelButton(onClick = onRetry) {
                        Text("Try again")
                    }
                } else if (result.needsKey) {
                    GelButton(onClick = onOpenDatebook) {
                        Text("Open Datebook")
                    }
                }
                GlassButton(onClick = onClose) {
                    Text("Close")
                }
            }
        } else {
            DatebookCard {
                // Show each plan with Add and Ignore, or what you already did with it, with a line between plans
                result.suggestions.forEachIndexed { index, suggestion ->
                    // A line above every plan but the first
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    PlanRow(suggestion, suggestion.id in added, suggestion.id in ignored, onAdd, onIgnore)
                }
            }
            Muted("Plans you don't add or ignore wait on Datebook's home screen.")
            GlassButton(onClick = onClose) {
                Text("Done")
            }
        }
    }
}

// One plan in the sheet: its title, when and where, then Add (Open for a change) and Ignore, or "Added ✓",
// "Opened ✓" or "Ignored" once handled
@Composable
private fun PlanRow(suggestion: Suggestion, isAdded: Boolean, isIgnored: Boolean, onAdd: (Long) -> Unit,
    onIgnore: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(promptTitle(suggestion), fontWeight = FontWeight.SemiBold)
            Muted(whenAndWhere(suggestion))
            ClashLine(suggestion.clash)
        }

        // Show what happened to it, or the buttons
        if (isAdded) {
            val done = if (isChange(suggestion)) "Opened ✓" else "Added ✓"
            Text(done, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        } else if (isIgnored) {
            Muted("Ignored")
        } else {
            val change = isChange(suggestion)  // changes open the event instead
            GelButton(onClick = { onAdd(suggestion.id) }, green = !change, small = true) {
                Text(if (change) "Open" else "Add")
            }
            GlassIconButton(DatebookIcons.Close, "Ignore", onClick = { onIgnore(suggestion.id) })
        }
    }
}
