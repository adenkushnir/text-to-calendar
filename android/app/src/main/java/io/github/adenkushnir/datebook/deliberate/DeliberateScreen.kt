// The Deliberate tab: a bar switching between the chat (your messages, Claude's replies, questions with choices to
// tap, previews to Confirm or Cancel, and changes with Undo), every change it made, and every rule it set; past chats
// are behind the clock button and a new chat behind the plus
package io.github.adenkushnir.datebook.deliberate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.DatebookCard
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GelSwitch
import io.github.adenkushnir.datebook.ui.GlassButton
import io.github.adenkushnir.datebook.ui.GlassField
import io.github.adenkushnir.datebook.ui.GlassIconButton
import io.github.adenkushnir.datebook.ui.Segment
import io.github.adenkushnir.datebook.ui.SegmentedBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
const val VIEW_CHAT = "chat"
const val VIEW_CHANGES = "changes"
const val VIEW_RULES = "rules"
private val ME_SHAPE = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
private val ME_GEL = Brush.verticalGradient(listOf(Color(0xFF52C0F6), Color(0xFF178DE0)))
private val WARN = Color(0xFFFFBE6A)
private val CHIP_FILL = Color(0xFF5ADBEA).copy(alpha = 0.2f)  // a flight preview's travel chips, in Aero aqua
private val CHIP_EDGE = Color(0xFF5ADBEA)
private val WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
private val WEB_LINK = Regex("https?://[^\\s<>]+")
private val LINK_BLUE = Color(0xFF9FD8FF)

// Text with its web links underlined in link blue, opening in the browser when tapped
fun linked(text: String): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = LINK_BLUE, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0

        // Copy the text, making each link tappable
        for (match in WEB_LINK.findAll(text)) {
            val url = match.value.trimEnd('.', ',', ')', ';', ':', '!', '?', '"', '\'')
            append(text.substring(at, match.range.first))
            withLink(LinkAnnotation.Url(url, style)) {
                append(url)
            }
            at = match.range.first + url.length
        }
        append(text.substring(at))
    }
}

// What the tab's buttons do; the ones that ask Claude are suspend, so the tab shows it's thinking meanwhile
class DeliberateActions(
    val onSend: suspend (String) -> Unit = {},
    val onPick: suspend (Long, String) -> Unit = { _, _ -> },
    val onConfirm: suspend (Long) -> Unit = {},
    val onCancel: suspend (Long) -> Unit = {},
    val onUndo: suspend (Long) -> Unit = {},
    val onRetry: suspend (Long) -> Unit = {},
    val onRuleOn: suspend (Rule, Boolean) -> Unit = { _, _ -> },
    val onDeleteRule: suspend (Long) -> Unit = {},
    val onOpenChat: (Long) -> Unit = {},
    val onNewChat: () -> Unit = {},
)

// A time as "Tue Nov 17, 3:04 PM"
fun whenText(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    return Instant.ofEpochMilli(millis).atZone(zone).format(WHEN)
}

// The Changes part of the bar, with how many changes there are
fun changesLabel(count: Int): String {
    return if (count == 0) "Changes" else "Changes ($count)"
}

