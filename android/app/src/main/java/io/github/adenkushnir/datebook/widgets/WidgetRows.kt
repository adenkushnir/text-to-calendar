// What the Lists at a glance widget says: one row for packages on the way, one for the next bill and one for the next
// assignment, each left out when its list is empty; and how big the widgets' text is at their size
package io.github.adenkushnir.datebook.widgets
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.followUpDay
import io.github.adenkushnir.datebook.followups.assignmentDue
import io.github.adenkushnir.datebook.lists.LIST_BILLS
import io.github.adenkushnir.datebook.lists.LIST_DUE
import io.github.adenkushnir.datebook.lists.LIST_PACKAGES
import io.github.adenkushnir.datebook.lists.arrivalText
import io.github.adenkushnir.datebook.lists.daysAway
import java.time.LocalDate
private const val HEADER_DP = 60f  // the widget's padding plus its header row

// One row of the widget: the list it opens, the main line, the line under it and the chip on the right (like
// "in 5 days")
data class GlanceRow(val list: String, val main: String, val sub: String, val chip: String)

// The row text size (sp) that fills a widget's height (dp): the rows share the room under the header, from 14 up to
// a cap
fun fillText(heightDp: Float, rows: Int, share: Float, cap: Float): Float {
    return ((heightDp - HEADER_DP) / rows * share).coerceIn(14f, cap)
}

// The trains widget's row text: 4 rows (the train that left and the next 3), up to 22sp
fun trainsText(heightDp: Float): Float {
    return fillText(heightDp, 4, 0.62f, 22f)
}

// The Lists widget's main line text: 3 two-line rows, up to 20sp
fun listsText(heightDp: Float): Float {
    return fillText(heightDp, 3, 0.42f, 20f)
}

// A widget's header text, growing a little with its rows
fun headerText(rowText: Float): Float {
    return (rowText * 0.78f).coerceIn(13f, 16f)
}

// How far away a "yyyy-MM-dd" day is, or a fallback when there's no day
private fun chipFor(date: String, today: LocalDate, fallback: String): String {
    val day = runCatching { LocalDate.parse(date.take(10)) }.getOrNull() ?: return fallback
    return daysAway(day, today)
}

// The widget's rows from the packages on the way (not yet received), the open bills and the open assignments
fun glanceRows(packages: List<Suggestion>, bills: List<FollowUp>, assignments: List<FollowUp>,
    today: LocalDate): List<GlanceRow> {
    val rows = mutableListOf<GlanceRow>()
    val soonest = packages.sortedBy { it.start.ifBlank { "9999" } }.firstOrNull()

    // Packages: how many, and when the soonest one arrives
    if (soonest != null) {
        val count = if (packages.size == 1) "1 package on the way" else "${packages.size} packages on the way"
        val arrives = arrivalText(soonest).replaceFirstChar { it.lowercase() }  // "arrives Mon Nov 16"
        rows.add(GlanceRow(LIST_PACKAGES, count, "${soonest.title} · $arrives", chipFor(soonest.start, today, "Soon")))
    }
    val bill = bills.minByOrNull { it.date.ifBlank { "9999" } }

    // The next bill, with its amount when known
    if (bill != null) {
        val amount = if (bill.amount.isBlank()) "" else " · ${bill.amount}"  // only when known
        val due = followUpDay(bill).ifBlank { "soon" }
        rows.add(GlanceRow(LIST_BILLS, bill.title + amount, "Due $due", chipFor(bill.date, today, "Soon")))
    }
    val assignment = assignments.minByOrNull { it.date.ifBlank { "9999" } + it.time.ifBlank { "99:99" } }

    // The next assignment
    if (assignment != null) {
        rows.add(GlanceRow(LIST_DUE, assignment.title, assignmentDue(assignment, today),
            chipFor(assignment.date, today, "Soon")))
    }
    return rows
}
