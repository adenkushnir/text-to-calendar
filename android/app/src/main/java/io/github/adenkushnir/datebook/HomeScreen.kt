// The home dashboard from the mockup: a teal top bar with Settings, banners for anything off, then Next up, Waiting
// for you and Coming up
package io.github.adenkushnir.datebook
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.summary
import io.github.adenkushnir.datebook.prompts.changedNote
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.trains.TrainBoard
import io.github.adenkushnir.datebook.prompts.isChange
import io.github.adenkushnir.datebook.prompts.promptTitle
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme

// Show the whole home dashboard: the next added plan, suggestions waiting for you, and what else is coming up
@Composable
fun HomeScreen(
    waiting: List<Suggestion> = emptyList(),
    ahead: List<Suggestion> = emptyList(),
    nowMillis: Long = System.currentTimeMillis(),
    onOpen: (Long) -> Unit = {},
    onAdd: (Long) -> Unit = {},
    onIgnore: (Long) -> Unit = {},
    onSettings: () -> Unit = {},
    fixes: List<Pair<String, () -> Unit>> = emptyList(),
    trains: TrainBoard? = null,
    onFlipTrains: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Datebook", onSettings = onSettings)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Show a banner for each required setting that is off, with a button to fix it
            for ((problem, fix) in fixes) {
                FixBanner(problem, fix)
            }
            NextUpCard(ahead.firstOrNull(), nowMillis, onOpen)

            // Show the Next trains card once a trip is saved
            if (trains != null) {
                NextTrainsCard(trains, onFlipTrains)
            }
            SectionLabel("Waiting for you (${waiting.size})")
            DatebookCard {
                // Show an empty message until there are suggestions
                if (waiting.isEmpty()) {
                    Muted("No suggestions yet. They appear here when a text mentions a plan.")
                }

                // Show each waiting suggestion with Add and ✕; tapping the text opens its card
                for (suggestion in waiting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onOpen(suggestion.id) }) {
                            Text(promptTitle(suggestion), fontWeight = FontWeight.SemiBold)
                            Muted(whenAndWhere(suggestion))
                            changedNote(suggestion)?.let { Muted(it) }  // only when it changed in the chat
                            ClashLine(suggestion.clash)
                        }
                        Button(onClick = { onAdd(suggestion.id) }) {
                            Text(if (isChange(suggestion)) "OPEN" else "ADD")  // changes open the event instead
                        }
                        TextButton(onClick = { onIgnore(suggestion.id) }) {
                            Text("✕")
                        }
                    }
                }
            }
            SectionLabel("Coming up")
            DatebookCard {
                val later = ahead.drop(1)  // the first one is already in Next up

                // Show an empty message until more plans are added
                if (later.isEmpty()) {
                    Muted("Other plans you add will be listed here.")
                }

                // Show each later plan; tapping one opens its card
                for (suggestion in later) {
                    Column(Modifier.fillMaxWidth().clickable { onOpen(suggestion.id) }) {
                        Text(suggestion.title, fontWeight = FontWeight.SemiBold)
                        Muted(whenAndWhere(suggestion))
                    }
                }
            }
        }
    }
}

// A teal banner saying what's off, with a button that opens the right setting
@Composable
private fun FixBanner(problem: String, fix: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Surface(color = MaterialTheme.colorScheme.primary, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(problem, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = fix) {
                Text("FIX", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// The highlighted card for the next plan you added, with a countdown, or a message when there is none
@Composable
private fun NextUpCard(next: Suggestion?, nowMillis: Long, onOpen: (Long) -> Unit) {
    DatebookCard(accent = true) {
        // Show a message until a plan has been added
        if (next == null) {
            SectionLabel("Next up")
            Text("Nothing coming up yet", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Muted("When you add a plan, the next one shows here with a countdown.")
            return@DatebookCard
        }
        val spacing = Arrangement.spacedBy(6.dp)
        Column(Modifier.fillMaxWidth().clickable { onOpen(next.id) }, verticalArrangement = spacing) {
            SectionLabel("Next up · ${countdownFor(next, nowMillis)}")
            Text(next.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Muted(whenAndWhere(next))
            Muted("From ${fromLine(next)}")
        }
    }
}

// The Next trains card: the saved trip with a ⇄ button to flip it, then the next 3 trains or why there are none
@Composable
private fun NextTrainsCard(board: TrainBoard, onFlip: () -> Unit) {
    DatebookCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Next trains · ${board.fromName} → ${board.toName}")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onFlip) {
                Text("⇄")
            }
        }

        // List the trains, or say why there are none
        if (board.lines.isEmpty()) {
            Muted(board.note.ifBlank { "Loading…" })
        } else {
            board.lines.forEach { Text(it, fontWeight = FontWeight.SemiBold) }  // one line per train
        }
    }
}

// When and where a suggestion is, like "Fri Nov 13, 7:00 PM · Joe's"
fun whenAndWhere(suggestion: Suggestion): String {
    return summary(suggestion.toEvent()).removePrefix("${suggestion.title} · ")
}

// The clash warning under a suggestion, like "⚠ You already have Practice 6:00 PM–8:00 PM" (nothing when it doesn't
// clash)
@Composable
fun ClashLine(clash: String) {
    // Only show it when something clashes
    if (clash.isNotBlank()) {
        Text("⚠ $clash", color = MaterialTheme.colorScheme.tertiary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// The teal bar across the top, reaching up behind the status bar, with an optional back button and Settings button
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).statusBarsPadding().height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = MaterialTheme.colorScheme.onPrimary

        // A back arrow on screens below Home
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("←", color = color, fontSize = 20.sp)
            }
        }
        Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

        // The Settings button on Home
        if (onSettings != null) {
            TextButton(onClick = onSettings) {
                Text("⚙", color = color, fontSize = 20.sp)
            }
        }
    }
}

// A small uppercase heading above a group of cards
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    )
}

// Grey supporting text
@Composable
fun Muted(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
}

// A white card with a thin border, and a teal left edge when it is the highlighted one
@Composable
fun DatebookCard(accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Draw the teal edge on the highlighted card
            if (accent) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

// Preview of the home screen in Android Studio's design view
@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    DatebookTheme {
        HomeScreen()
    }
}
