// The Lists tab: a segmented glass bar switching between Packages (with Track, Order and Got it), Due soon
// (assignments due in the next 5 days, with Done), Trips (bookings grouped by destination, each with its Booking link)
// and Bills (with Pay and Paid); a link button reads "+ Link" until there's a link, and press-and-hold changes it
package io.github.adenkushnir.datebook.lists
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.ui.GlassField
import io.github.adenkushnir.datebook.ui.LocalGlass
import io.github.adenkushnir.datebook.ui.PlainButton
import io.github.adenkushnir.datebook.ui.theme.AeroBlueLight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.adenkushnir.datebook.DatebookCard
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.TopBar
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.followUpDay
import io.github.adenkushnir.datebook.followups.assignmentDue
import io.github.adenkushnir.datebook.tracking.trackLinkFor
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GlassButton
import io.github.adenkushnir.datebook.ui.Segment
import io.github.adenkushnir.datebook.ui.SegmentedBar
import io.github.adenkushnir.datebook.whenAndWhere
import java.time.LocalDate
const val LIST_PACKAGES = "packages"
const val LIST_DUE = "due"
const val LIST_TRIPS = "trips"
const val LIST_BILLS = "bills"
const val DUE_DAYS = 5L  // Due soon shows the next 5 days
private val LIST_SEGMENTS = listOf(
    Segment(LIST_PACKAGES, DatebookIcons.Box, "Packages"),
    Segment(LIST_DUE, DatebookIcons.Book, "Due soon"),
    Segment(LIST_TRIPS, DatebookIcons.Plane, "Trips"),
    Segment(LIST_BILLS, DatebookIcons.Receipt, "Bills"),
)

// What the Lists tab shows and what its buttons do
class ListsContent(
    val packages: List<Suggestion>,
    val assignments: List<FollowUp>,
    val trips: List<Trip>,
    val bills: List<FollowUp>,
    val today: LocalDate,
)

// What the Lists tab's buttons do: open a tracking page or any link, mark a package received, hand in an assignment,
// pay a bill, open a booking's card, and save (or remove, with "") the link on a plan or a follow-up
class ListsActions(
    val onTrack: (String) -> Unit = {},
    val onGotIt: (Long) -> Unit = {},
    val onDone: (Long) -> Unit = {},
    val onPaid: (Long) -> Unit = {},
    val onOpenPlan: (Long) -> Unit = {},
    val onPlanLink: (Long, String) -> Unit = { _, _ -> },
    val onItemLink: (Long, String) -> Unit = { _, _ -> },
)

// What's being given a link: its name, the link it has now, and where to save it
private class LinkTarget(val name: String, val link: String, val save: (String) -> Unit)

// Show the Lists tab: the bar, then the chosen list
@Composable
fun ListsScreen(selected: String, onSelect: (String) -> Unit, content: ListsContent, actions: ListsActions,
    onSettings: (() -> Unit)? = null) {
    var editing by remember { mutableStateOf<LinkTarget?>(null) }
    val edit: (String, String, (String) -> Unit) -> Unit = { name, link, save -> editing = LinkTarget(name, link, save) }
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
        TopBar("Lists", onSettings = onSettings)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentedBar(LIST_SEGMENTS, selected, onSelect)

            // Show the chosen list
            when (selected) {
                LIST_DUE -> DueSoon(dueWithin(content.assignments, content.today), content.today, actions)
                LIST_TRIPS -> Trips(content.trips, actions, edit)
                LIST_BILLS -> Bills(content.bills, content.today, actions, edit)
                else -> Packages(content.packages, actions, edit)
            }
        }
    }

    // Change or remove a link in a dialog
    editing?.let { target ->
        LinkEditor(target, onDismiss = { editing = null })
    }
}

// The assignments due within the next 5 days (ones without a day stay listed)
fun dueWithin(assignments: List<FollowUp>, today: LocalDate, days: Long = DUE_DAYS): List<FollowUp> {
    return assignments.filter { item ->
        val day = runCatching { LocalDate.parse(item.date) }.getOrNull()
        day == null || !day.isAfter(today.plusDays(days))
    }
}

// A link as saved: trimmed, with https:// added when it has no web address part; "" stays "" (removed)
fun cleanLink(text: String): String {
    val link = text.trim()
    return when {
        link.isBlank() -> ""
        link.startsWith("https://") || link.startsWith("http://") -> link
        else -> "https://$link"
    }
}

