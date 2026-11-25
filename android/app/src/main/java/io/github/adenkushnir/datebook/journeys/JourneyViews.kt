// A journey's steps as the plan card and Deliberate show them: the time, a coloured dot for the kind of step, the
// line's short name on a chip (for trains and buses), what happens and a line under it; arriving in the warning colour.
// With onTap, steps you can act on (walks, drives, rides, boarding, the flight) are tappable, with a › at the end
package io.github.adenkushnir.datebook.journeys
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.Muted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
private val CLOCK = DateTimeFormatter.ofPattern("h:mm", Locale.US)
private val LABEL = Color(0xFFD6E8FF)
private val ARRIVE = Color(0xFFFFBE6A)
private val LINE_DEFAULT = Color(0xFF0E86C4)
private val DOTS = mapOf(STEP_WALK to Color(0xFF52C0F6), STEP_BUS to Color(0xFF9FB3C8), STEP_TRAIN to Color(0xFF5ADBEA),
    STEP_SUBWAY to Color(0xFF5ADBEA), STEP_DRIVE to Color(0xFF52C0F6), STEP_ARRIVE to ARRIVE, STEP_BOARD to ARRIVE,
    STEP_FLIGHT to Color(0xFFEEF5FF))
private val TAPPABLE = setOf(STEP_WALK, STEP_DRIVE, STEP_TRAIN, STEP_BUS, STEP_SUBWAY, STEP_BOARD, STEP_FLIGHT)
private val ARROW = Color(0xFF9FD8FF)

// A line's colour from Google ("#0039A6"), or Aero aqua when there's none or it can't be read
private fun lineColor(text: String): Color {
    val hex = text.removePrefix("#")
    return hex.toLongOrNull(16)?.takeIf { hex.length == 6 }?.let { Color(0xFF000000 or it) } ?: LINE_DEFAULT
}

// Every step of a journey, one row each (tappable ones call onTap)
@Composable
fun JourneySteps(journey: Journey, zone: ZoneId = ZoneId.systemDefault(), onTap: ((JourneyStep) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // One row per step
        for (step in journey.steps) {
            val tappable = onTap != null && step.kind in TAPPABLE
            val row = if (tappable) Modifier.clickable { onTap?.invoke(step) }.padding(vertical = 2.dp) else Modifier
            Row(row, verticalAlignment = Alignment.Top) {
                Text(Instant.ofEpochMilli(step.starts).atZone(zone).format(CLOCK), color = LABEL,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
                Box(Modifier.padding(top = 6.dp, end = 10.dp).size(10.dp).clip(CircleShape)
                    .background(DOTS[step.kind] ?: LINE_DEFAULT))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The line's chip, for trains and buses that have one
                        if (step.line.isNotBlank() && step.kind != STEP_ARRIVE) {
                            Text(step.line, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(6.dp))
                                    .background(lineColor(step.color)).padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                        Text(step.title, fontWeight = FontWeight.SemiBold,
                            color = if (step.kind == STEP_ARRIVE) ARRIVE else Color.Unspecified)
                    }

                    // The line under it, when there is one
                    if (step.under.isNotBlank()) {
                        Muted(step.under)
                    }
                }

                // A › on steps you can tap
                if (tappable) {
                    Text("›", color = ARROW, fontSize = 22.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
