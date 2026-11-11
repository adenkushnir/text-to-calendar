// The home dashboard from the mockup: a teal top bar, then Next up, Waiting for you and Coming up
package io.github.adenkushnir.datebook
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.summary
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme

// Show the whole home dashboard: waiting suggestions from the database, and test tools in debug builds
@Composable
fun HomeScreen(waiting: List<Suggestion> = emptyList(), testTools: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DatebookCard(accent = true) {
                SectionLabel("Next up")
                Text("Nothing coming up yet", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Muted("Your next plan from Datebook will show here, with a countdown.")
            }
            SectionLabel("Waiting for you (${waiting.size})")
            DatebookCard {
                // Show an empty message until there are suggestions
                if (waiting.isEmpty()) {
                    Muted("No suggestions yet. They appear here when a text mentions a plan.")
                }

                // Show each waiting suggestion as its title and when it is
                for (suggestion in waiting) {
                    Text(suggestion.title, fontWeight = FontWeight.SemiBold)
                    Muted(summary(suggestion.toEvent()).removePrefix("${suggestion.title} · "))
                }
            }
            SectionLabel("Coming up")
            DatebookCard {
                Muted("Plans you add will be listed here.")
            }
            testTools?.invoke()
        }
    }
}

// The teal bar across the top, reaching up behind the status bar
@Composable
private fun TopBar() {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).statusBarsPadding().padding(16.dp)) {
        val color = MaterialTheme.colorScheme.onPrimary
        Text("Datebook", color = color, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
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
