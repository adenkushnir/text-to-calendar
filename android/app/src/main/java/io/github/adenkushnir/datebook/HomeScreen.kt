// The home dashboard: a clear top bar over the scene with Settings, banners for anything off, then Next up, Waiting
// for you and Coming up on dark glass; also the glass pieces every screen shares (top bar, labels, cards)
package io.github.adenkushnir.datebook
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.summary
import io.github.adenkushnir.datebook.prompts.changedNote
import io.github.adenkushnir.datebook.prompts.guessNote
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.trains.TrainBoard
import io.github.adenkushnir.datebook.prompts.isChange
import io.github.adenkushnir.datebook.prompts.promptTitle
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.GlassIconButton
import io.github.adenkushnir.datebook.ui.HERO_BRUSH
import io.github.adenkushnir.datebook.ui.TEXT_SHADOW
import io.github.adenkushnir.datebook.ui.addButtonColors
import io.github.adenkushnir.datebook.ui.glass
import io.github.adenkushnir.datebook.ui.labelPill
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
private val LABEL_INK = Color(0xFFD6E8FF)
private val HERO_EDGE = Color.White.copy(alpha = 0.5f)

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
    checking: Set<Long> = emptySet(),
) {
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
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

                // Show each waiting suggestion with Add and Ignore; tapping the text opens its card
                for (suggestion in waiting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onOpen(suggestion.id) }) {
                            Text(promptTitle(suggestion), fontWeight = FontWeight.SemiBold)
                            Muted(whenAndWhere(suggestion))
                            changedNote(suggestion)?.let { Muted(it) }  // only when it changed in the chat
                            guessNote(suggestion, suggestion.retryId in checking)?.let { Muted(it) }  // guesses only
                            ClashLine(suggestion.clash)
                        }
                        val change = isChange(suggestion)  // changes open the event instead
                        Button(onClick = { onAdd(suggestion.id) },
                            colors = if (change) ButtonDefaults.buttonColors() else addButtonColors()) {
                            Text(if (change) "OPEN" else "ADD")
                        }
                        GlassIconButton(DatebookIcons.Close, "Ignore", onClick = { onIgnore(suggestion.id) })
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

// A blue banner saying what's off, with a button that opens the right setting
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

// The Next trains card: the saved trip with a button to flip it, then the next 3 trains or why there are none
@Composable
private fun NextTrainsCard(board: TrainBoard, onFlip: () -> Unit) {
    DatebookCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Next trains · ${board.fromName} → ${board.toName}")
            Spacer(Modifier.weight(1f))
            GlassIconButton(DatebookIcons.Swap, "Flip the trip", onClick = onFlip)
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

// The clash warning under a suggestion: a warning icon and "You already have Practice 6:00 PM–8:00 PM" (nothing when
// it doesn't clash)
@Composable
fun ClashLine(clash: String) {
    // Only show it when something clashes
    if (clash.isNotBlank()) {
        val color = MaterialTheme.colorScheme.tertiary
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(DatebookIcons.Warn, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
            Text(clash, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// The clear bar across the top, below the status bar: the title floating over the scene with a soft shadow, an
// optional back button and an optional Settings button, both glass tiles
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A back button on screens below Home
        if (onBack != null) {
            GlassIconButton(DatebookIcons.Back, "Back", onClick = onBack)
        }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            style = TextStyle(shadow = TEXT_SHADOW), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

        // The Settings button on Home
        if (onSettings != null) {
            GlassIconButton(DatebookIcons.Gear, "Settings", onClick = onSettings)
        }
    }
}

// A small uppercase heading above a group of cards, on a tinted pill so it reads over the scene
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = LABEL_INK,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.labelPill().padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

// Supporting text in the muted colour, with a soft shadow so it stays readable straight on the scene
@Composable
fun Muted(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        style = TextStyle(shadow = TEXT_SHADOW))
}

// A card of dark glass tinted by the light, or the glossy blue Next up card when it's the highlighted one
@Composable
fun DatebookCard(accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val surface = if (accent) Modifier.clip(shape).background(HERO_BRUSH).border(1.dp, HERO_EDGE, shape)
        else Modifier.glass(shape)
    val ink = if (accent) Color.White else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(Modifier.fillMaxWidth().then(surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content)
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
