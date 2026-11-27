// The plan card's trip dialogs: a train picker (other trains for the same trip, the one you have marked, ones that
// get you there too late in red; picking one moves every step) and a flight's details (status, terminal and gate,
// boarding, departure and landing, with Check in and Track)
package io.github.adenkushnir.datebook.journeys
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GlassButton
import io.github.adenkushnir.datebook.ui.PlainButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val TOO_LATE = Color(0xFFFF8A8A)
private val YOURS = Color(0xFF9FD8FF)

// A time as the dialogs say it, "2:26 PM"
private fun clock(millis: Long): String {
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(CLOCK)
}

// The first ride of a journey (the train or bus a picker is about), or null for walks and drives
fun firstRide(journey: Journey): JourneyStep? {
    return journey.steps.firstOrNull { it.kind == STEP_TRAIN || it.kind == STEP_BUS || it.kind == STEP_SUBWAY }
}

// The train picker (or, with buses, the campus bus picker): each way of making the trip by its ride and when you'd
// arrive; the current one says Yours, ones arriving after you mean to be there say too late; null choices show a
// loading line
@Composable
fun TrainPickerDialog(choices: List<Journey>?, current: Journey, beThere: Long, onPick: (Journey) -> Unit,
    onDismiss: () -> Unit, buses: Boolean = false) {
    val word = if (buses) "buses" else "trains"
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PlainButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Other $word") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted("Pick one; the leave time and every step after it move with it.")

                // Still asking Google, or nothing else found
                if (choices == null) {
                    Muted("Looking for other $word…")
                } else if (choices.size <= 1) {
                    Muted("No other $word found for this trip.")
                }

                // One row per way of making the trip
                for (choice in choices.orEmpty()) {
                    val ride = if (buses) choice.steps.firstOrNull { it.busTrip.isNotBlank() } else firstRide(choice)
                    val late = choice.arrive > beThere
                    val mine = choice.leave == current.leave && choice.arrive == current.arrive
                    val name = ride?.let { "${clock(it.starts)} · ${it.title.substringBefore(" toward")}" }
                        ?: "Leave ${clock(choice.leave)}"
                    PlainButton(onClick = { onPick(choice) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(name + if (mine) " · Yours" else "", fontWeight = FontWeight.SemiBold,
                                color = if (mine) YOURS else Color.Unspecified)
                            Text("Leave ${clock(choice.leave)} · there ${clock(choice.arrive)}" +
                                if (late) " · too late" else "", color = if (late) TOO_LATE else Color.Unspecified)
                        }
                    }
                }
            }
        },
    )
}

// A flight's details: the status line, terminal and gate, boarding, departure and landing, then Check in (the
// airline's page) and Track (the flight's status page)
@Composable
fun FlightDialog(title: String, status: String, lines: List<Pair<String, String>>, onCheckIn: () -> Unit,
    onTrack: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PlainButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status, color = YOURS, fontWeight = FontWeight.SemiBold)

                // One line per detail
                for ((label, value) in lines) {
                    Text("$label: $value")
                }
                GelButton(onClick = onCheckIn) {
                    Text("Check in")
                }

                // The status page, when there's one
                if (onTrack != null) {
                    GlassButton(onClick = onTrack) {
                        Text("Track flight")
                    }
                }
            }
        },
    )
}
