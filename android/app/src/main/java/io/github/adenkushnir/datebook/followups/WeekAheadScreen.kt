// The Week ahead screen the Friday notification opens: Claude's paragraph on the glossy card, then one glass card
// per day for the next 7 days (free days say so), then the bills due and the loose plans still open
package io.github.adenkushnir.datebook.followups
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.ClashLine
import io.github.adenkushnir.datebook.DatebookCard
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.TopBar
import io.github.adenkushnir.datebook.calendar.DayGroup
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.calendar.timeText
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.followUpDay
import java.time.ZoneId
private const val OPAQUE = 0xFF shl 24  // calendar colours sometimes leave out the alpha

// Show the week ahead: the paragraph, each day, then bills and loose plans (those two only when there are any)
@Composable
fun WeekAheadScreen(
    brief: String,
    days: List<DayGroup>,
    bills: List<FollowUp>,
    loosePlans: List<FollowUp>,
    zone: ZoneId,
    onBack: () -> Unit,
    onOpenEvent: (HomeEvent) -> Unit = {},
    onOpenChat: (FollowUp) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
        TopBar("Week ahead", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DatebookCard(accent = true) {
                SectionLabel("Your week")
                Text(brief.ifBlank { "Your week ahead is written here every Friday evening." }, fontSize = 16.sp,
                    lineHeight = 23.sp)
            }

            // One card per day, a free day saying so
            for (day in days) {
                SectionLabel(day.label)
                DatebookCard {
                    // Say so when nothing is on
                    if (day.events.isEmpty()) {
                        Muted("Free")
                    }

                    // One row per event, a line between them
                    day.events.forEachIndexed { index, event ->
                        // A line above every event but the first
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                        WeekEventRow(event, day.events, zone, onOpenEvent)
                    }
                }
            }

            // The bills still to pay
            if (bills.isNotEmpty()) {
                SectionLabel("Bills due")
                DatebookCard {
                    // One line per bill
                    for (bill in bills) {
                        val amount = if (bill.amount.isNotBlank()) " · ${bill.amount}" else ""  // only when known
                        Text(bill.title + amount, fontWeight = FontWeight.SemiBold)
                        Muted("Due ${followUpDay(bill)}")
                    }
                }
            }

            // The loose plans still to pin down; tapping one opens the chat
            if (loosePlans.isNotEmpty()) {
                SectionLabel("Loose plans")
                DatebookCard {
                    // One line per loose plan
                    for (loose in loosePlans) {
                        Column(Modifier.fillMaxWidth().clickable { onOpenChat(loose) }) {
                            Text(loose.title, fontWeight = FontWeight.SemiBold)
                            Muted("From ${loose.conversation.ifBlank { loose.sender }}")
                        }
                    }
                }
            }
        }
    }
}

// One event in a day's card: its calendar's colour dot, title, time and place, and a warning when it overlaps another
@Composable
private fun WeekEventRow(event: HomeEvent, dayEvents: List<HomeEvent>, zone: ZoneId, onOpen: (HomeEvent) -> Unit) {
    val dot = if (event.color == 0) MaterialTheme.colorScheme.primary else Color(event.color or OPAQUE)
    val overlap = dayEvents.firstOrNull { other ->
        other !== event && !other.allDay && !event.allDay && other.begin < event.end && event.begin < other.end
    }
    Row(Modifier.fillMaxWidth().clickable { onOpen(event) }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(event.title, fontWeight = FontWeight.SemiBold)
            val place = if (event.location.isBlank()) "" else " · ${event.location}"  // only when there is one
            Muted(timeText(event, zone) + place)
            overlap?.let { ClashLine("Overlaps ${it.title}") }  // only when two events overlap
        }
    }
}