// A small glass pill: tapping opens the link (or adds one when there's none), pressing and holding changes it
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkChip(label: String, link: String, onOpen: (String) -> Unit, onEdit: () -> Unit) {
    val tint = LocalGlass.current.tint
    val shape = RoundedCornerShape(50)
    Text(if (link.isBlank()) "+ Link" else label, color = AeroBlueLight, fontSize = 13.sp,
        modifier = Modifier.clip(shape).background(tint.copy(alpha = 0.75f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
            .combinedClickable(onClick = { if (link.isBlank()) onEdit() else onOpen(link) }, onLongClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 7.dp))
}

// The dialog to add, change or remove a link
@Composable
private fun LinkEditor(target: LinkTarget, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(target.link) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link for ${target.name}") },
        text = {
            GlassField(value = text, onValueChange = { text = it }, label = { Text("Paste a link") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            PlainButton(enabled = text.isNotBlank(), onClick = {
                target.save(cleanLink(text))
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                // Remove only when there's a link to remove
                if (target.link.isNotBlank()) {
                    PlainButton(onClick = {
                        target.save("")
                        onDismiss()
                    }) {
                        Text("Remove")
                    }
                }
                PlainButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

// Packages on their way: what it is, when it arrives, Track (when there's a tracking link), Order and Got it
@Composable
private fun Packages(packages: List<Suggestion>, actions: ListsActions,
    edit: (String, String, (String) -> Unit) -> Unit) {
    DatebookCard {
        // Say so when nothing is on its way
        if (packages.isEmpty()) {
            Muted("No packages on the way. Delivery texts and emails show up here.")
        }

        // One row per package, a line between them
        packages.forEachIndexed { index, item ->
            // A line above every package but the first
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold)
                    Muted(arrivalText(item))
                }
                val link = trackLinkFor("", item.packageCarrier, item.trackingNumber)

                // Track only when there's somewhere to track it
                if (link != null) {
                    GlassButton(onClick = { actions.onTrack(link.url) }, small = true) {
                        Text("Track")
                    }
                }
                LinkChip("Order", item.link, actions.onTrack) {
                    edit(item.title, item.link) { actions.onPlanLink(item.id, it) }
                }
                GelButton(onClick = { actions.onGotIt(item.id) }, green = true, small = true) {
                    Text("Got it")
                }
            }
        }
    }
}

// Assignments due, soonest first, each with how far away it is and Done
@Composable
private fun DueSoon(assignments: List<FollowUp>, today: LocalDate, actions: ListsActions) {
    DatebookCard {
        // Say so when nothing is due
        if (assignments.isEmpty()) {
            Muted("Nothing due in the next 5 days. Assignments from Canvas, school emails and syllabi show up here.")
        }

        // One row per assignment, a line between them
        assignments.forEachIndexed { index, assignment ->
            // A line above every assignment but the first
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(assignment.title, fontWeight = FontWeight.SemiBold)
                    Muted(assignmentDue(assignment, today))
                }
                GelButton(onClick = { actions.onDone(assignment.id) }, green = true, small = true) {
                    Text("Done")
                }
            }
        }
    }
}

// Trips coming up: the place and dates, then each booking (tap to open its card) with its Booking link
@Composable
private fun Trips(trips: List<Trip>, actions: ListsActions, edit: (String, String, (String) -> Unit) -> Unit) {
    // Say so when no trip is coming up
    if (trips.isEmpty()) {
        DatebookCard {
            Muted("No trips coming up. Flight, hotel, rental car and train bookings are grouped here by place.")
        }
    }

    // One card per trip
    for (trip in trips) {
        SectionLabel("${trip.name} · ${tripDates(trip)}")
        DatebookCard {
            // One line per booking, a line between them
            trip.plans.forEachIndexed { index, plan ->
                // A line above every booking but the first
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).clickable { actions.onOpenPlan(plan.id) }) {
                        Text(plan.title, fontWeight = FontWeight.SemiBold)
                        Muted(whenAndWhere(plan))

                        // The booking's code, when it has one
                        if (plan.confirmation.isNotBlank()) {
                            Muted("Confirmation ${plan.confirmation}")
                        }
                    }
                    LinkChip("Booking", plan.link, actions.onTrack) {
                        edit(plan.title, plan.link) { actions.onPlanLink(plan.id, it) }
                    }
                }
            }
        }
    }
}

// Bills due, soonest first, with the amount, how far away, Pay (its link) and Paid
@Composable
private fun Bills(bills: List<FollowUp>, today: LocalDate, actions: ListsActions,
    edit: (String, String, (String) -> Unit) -> Unit) {
    DatebookCard {
        // Say so when nothing is due
        if (bills.isEmpty()) {
            Muted("No bills due. Bills and due dates from texts and emails show up here.")
        }

        // One row per bill, a line between them
        bills.forEachIndexed { index, bill ->
            // A line above every bill but the first
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(bill.title, fontWeight = FontWeight.SemiBold)
                    val amount = if (bill.amount.isNotBlank()) "${bill.amount} · " else ""  // only when known
                    val away = runCatching { daysAway(LocalDate.parse(bill.date), today) }.getOrDefault("")
                    Muted("${amount}due ${followUpDay(bill)}" + if (away.isBlank()) "" else " · $away")
                }
                LinkChip("Pay", bill.link, actions.onTrack) {
                    edit(bill.title, bill.link) { actions.onItemLink(bill.id, it) }
                }
                GelButton(onClick = { actions.onPaid(bill.id) }, small = true) {
                    Text("Paid")
                }
            }
        }
    }
}