// Show the Deliberate tab
@Composable
fun DeliberateScreen(view: String, onView: (String) -> Unit, lines: List<DeliberateLine>, chats: List<DeliberateChat>,
    changes: List<DeliberateChange>, rules: List<Rule>, actions: DeliberateActions,
    onSettings: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }

    // Run a button's action, showing that Deliberate is busy until it's done
    val work: (suspend () -> Unit) -> Unit = { action ->
        // Ignore taps while something is running
        if (!busy) {
            busy = true
            scope.launch {
                // Always end the busy state, even if the action fails
                try {
                    action()
                } finally {
                    busy = false
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (history) "Past chats" else "Deliberate", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 21.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            GlassIconButton(DatebookIcons.Plus, "New chat", onClick = {
                history = false
                onView(VIEW_CHAT)
                actions.onNewChat()
            })
            GlassIconButton(if (history) DatebookIcons.Close else DatebookIcons.Clock,
                if (history) "Close past chats" else "Past chats", onClick = { history = !history })

            // The Settings button, as on every tab
            if (onSettings != null) {
                GlassIconButton(DatebookIcons.Gear, "Settings", onClick = onSettings)
            }
        }

        // Past chats cover the tab until one is picked
        if (history) {
            PastChats(chats, Modifier.weight(1f)) { id ->
                history = false
                onView(VIEW_CHAT)
                actions.onOpenChat(id)
            }
            return@Column
        }
        val segments = listOf(Segment(VIEW_CHAT, DatebookIcons.Sparkle, "Chat"),
            Segment(VIEW_CHANGES, DatebookIcons.Check, changesLabel(changes.size)),
            Segment(VIEW_RULES, DatebookIcons.Bolt, "Rules"))
        SegmentedBar(segments, view, onView, Modifier.padding(horizontal = 16.dp))

        // The picked part of the bar
        when (view) {
            VIEW_CHANGES -> ChangesList(changes, busy, Modifier.weight(1f)) { id -> work { actions.onUndo(id) } }
            VIEW_RULES -> RulesList(rules, Modifier.weight(1f),
                onOn = { rule, on -> work { actions.onRuleOn(rule, on) } },
                onDelete = { id -> work { actions.onDeleteRule(id) } })
            else -> {
                ChatLines(lines, changes, busy, Modifier.weight(1f), actions, work)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassField(value = typed, onValueChange = { typed = it },
                        label = { Text("Deliberate with Datebook…") }, modifier = Modifier.weight(1f))
                    GelButton(enabled = typed.isNotBlank() && !busy, onClick = {
                        val text = typed.trim()
                        typed = ""
                        work { actions.onSend(text) }
                    }) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

// The chat: each line in order, the newest at the bottom, with "Thinking…" while Claude works
@Composable
private fun ChatLines(lines: List<DeliberateLine>, changes: List<DeliberateChange>, busy: Boolean, modifier: Modifier,
    actions: DeliberateActions, work: (suspend () -> Unit) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, busy) {
        // Keep the newest line in view
        if (lines.isNotEmpty()) {
            list.animateScrollToItem(lines.size)
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        // A short welcome in an empty chat
        if (lines.isEmpty()) {
            item {
                DatebookCard {
                    Text("Deliberate with Datebook", fontWeight = FontWeight.SemiBold)
                    Muted("Ask about your plans, or tell Datebook how to plan them: \"every practice takes 50 minutes " +
                        "to get to\". It always shows you what will change first, and every change can be undone.")
                }
            }
        }
        items(lines, key = { it.id }) { line ->
            LineView(line, changes, busy, actions, work)
        }
        item {
            // Say Claude is working
            if (busy) {
                Muted("Thinking…")
            }
        }
    }
}

// One line of the chat, drawn by its kind
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineView(line: DeliberateLine, changes: List<DeliberateChange>, busy: Boolean, actions: DeliberateActions,
    work: (suspend () -> Unit) -> Unit) {
    val data = remember(line.data) { mapFromJson(line.data) }

    // Each kind of line has its own look
    when (line.kind) {
        LINE_ME -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(line.text, color = Color.White, modifier = Modifier.widthIn(max = 300.dp).clip(ME_SHAPE)
                .background(ME_GEL).padding(horizontal = 12.dp, vertical = 9.dp))
        }
        LINE_CHOICES -> DatebookCard {
            Text(linked(line.text))
            val picked = line.state.removePrefix("picked:")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // One button per choice; the picked one glows
                for (choice in (data["choices"] as? List<*>).orEmpty().map { it.toString() }) {
                    // The picked choice is gel, the others glass
                    if (line.state.startsWith("picked:") && picked == choice) {
                        GelButton(onClick = {}, small = true) {
                            Text(choice)
                        }
                    } else {
                        GlassButton(onClick = { work { actions.onPick(line.id, choice) } }, small = true,
                            enabled = line.state.isBlank() && !busy) {
                            Text(choice)
                        }
                    }
                }
            }
        }
        LINE_PREVIEW -> PreviewCard(line, data, busy, actions, work)
        LINE_DONE -> DatebookCard {
            Text(line.text)
            val changeId = (data["changeId"] as? Number)?.toLong() ?: 0
            val change = changes.firstOrNull { it.id == changeId }

            // Undo while the change stands
            if (change != null && !change.reverted) {
                GlassButton(onClick = { work { actions.onUndo(change.id) } }, small = true, enabled = !busy) {
                    Text("Undo")
                }
            } else if (change != null) {
                Muted("Undone")
            }
        }
        LINE_ERROR -> DatebookCard {
            Text(line.text, color = WARN)

            // Try again until it's been tried
            if (line.state.isBlank()) {
                GlassButton(onClick = { work { actions.onRetry(line.id) } }, small = true, enabled = !busy) {
                    Text("Try again")
                }
            }
        }
        LINE_NOTE -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Muted(line.text)
        }
        else -> DatebookCard {
            Text(linked(line.text))
        }
    }
}

// A new flight in a preview: "New flight plan", the route big with the number, departure and landing, the airport,
// and this plan's travel as aqua chips
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlightPreview(card: Map<*, *>) {
    val chip = RoundedCornerShape(50)
    Muted(card["heading"]?.toString() ?: "New flight plan")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(card["route"].toString(), fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Muted(card["number"].toString())
    }
    Row(Modifier.fillMaxWidth()) {
        Text(card["departs"].toString(), modifier = Modifier.weight(1f))
        Muted(card["lands"].toString())
    }

    // The airport and terminal, when known
    if (card["airport"].toString().isNotBlank()) {
        Muted(card["airport"].toString())
    }

    // Say so when there's no live data yet
    if (card["found"] != true) {
        Muted("No live data yet: Datebook fills in the times when the flight shows up.")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // One chip per travel setting
        for (text in (card["chips"] as? List<*>).orEmpty()) {
            Text(text.toString(), fontSize = 14.sp, modifier = Modifier.clip(chip).background(CHIP_FILL)
                .border(1.dp, CHIP_EDGE, chip).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

// A preview: a new flight's card (then the other changes under "Also"), a line per change, what the new rules cover,
// and Confirm / Cancel (or what was decided)
@Composable
private fun PreviewCard(line: DeliberateLine, data: Map<String, Any?>, busy: Boolean, actions: DeliberateActions,
    work: (suspend () -> Unit) -> Unit) {
    DatebookCard {
        SectionLabel("Preview · ${line.text}")
        val card = data["card"] as? Map<*, *>
        val lines = (data["lines"] as? List<*>).orEmpty()

        // A new flight gets its card, with the other changes listed under it
        if (card != null) {
            FlightPreview(card)
        }

        // The other changes go under "Also"
        if (card != null && lines.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Muted("Also")
        }

        // One line per change
        for (text in lines) {
            Text(linked(text.toString()))
        }
        val matches = (data["matches"] as? List<*>).orEmpty()

        // The events or plans the new rules cover
        if (matches.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // One line per match
            for (match in matches) {
                Muted(match.toString())
            }
        }

        // The buttons until it's decided, then what was decided
        when (line.state) {
            STATE_CONFIRMED -> Muted("✓ Confirmed")
            STATE_CANCELLED -> Muted("Cancelled")
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GelButton(onClick = { work { actions.onConfirm(line.id) } }, green = true, enabled = !busy) {
                    Text("Confirm")
                }
                GlassButton(onClick = { work { actions.onCancel(line.id) } }, enabled = !busy) {
                    Text("Cancel")
                }
            }
        }
    }
}

// Every change Deliberate made in the last 30 days, the latest first, each with Undo
@Composable
private fun ChangesList(changes: List<DeliberateChange>, busy: Boolean, modifier: Modifier, onUndo: (Long) -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            DatebookCard {
                // Say so when there are none
                if (changes.isEmpty()) {
                    Muted("No changes yet. Everything Deliberate changes shows here for 30 days, with Undo.")
                }

                // One row per change, a line between them
                changes.forEachIndexed { index, change ->
                    // A line above every change but the first
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(change.summary, fontWeight = FontWeight.SemiBold)
                            Muted(whenText(change.createdAt))
                        }

                        // Undo while it stands
                        if (change.reverted) {
                            Muted("Undone")
                        } else {
                            GlassButton(onClick = { onUndo(change.id) }, small = true, enabled = !busy) {
                                Text("Undo")
                            }
                        }
                    }
                }
            }
        }
    }
}

// Every rule, newest first, each with an on/off switch and Delete
@Composable
private fun RulesList(rules: List<Rule>, modifier: Modifier, onOn: (Rule, Boolean) -> Unit, onDelete: (Long) -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            DatebookCard {
                // Say so when there are none
                if (rules.isEmpty()) {
                    Muted("No rules yet. Try \"every practice takes 50 minutes to get to\" in the chat.")
                }

                // One row per rule, a line between them
                rules.forEachIndexed { index, rule ->
                    // A line above every rule but the first
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(ruleText(rule), fontWeight = FontWeight.SemiBold)
                            Muted(if (rule.isOn) "On" else "Off")
                        }
                        GelSwitch(rule.isOn, onCheckedChange = { onOn(rule, it) })
                        GlassIconButton(DatebookIcons.Close, "Delete rule", onClick = { onDelete(rule.id) })
                    }
                }
            }
        }
    }
}

// The past chats, the latest first; tapping one opens it
@Composable
private fun PastChats(chats: List<DeliberateChat>, modifier: Modifier, onOpen: (Long) -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Muted("Kept for 30 days. Rules and changes stay after a chat is gone.")
        }
        item {
            DatebookCard {
                // Say so when there are none
                if (chats.isEmpty()) {
                    Muted("No chats yet.")
                }

                // One row per chat, a line between them
                chats.forEachIndexed { index, chat ->
                    // A line above every chat but the first
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    Column(Modifier.fillMaxWidth().clickable { onOpen(chat.id) }.padding(vertical = 4.dp)) {
                        Text(chat.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Muted(whenText(chat.updatedAt))
                    }
                }
            }
        }
    }
}
