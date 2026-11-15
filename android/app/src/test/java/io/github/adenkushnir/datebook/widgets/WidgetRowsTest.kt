// Tests for the Lists at a glance widget's rows: packages, the next bill and the next assignment, and empty lists
package io.github.adenkushnir.datebook.widgets
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.lists.LIST_BILLS
import io.github.adenkushnir.datebook.lists.LIST_DUE
import io.github.adenkushnir.datebook.lists.LIST_PACKAGES
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the widget's rows on Tue Nov 10 2037
class WidgetRowsTest {
    private val today = LocalDate.of(2037, 11, 10)
    private val bill = FollowUp(id = 1, kind = KIND_DUE, title = "Phone bill", date = "2037-11-20", amount = "$45",
        sender = "Verizon", app = "sms", conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = 0,
        nudges = 0)

    // A package plan arriving on a day (or with no day)
    private fun parcel(id: Long, title: String, start: String): Suggestion {
        return Suggestion(id = id, sender = "UPS", app = "gmail", title = title, start = start, end = "", allDay = true,
            location = "", repeat = "", timezone = "", phoneTimezone = "America/New_York", createdAt = 0,
            packageCarrier = "ups")
    }

    // Packages count up with the soonest one's day; the soonest bill and assignment come next, each opening its list
    @Test
    fun rows() {
        val packages = listOf(parcel(2, "Amazon delivery", ""), parcel(3, "UPS headphones", "2037-11-16"))
        val rent = bill.copy(id = 4, title = "Rent", date = "2037-12-01", amount = "")
        val essay = bill.copy(id = 5, kind = KIND_ASSIGNMENT, title = "History essay", date = "2037-11-11",
            time = "23:59", amount = "")
        val lab = essay.copy(id = 6, title = "Lab report", date = "2037-11-13")
        val rows = glanceRows(packages, listOf(rent, bill), listOf(lab, essay), today)
        assertEquals(listOf(LIST_PACKAGES, LIST_BILLS, LIST_DUE), rows.map { it.list })
        assertEquals(GlanceRow(LIST_PACKAGES, "2 packages on the way", "UPS headphones · arrives Mon Nov 16",
            "in 6 days"), rows[0])
        assertEquals(GlanceRow(LIST_BILLS, "Phone bill · $45", "Due Fri Nov 20", "in 10 days"), rows[1])
        assertEquals(GlanceRow(LIST_DUE, "History essay", "Due tomorrow 11:59 PM", "Tomorrow"), rows[2])
    }

    // The text stays at 14sp on a short widget, grows to fill a taller one, and stops at its cap
    @Test
    fun textGrows() {
        assertEquals(14f, trainsText(110f), 0.01f)
        assertEquals((195f - 60f) / 4 * 0.62f, trainsText(195f), 0.01f)
        assertEquals(22f, trainsText(400f), 0.01f)
        assertEquals(20f, listsText(400f), 0.01f)
        assertEquals(16f, headerText(22f), 0.01f)
    }

    // Half an hour before the next train, then every 5 minutes until just after it leaves; nothing without a train
    @Test
    fun trainTicks() {
        val minute = 60_000L
        val train = 1_000_000_000L
        assertEquals(train - 30 * minute, nextTickAt(train, train - 90 * minute))
        assertEquals(train - 15 * minute, nextTickAt(train, train - 20 * minute))
        assertEquals(train + 30_000, nextTickAt(train, train - 2 * minute))
        assertEquals(0L, nextTickAt(0, train))
    }

    // Empty lists leave their rows out; a package with no day says it's coming soon
    @Test
    fun emptyLists() {
        assertTrue(glanceRows(emptyList(), emptyList(), emptyList(), today).isEmpty())
        val only = glanceRows(listOf(parcel(2, "Amazon delivery", "")), emptyList(), emptyList(), today).single()
        assertEquals(GlanceRow(LIST_PACKAGES, "1 package on the way", "Amazon delivery · arriving soon", "Soon"), only)
    }
}
