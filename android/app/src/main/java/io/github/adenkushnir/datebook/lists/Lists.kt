// The Lists tab's data: travel bookings grouped into trips, packages you've marked as received, and how far away a
// date is ("Today", "Tomorrow", "in 5 days")
package io.github.adenkushnir.datebook.lists
import android.content.Context
import io.github.adenkushnir.datebook.data.Suggestion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
private const val TRIP_GAP_DAYS = 3L  // bookings for the same place this close together are one trip
private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val DAY_NAME = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

// One trip: where it's to, its first and last day, and its bookings in order
data class Trip(val name: String, val first: LocalDate, val last: LocalDate, val plans: List<Suggestion>)

// The last day a booking covers: its end's day when it has one, else its start's
private fun lastDayOf(plan: Suggestion): LocalDate? {
    val start = runCatching { LocalDate.parse(plan.start.take(10)) }.getOrNull() ?: return null
    val end = runCatching { LocalDate.parse(plan.end.take(10)) }.getOrNull()
    return if (end != null && end > start) end else start
}

// Group travel bookings into trips: the same place (ignoring case) and no more than 3 days after the trip's last day;
// trips that are already over are left out
fun tripsOf(plans: List<Suggestion>, today: LocalDate): List<Trip> {
    val trips = mutableListOf<Trip>()

    // Add each booking to its trip, oldest first, or start a new trip
    for (plan in plans.sortedBy { it.start }) {
        val first = runCatching { LocalDate.parse(plan.start.take(10)) }.getOrNull() ?: continue
        val last = lastDayOf(plan) ?: first
        val index = trips.indexOfFirst {
            it.name.equals(plan.trip, ignoreCase = true) && first <= it.last.plusDays(TRIP_GAP_DAYS)
        }

        // Join the trip it belongs to, or start one
        if (index >= 0) {
            val trip = trips[index]
            trips[index] = trip.copy(last = maxOf(trip.last, last), plans = trip.plans + plan)
        } else {
            trips.add(Trip(plan.trip, first, last, listOf(plan)))
        }
    }
    return trips.filter { it.last >= today }
}

// A trip's dates, like "Dec 18–21", "Dec 30 – Jan 2", or "Dec 18" for a one-day trip
fun tripDates(trip: Trip): String {
    // Pick the wording for one day, one month or two
    return when {
        trip.first == trip.last -> trip.first.format(MONTH_DAY)
        trip.first.month == trip.last.month -> "${trip.first.format(MONTH_DAY)}–${trip.last.dayOfMonth}"
        else -> "${trip.first.format(MONTH_DAY)} – ${trip.last.format(MONTH_DAY)}"
    }
}

// How far away a day is: "Overdue", "Today", "Tomorrow" or "in 5 days"
fun daysAway(day: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, day)

    // Pick the words for the gap
    return when {
        days < 0 -> "Overdue"
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        else -> "in $days days"
    }
}

// When a package arrives, like "Arrives Mon Nov 16" ("Arriving soon" without a day)
fun arrivalText(plan: Suggestion): String {
    val day = runCatching { LocalDate.parse(plan.start.take(10)) }.getOrNull() ?: return "Arriving soon"
    return "Arrives ${day.format(DAY_NAME)}"
}

// Remembers which packages you've marked as received (by plan id); they leave the Packages list
class PackageStore(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-packages", Context.MODE_PRIVATE)

    // The ids of the packages marked as received
    fun received(): Set<Long> {
        return prefs.getStringSet("received", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    // Mark a package as received, keeping only the latest few hundred
    fun markReceived(id: Long) {
        val all = (prefs.getStringSet("received", emptySet()).orEmpty() + id.toString()).toList().takeLast(300)
        prefs.edit().putStringSet("received", all.toSet()).apply()
    }

    // Put a package back on the list (Deliberate's Undo)
    fun unmarkReceived(id: Long) {
        val all = prefs.getStringSet("received", emptySet()).orEmpty() - id.toString()
        prefs.edit().putStringSet("received", all).apply()
    }
}
