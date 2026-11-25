// Writes to the phone's calendar once you've confirmed: new events go on your Google account's main calendar (or the
// one picked in Settings → Calendars), an event's title, time, place or notes can change, and everything can be put
// back for Undo; the event then opens in your calendar app
package io.github.adenkushnir.datebook.calendar
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.pipeline.eventZone
import io.github.adenkushnir.datebook.pipeline.repeatRule
import io.github.adenkushnir.datebook.prompts.notesWithTrackLink
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
private const val GOOGLE_ACCOUNT = "com.google"
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

// An event's own fields, as read before a change (so Undo can put them back) or as written
data class EventFields(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String,
    val notes: String,
    val timezone: String,
    val repeat: String = "",
)

// One calendar Datebook may write to, and whether it's the Google account's main one
data class WritableCalendar(val calendar: PhoneCalendar, val primary: Boolean)

// Return true if Datebook may write to the calendar
fun canWriteCalendar(context: Context): Boolean {
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
    return permission == PackageManager.PERMISSION_GRANTED && hasCalendarAccess(context)
}

// The calendars Datebook may add events to (ones you can edit), Google ones first and the main one at the top
fun writableCalendars(context: Context): List<WritableCalendar> {
    // Nothing can be read without permission
    if (!canWriteCalendar(context)) {
        return emptyList()
    }
    val columns = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
        CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        CalendarContract.Calendars.IS_PRIMARY)
    val found = mutableListOf<Pair<WritableCalendar, Boolean>>()
    val cursor = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, null, null, null)
    cursor?.use {
        // Keep each calendar you can add events to
        while (it.moveToNext()) {
            val access = it.getInt(5)

            // Skip calendars that are read-only (holidays, birthdays, shared ones)
            if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                continue
            }
            val account = it.getString(2).orEmpty()
            val google = it.getString(3) == GOOGLE_ACCOUNT
            val primary = google && (it.getInt(6) == 1 || it.getString(4) == account)
            found.add(Pair(WritableCalendar(PhoneCalendar(it.getLong(0), it.getString(1).orEmpty(), account), primary),
                google))
        }
    }
    return found.sortedWith(compareBy({ !it.first.primary }, { !it.second }, { it.first.calendar.name.lowercase() }))
        .map { it.first }
}

// The calendar new events go to: the one picked in Settings when you can still write to it, else the main Google
// calendar (or the first one you can write to); null when there's none
fun writeCalendarId(context: Context, picked: Long?): Long? {
    val calendars = writableCalendars(context)
    return calendars.firstOrNull { it.calendar.id == picked }?.calendar?.id ?: calendars.firstOrNull()?.calendar?.id
}

// A plan's fields as an event: its title, times (all-day ones at midnight UTC, as calendars keep them), place, notes
// with what to bring and the Track link, zone and repeat rule
fun planFields(suggestion: Suggestion): EventFields {
    val event = suggestion.toEvent()
    val (begin, end) = eventTimes(event)

    // All-day events run from midnight to midnight UTC
    if (event.allDay) {
        val day = LocalDate.parse(event.start.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val days = maxOf(1L, (end - begin + DAY_MILLIS - 1) / DAY_MILLIS)
        return EventFields(event.title.ifBlank { "Event" }, day, day + days * DAY_MILLIS, true, event.location,
            notesWithTrackLink(suggestion), "UTC", repeatRule(event))
    }
    val zone = eventZone(event) ?: ZoneId.systemDefault()
    return EventFields(event.title.ifBlank { "Event" }, begin, end, false, event.location,
        notesWithTrackLink(suggestion), zone.id, repeatRule(event))
}

// An event's fields as the values the calendar stores (a repeating event keeps a length instead of an end)
fun eventValues(fields: EventFields): ContentValues {
    val values = ContentValues()
    values.put(CalendarContract.Events.TITLE, fields.title)
    values.put(CalendarContract.Events.DTSTART, fields.begin)
    values.put(CalendarContract.Events.ALL_DAY, if (fields.allDay) 1 else 0)
    values.put(CalendarContract.Events.EVENT_LOCATION, fields.location)
    values.put(CalendarContract.Events.DESCRIPTION, fields.notes)
    values.put(CalendarContract.Events.EVENT_TIMEZONE, fields.timezone)

    // A repeating event has a rule and a length; a one-off has its end
    if (fields.repeat.isNotBlank()) {
        values.put(CalendarContract.Events.RRULE, fields.repeat)
        values.put(CalendarContract.Events.DURATION, "P${maxOf(1, (fields.end - fields.begin) / 1000)}S")
        values.putNull(CalendarContract.Events.DTEND)
    } else {
        values.put(CalendarContract.Events.DTEND, fields.end)
    }
    return values
}

// Add a plan to a calendar; returns the new event's id, or null when it couldn't be written
fun insertEvent(context: Context, calendarId: Long, suggestion: Suggestion): Long? {
    val values = eventValues(planFields(suggestion))
    values.put(CalendarContract.Events.CALENDAR_ID, calendarId)

    // The calendar can refuse (no permission any more, a calendar that went away)
    return try {
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
    } catch (error: Exception) {  // a refused or failed write
        Log.w("Datebook", "calendar insert failed: ${error.message}")
        null
    }
}

// Read an event's own fields (null when it's gone or can't be read)
fun readEvent(context: Context, eventId: Long): EventFields? {
    // Nothing can be read without permission
    if (!hasCalendarAccess(context)) {
        return null
    }
    val columns = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_LOCATION,
        CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.RRULE)
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    return context.contentResolver.query(uri, columns, null, null, null)?.use {
        // The event, when it's there
        if (!it.moveToFirst()) {
            null
        } else {
            EventFields(it.getString(0).orEmpty(), it.getLong(1), it.getLong(2), it.getInt(3) == 1,
                it.getString(4).orEmpty(), it.getString(5).orEmpty(), it.getString(6).orEmpty(),
                it.getString(7).orEmpty())
        }
    }
}

// Change an event's title, times, place and notes (not repeating ones, whose whole series would move); returns true
// when it was written
fun updateEvent(context: Context, eventId: Long, fields: EventFields): Boolean {
    val values = ContentValues()
    values.put(CalendarContract.Events.TITLE, fields.title)
    values.put(CalendarContract.Events.DTSTART, fields.begin)
    values.put(CalendarContract.Events.DTEND, fields.end)
    values.put(CalendarContract.Events.ALL_DAY, if (fields.allDay) 1 else 0)
    values.put(CalendarContract.Events.EVENT_LOCATION, fields.location)
    values.put(CalendarContract.Events.DESCRIPTION, fields.notes)
    values.put(CalendarContract.Events.EVENT_TIMEZONE, fields.timezone)
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)

    // The calendar can refuse the change
    return try {
        context.contentResolver.update(uri, values, null, null) > 0
    } catch (error: Exception) {  // a refused or failed write
        Log.w("Datebook", "calendar update failed: ${error.message}")
        false
    }
}

// Delete an event Datebook added (for Undo); returns true when it's gone
fun deleteEvent(context: Context, eventId: Long): Boolean {
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)

    // The calendar can refuse
    return try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (error: Exception) {  // a refused or failed delete
        Log.w("Datebook", "calendar delete failed: ${error.message}")
        false
    }
}

// The intent that opens an event in your calendar app
fun openEventIntent(eventId: Long): Intent {
    return Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
