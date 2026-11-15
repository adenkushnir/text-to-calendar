// The Lists tab: a segmented glass bar switching between Packages (with Track and Got it), Due soon (assignments with
// Done), Trips (bookings grouped by destination) and Bills (with Paid)
package io.github.adenkushnir.datebook.lists
import androidx.compose.foundation.clickable
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

// What the Lists tab's buttons do: open a tracking page, mark a package received, hand in an assignment, pay a bill,
// and open a booking's card
class ListsActions(
    val onTrack: (String) -> Unit = {},
    val onGotIt: (Long) -> Unit = {},
    val onDone: (Long) -> Unit = {},
    val onPaid: (Long) -> Unit = {},
    val onOpenPlan: (Long) -> Unit = {},
)

// Show the Lists tab: the bar, then the chosen list
@Composable
fun ListsScreen(selected: String, onSelect: (String) -> Unit, content: ListsContent, actions: ListsActions) {
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
        TopBar("Lists")
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentedBar(LIST_SEGMENTS, selected, onSelect)

            // Show the chosen list
            when (selected) {
                LIST_DUE -> DueSoon(content.assignments, content.today, actions)
                LIST_TRIPS -> Trips(content.trips, actions)
                LIST_BILLS -> Bills(content.bills, content.today, actions)
                else -> Packages(content.packages, actions)
            }
        }
    }
}

// Packages on their way: what it is, when it arrives, Track (when there's a tracking link) and Got it
@Composable
private fun Packages(packages: List<Suggestion>, actions: ListsActions) {
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
            Muted("Nothing due. Assignments from school emails and syllabi show up here.")
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

// Trips coming up: the place and dates, then each booking (tap to open its card)
@Composable
private fun Trips(trips: List<Trip>, actions: ListsActions) {
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
                Column(Modifier.fillMaxWidth().clickable { actions.onOpenPlan(plan.id) }) {
                    Text(plan.title, fontWeight = FontWeight.SemiBold)
                    Muted(whenAndWhere(plan))

                    // The booking's code, when it has one
                    if (plan.confirmation.isNotBlank()) {
                        Muted("Confirmation ${plan.confirmation}")
                    }
                }
            }
        }
    }
}

// Bills due, soonest first, with the amount, how far away and Paid
@Composable
private fun Bills(bills: List<FollowUp>, today: LocalDate, actions: ListsActions) {
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
                GelButton(onClick = { actions.onPaid(bill.id) }, small = true) {
                    Text("Paid")
                }
            }
        }
    }
}
