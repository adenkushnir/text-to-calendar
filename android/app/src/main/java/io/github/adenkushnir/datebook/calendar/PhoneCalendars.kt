// Reads the phone's calendars (Samsung, Google and phone-only ones all share Android's calendar store), only ever
// reading, and remembers which calendars you switched off
package io.github.adenkushnir.datebook.calendar
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

// One calendar on the phone, like "Personal" in your Google account
data class PhoneCalendar(val id: Long, val name: String, val account: String)

// One event on a calendar (each time of a repeating event is its own), with its start and end in milliseconds
data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String = "",
)

// Return true if Datebook may read the calendar
fun hasCalendarAccess(context: Context): Boolean {
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
    return permission == PackageManager.PERMISSION_GRANTED
}

// List the phone's calendars by name, or none without permission
fun phoneCalendars(context: Context): List<PhoneCalendar> {
    // Nothing can be read without permission
    if (!hasCalendarAccess(context)) {
        return emptyList()
    }
    val columns = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME)
    val calendars = mutableListOf<PhoneCalendar>()
    val cursor = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, null, null, null)
    cursor?.use {
        // Read each calendar's id, name and account
        while (it.moveToNext()) {
            calendars.add(PhoneCalendar(it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty()))
        }
    }
    return calendars.sortedBy { it.name.lowercase() }
}

// List the events between two times on the calendars that are switched on, soonest first (none without permission)
fun calendarEvents(context: Context, from: Long, to: Long, switchedOff: Set<Long>): List<CalendarEvent> {
    // Nothing can be read without permission
    if (!hasCalendarAccess(context)) {
        return emptyList()
    }
    val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
    val events = mutableListOf<CalendarEvent>()
    val cursor = CalendarContract.Instances.query(context.contentResolver, columns, from, to)
    cursor?.use {
        // Keep each event from a calendar that is switched on
        while (it.moveToNext()) {
            val calendarId = it.getLong(1)

            // Skip calendars you switched off
            if (calendarId in switchedOff) {
                continue
            }
            events.add(CalendarEvent(it.getLong(0), calendarId, it.getString(2).orEmpty(), it.getLong(3),
                it.getLong(4), it.getInt(5) == 1, it.getString(6).orEmpty()))
        }
    }
    return events.sortedBy { it.begin }
}

// Remembers which calendars are switched off in Settings; every calendar starts switched on
class CalendarSwitches(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-calendars", Context.MODE_PRIVATE)

    // Return the ids of the calendars that are switched off
    fun switchedOff(): Set<Long> {
        return prefs.getStringSet("off", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    // Return true if a calendar is read
    fun isOn(calendarId: Long): Boolean {
        return calendarId !in switchedOff()
    }

    // Switch a calendar on or off
    fun set(calendarId: Long, on: Boolean) {
        val off = if (on) switchedOff() - calendarId else switchedOff() + calendarId  // the new list
        prefs.edit().putStringSet("off", off.map { it.toString() }.toSet()).apply()
    }
}
