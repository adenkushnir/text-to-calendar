// The suggestion card from the mockup: a read-only summary of one suggestion, with Add and Ignore
package io.github.adenkushnir.datebook
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextDecoration
import io.github.adenkushnir.datebook.pipeline.CHANGE_CANCELLED
import io.github.adenkushnir.datebook.prompts.UpdateRow
import io.github.adenkushnir.datebook.prompts.actionWord
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.places.TRAVEL_MODES
import io.github.adenkushnir.datebook.prompts.changedNote
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.prompts.guessNote
import io.github.adenkushnir.datebook.prompts.isChange
import io.github.adenkushnir.datebook.prompts.promptTitle
import io.github.adenkushnir.datebook.tracking.trackLinkFor
import io.github.adenkushnir.datebook.trains.TrainChoices
import io.github.adenkushnir.datebook.trains.TrainRow
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GlassButton
import io.github.adenkushnir.datebook.ui.Segment
import io.github.adenkushnir.datebook.ui.SegmentedBar
import io.github.adenkushnir.datebook.ui.PlainButton
import io.github.adenkushnir.datebook.ui.travelIcon
import io.github.adenkushnir.datebook.deliberate.minutesText
import io.github.adenkushnir.datebook.journeys.Journey
import io.github.adenkushnir.datebook.journeys.JourneyStep
import io.github.adenkushnir.datebook.journeys.JourneySteps

private val AIRPORT_LINE = Color(0xFFFFBE6A)  // "Be at the airport by", in the warning colour
private val QUOTE = Color(0xFFD6E8FF)
private val OLD = Color(0xFF9FB3C8)
private val NEW = Color(0xFFB8F08F)
private val ADDED = Color(0xFFFFD59A)
private const val EARLY_STEP = 5  // the Be there early stepper moves 5 minutes a tap, up to 3 hours
private const val EARLY_MOST = 180

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
    beThere: String? = null,
    early: Int? = null,
    earlyHint: String? = null,
    onEarly: (Int) -> Unit = {},
    journey: Journey? = null,
    onJourneyStep: (JourneyStep) -> Unit = {},
    trains: TrainChoices? = null,
    onPickTrain: (TrainRow) -> Unit = {},
    onSwitchOrigin: (String) -> Unit = {},
    guessText: String? = null,
    onOpenCalendar: (() -> Unit)? = null,
    updateRows: List<UpdateRow> = emptyList(),
    onOpenEvent: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
        TopBar(if (suggestion?.status == Suggestion.STATUS_CALENDAR) "Event" else "Suggestion", onBack = onBack)
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

                // Say who it came from, except for calendar events that didn't come from a text
                if (suggestion.sender.isNotBlank() || suggestion.app.isNotBlank()) {
                    Muted("From ${fromLine(suggestion)}")
                }
                changedNote(suggestion)?.let { Muted(it) }  // only when it changed in the chat
                guessNote(suggestion, guessText != null)?.let { Muted(it) }  // offline guesses only

                // Show the message a guess came from while Claude is still to check it (then it's deleted)
                if (guessText != null) {
                    Text("“$guessText”", fontStyle = FontStyle.Italic)
                }
                ClashLine(suggestion.clash)

                // A text's update: the text, then each change before → after
                if (isChange(suggestion)) {
                    UpdateLines(suggestion, updateRows)
                }

                // Show the flight's live status when it's known
                if (flightStatus != null) {
                    val color = MaterialTheme.colorScheme.onPrimaryContainer
                    val gap = Arrangement.spacedBy(6.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
                        Icon(DatebookIcons.Plane, contentDescription = "Flight", modifier = Modifier.size(18.dp),
                            tint = color)
                        Text(flightStatus, color = color, fontWeight = FontWeight.SemiBold)
                    }
                }

                // When to be at the airport, for flights
                if (beThere != null) {
                    Text(beThere, color = AIRPORT_LINE, fontWeight = FontWeight.SemiBold)
                }

                Muted(statusText(suggestion))
            }

            // The whole trip there, step by step
            if (journey != null) {
                DatebookCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Your trip · tap a step")
                        Text("", modifier = Modifier.weight(1f))
                        Text(minutesText(journey.minutes), fontWeight = FontWeight.SemiBold)
                    }
                    JourneySteps(journey, onTap = onJourneyStep)
                }
            }

            // Show the plan's NJ Transit trains when it goes by transit
            if (trains != null) {
                TrainList(trains, onPickTrain, onSwitchOrigin)
            }

            // Offer the travel modes for new plans with a place, on a glass card so they read over the scene (the
            // leave-by alert uses the one picked)
            if (suggestion.location.isNotBlank() && travelMode != null && !isChange(suggestion)) {
                DatebookCard {
                    SectionLabel("Getting there")
                    SegmentedBar(TRAVEL_MODES.map { Segment(it.id, travelIcon(it.id), it.name) }, travelMode,
                        onTravelMode)

                    // How early to be there, for this plan only
                    if (early != null) {
                        EarlyStepper(early, onEarly)
                    }
                    earlyHint?.let { Muted(it) }
                }
            }

            val track = trackLinkFor(suggestion.flight, suggestion.packageCarrier, suggestion.trackingNumber)

            // Offer the package's tracking page or the flight's status
            if (track != null) {
                GlassButton(onClick = { onTrack(track.url) }) {
                    Text(track.label)
                }
            }

            // Offer directions when the plan has a place
            if (suggestion.location.isNotBlank()) {
                GlassButton(onClick = { onOpenMaps(suggestion.location) }) {
                    Text("Open in Maps")
                }
            }

            // Calendar events open in your calendar app to change them
            if (onOpenCalendar != null) {
                GlassButton(onClick = onOpenCalendar) {
                    Text("Open in calendar")
                }
            }

            // Only waiting suggestions can still be added or ignored
            if (suggestion.status == Suggestion.STATUS_WAITING) {
                val change = isChange(suggestion)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GelButton(onClick = onAdd, green = !change) {
                        Text(actionWord(suggestion))
                    }
                    GlassButton(onClick = onIgnore) {
                        Text(if (change) "Not this" else "Ignore")
                    }

                    // Changes can also open the event, to change it yourself
                    if (change && onOpenEvent != null) {
                        GlassButton(onClick = onOpenEvent) {
                            Text("Open event")
                        }
                    }
                }
                // What the main button does
                val help = when {
                    suggestion.change == CHANGE_CANCELLED -> "Remove deletes it from your calendar; you can undo it " +
                        "for an hour."
                    change -> "Update changes the event on your calendar; you can undo it for an hour."
                    else -> "Add opens your calendar app with everything filled in, so you can change anything " +
                        "before saving."
                }
                Muted(help)
            }
        }
    }
}

