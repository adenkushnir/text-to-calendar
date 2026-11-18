// Tests for when a follow-up's first nudge or reminder comes, and how its day reads
package io.github.adenkushnir.datebook.data
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

// Checks the first nudge for loose plans and bills, found on Tue Nov 10 2037 at 2:00 PM in New York
class FollowUpsTest {
    private val newYork = ZoneId.of("America/New_York")
    private val found = millis(LocalDateTime.of(2037, 11, 10, 14, 0))

    // Milliseconds for a time in New York
    private fun millis(time: LocalDateTime): Long {
        return time.atZone(newYork).toInstant().toEpochMilli()
    }

    // A paid monthly bill comes back due a month later with its reminders booked; a one-off bill doesn't
    @Test
    fun repeatingBills() {
        val rent = FollowUp(kind = KIND_DUE, title = "Rent", date = "2037-12-01", amount = "\$1,200", sender = "you",
            app = "", conversation = "", status = FollowUp.OPEN, createdAt = found, nextNudge = 0, nudges = 1,
            repeat = "FREQ=MONTHLY")
        val next = nextBill(rent, found, newYork)!!
        assertEquals("2038-01-01", next.date)
        assertEquals(FollowUp.OPEN, next.status)
        assertEquals(millis(LocalDateTime.of(2037, 12, 30, 9, 0)), next.nextNudge)
        assertEquals(null, nextBill(rent.copy(repeat = ""), found, newYork))
    }

    // A loose plan is nudged at 9 AM on the first day of the time it names, or 3 days later without one
    @Test
    fun loosePlans() {
        assertEquals(millis(LocalDateTime.of(2037, 11, 16, 9, 0)), firstNudge(KIND_LOOSE, "2037-11-16", found, newYork))
        assertEquals(millis(LocalDateTime.of(2037, 11, 13, 9, 0)), firstNudge(KIND_LOOSE, "", found, newYork))
        assertEquals(millis(LocalDateTime.of(2037, 11, 13, 9, 0)), firstNudge(KIND_LOOSE, "2037-11-10", found, newYork))
    }

    // A bill is reminded 2 days before at 9 AM, or on the day when that's already passed; never once it's over
    @Test
    fun bills() {
        assertEquals(millis(LocalDateTime.of(2037, 11, 18, 9, 0)), firstNudge(KIND_DUE, "2037-11-20", found, newYork))
        assertEquals(found, firstNudge(KIND_DUE, "2037-11-10", found, newYork))
        assertEquals(millis(LocalDateTime.of(2037, 11, 11, 9, 0)), firstNudge(KIND_DUE, "2037-11-11", found, newYork))
        assertEquals(0L, firstNudge(KIND_DUE, "2037-11-01", found, newYork))
        assertEquals(0L, firstNudge(KIND_DUE, "", found, newYork))
    }

    // An assignment is reminded at 6 PM the evening before, or 9 AM on the day when that's passed; never once it's over
    @Test
    fun assignments() {
        assertEquals(millis(LocalDateTime.of(2037, 11, 16, 18, 0)),
            firstNudge(KIND_ASSIGNMENT, "2037-11-17", found, newYork))
        assertEquals(millis(LocalDateTime.of(2037, 11, 11, 9, 0)),
            firstNudge(KIND_ASSIGNMENT, "2037-11-11", millis(LocalDateTime.of(2037, 11, 10, 19, 0)), newYork))
        assertEquals(0L, firstNudge(KIND_ASSIGNMENT, "2037-11-10", found, newYork))
    }

    // A follow-up's day reads like "Fri Nov 20", and nothing without one
    @Test
    fun dayText() {
        val bill = FollowUp(kind = KIND_DUE, title = "Phone bill", date = "2037-11-20", amount = "$45",
            sender = "Verizon", app = "sms", conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = 0,
            nudges = 0)
        assertEquals("Fri Nov 20", followUpDay(bill))
        assertEquals("", followUpDay(bill.copy(date = "")))
    }
}