// The Be there early stepper: − and + move 5 minutes, from right on time up to 3 hours
@Composable
private fun EarlyStepper(minutes: Int, onEarly: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Be there early", modifier = Modifier.weight(1f))
        GlassButton(onClick = { onEarly(maxOf(0, minutes - EARLY_STEP)) }, enabled = minutes > 0, small = true) {
            Text("−", fontSize = 18.sp)
        }
        Text(if (minutes == 0) "On time" else minutesText(minutes), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp))
        GlassButton(onClick = { onEarly(minOf(EARLY_MOST, minutes + EARLY_STEP)) }, enabled = minutes < EARLY_MOST,
            small = true) {
            Text("+", fontSize = 18.sp)
        }
    }
}

// A plan's NJ Transit trains: where they leave from (with a menu to switch to another nearby station), where they go,
// then each train with its time, state and arrival, marked ✓ yours, ★ Best or (late); tapping one makes it yours
@Composable
private fun TrainList(choices: TrainChoices, onPick: (TrainRow) -> Unit, onSwitch: (String) -> Unit) {
    var switching by remember { mutableStateOf(false) }
    SectionLabel("Trains")

    // Show the station the trains leave from, with a menu of the other nearby ones
    if (choices.from != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("From:")
            Box {
                PlainButton(onClick = { switching = true }) {
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

// A text's update on its card: the text it came from, then each change, the old one struck through and the new one
// in green (added notes in amber, with a +)
@Composable
private fun UpdateLines(suggestion: Suggestion, rows: List<UpdateRow>) {
    // The text it came from, when it was kept
    if (suggestion.message.isNotBlank()) {
        Text("“${suggestion.message.take(300)}”", fontStyle = FontStyle.Italic, color = QUOTE)
    }

    // Each change, as a row
    for (row in rows) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, color = QUOTE, fontSize = 14.sp, modifier = Modifier.width(64.dp))

            // Something that changes shows what it was; something added has a +
            if (row.old.isNotBlank()) {
                Text(row.old, textDecoration = TextDecoration.LineThrough, color = OLD,
                    modifier = Modifier.padding(end = 6.dp))
                Text(row.new, color = NEW, fontWeight = FontWeight.SemiBold)
            } else {
                Text("+ ${row.new}", color = ADDED, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// Describe a suggestion's status in words
private fun statusText(suggestion: Suggestion): String {
    // Pick the words for each status
    return when (suggestion.status) {
        Suggestion.STATUS_ADDED -> when {
            suggestion.change == CHANGE_CANCELLED -> "Removed from your calendar"
            isChange(suggestion) -> "Updated on your calendar"
            else -> "Added to your calendar"
        }
        Suggestion.STATUS_IGNORED -> "Ignored"
        Suggestion.STATUS_CALENDAR -> "On your calendar"
        else -> "Waiting for you"
    }
}
