// Texts that update an event already on the calendar: what changes (a new time, things to bring, a code), shown as
// before → after rows and in the prompt ("Update Dinner with Sam?" / "Remove Pickup game?"); Update changes the event
// itself (a cancel deletes it), then "Updated Dinner with Sam" offers Undo for an hour, which puts the event back
package io.github.adenkushnir.datebook.prompts
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.calendar.EventFields
import io.github.adenkushnir.datebook.calendar.canWriteCalendar
import io.github.adenkushnir.datebook.calendar.deleteEvent
import io.github.adenkushnir.datebook.calendar.eventValues
import io.github.adenkushnir.datebook.calendar.planFields
import io.github.adenkushnir.datebook.calendar.readEvent
import io.github.adenkushnir.datebook.calendar.updateEvent
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.deliberate.mergeBring
import io.github.adenkushnir.datebook.extras.BRING_LABEL
import io.github.adenkushnir.datebook.extras.bringOf
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.pipeline.CHANGE_CANCELLED
import io.github.adenkushnir.datebook.pipeline.CHANGE_DETAILS
import io.github.adenkushnir.datebook.pipeline.CHANGE_MOVED
import io.github.adenkushnir.datebook.whenAndWhere
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
const val CODE_LABEL = "Code: "
private const val UNDO_MILLIS = 3600_000L  // Undo stays this long
private const val UNDO_IDS = 1_900_000_000  // the Updated / Removed notifications' ids start here
private const val UPDATES_CHANNEL = "calendar_updates"
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
private val DAY_ONLY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val MAPPER = jacksonObjectMapper()

// One row of what an update changes: what it is ("When", "Notes"), what it was (empty for something added) and what
// it becomes
data class UpdateRow(val label: String, val old: String, val new: String)

// An event's notes with what a text adds: things to bring join the Bring line (or start one), and a code gets its own
// "Code:" line unless the notes already have it
fun updatedNotes(notes: String, bring: String, code: String): String {
    val lines = notes.lines().toMutableList().also { if (it.size == 1 && it[0].isBlank()) it.clear() }
    val extra = bring.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val merged = mergeBring(bringOf(notes), extra)
    val at = lines.indexOfFirst { it.trim().startsWith(BRING_LABEL) }

    // Things to bring: the old line grows, or a new one is added
    if (extra.isNotEmpty() && at >= 0) {
        lines[at] = BRING_LABEL + merged
    } else if (extra.isNotEmpty()) {
        lines.add(BRING_LABEL + merged)
    }

    // A code the notes don't have yet
    if (code.isNotBlank() && !notes.contains(code.trim())) {
        lines.add(CODE_LABEL + code.trim())
    }
    return lines.joinToString("\n")
}

// When an event is, as the rows and prompt say it: "Fri 7:00 PM", or "Fri Nov 27" for an all-day one
private fun whenText(begin: Long, allDay: Boolean): String {
    // All-day events are kept at midnight UTC
    return if (allDay) Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).format(DAY_ONLY)
        else Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).format(DAY_TIME)
}

// What an update changes on the event as it is now: its time (when it moved) and what it adds to the notes; nothing
// for a cancel (it's removed)
fun updateRows(old: EventFields?, suggestion: Suggestion): List<UpdateRow> {
    val rows = mutableListOf<UpdateRow>()

    // Nothing to compare without the event, and a cancel only removes it
    if (old == null || suggestion.change == CHANGE_CANCELLED) {
        return rows
    }
    val new = planFields(suggestion)

    // A new time
    if (suggestion.change == CHANGE_MOVED && new.begin != old.begin) {
        rows.add(UpdateRow("When", whenText(old.begin, old.allDay), whenText(new.begin, new.allDay)))
    }
    val notes = updatedNotes(old.notes, suggestion.bring, suggestion.confirmation)
    val added = notes.lines().filter { it.isNotBlank() && it !in old.notes.lines() }

    // Each note line it adds (or grows)
    for (line in added) {
        rows.add(UpdateRow("Notes", "", line))
    }
    return rows
}

// The word on a prompt's main button: Remove for a cancel, Update for other changes, Add for a new plan
fun actionWord(suggestion: Suggestion): String {
    // Pick the word by the kind of suggestion
    return when {
        suggestion.change == CHANGE_CANCELLED -> "Remove"
        suggestion.change.isNotBlank() -> "Update"
        else -> "Add"
    }
}

// The prompt's line: "Fri 7:00 PM → 8:00 PM · add Bring: $20", or for a cancel when it was
fun updateLine(old: EventFields?, suggestion: Suggestion): String {
    // A cancel says when it was; an update what changes
    if (suggestion.change == CHANGE_CANCELLED || old == null) {
        return old?.let { whenText(it.begin, it.allDay) } ?: whenAndWhere(suggestion)
    }
    return updateRows(old, suggestion).joinToString(" · ") { row ->
        val sameDay = row.old.substringBefore(" ") + " "  // "Fri 7:00 PM → 8:00 PM", not "→ Fri 8:00 PM"
        if (row.old.isBlank()) "add ${row.new}" else "${row.old} → ${row.new.removePrefix(sameDay)}"
    }.ifBlank { whenText(old.begin, old.allDay) }
}

// Return true if an update only adds notes (its prompt is quiet: when you go doesn't change)
fun notesOnly(suggestion: Suggestion): Boolean {
    return suggestion.change == CHANGE_DETAILS
}

// What Undo needs to put an event back: its fields before, the calendar it was on, and whether it was removed
data class UndoRecord(val fields: EventFields, val calendarId: Long, val eventId: Long, val removed: Boolean,
    val title: String, val until: Long)

// Keeps each update's Undo for an hour, by the plan's id
class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-updates", Context.MODE_PRIVATE)

    // Keep a plan's Undo
    fun save(suggestionId: Long, record: UndoRecord) {
        prefs.edit().putString("undo:$suggestionId", MAPPER.writeValueAsString(record)).apply()
    }

    // A plan's Undo while it lasts (null when there's none or it's over an hour old)
    fun undo(suggestionId: Long): UndoRecord? {
        val saved = prefs.getString("undo:$suggestionId", null) ?: return null
        return runCatching { MAPPER.readValue<UndoRecord>(saved) }.getOrNull()
            ?.takeIf { it.until > System.currentTimeMillis() }
    }

    // Forget a plan's Undo
    fun forget(suggestionId: Long) {
        prefs.edit().remove("undo:$suggestionId").apply()
    }
}

// The calendar an event is on (null when it's gone)
private fun calendarOf(context: Context, eventId: Long): Long? {
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    return context.contentResolver.query(uri, arrayOf(CalendarContract.Events.CALENDAR_ID), null, null, null)?.use {
        if (it.moveToFirst()) it.getLong(0) else null
    }
}

// Move a plan's own travel settings (mode, early minutes, Uber reminder) to the event's new time, so they follow it
private fun moveTravel(context: Context, title: String, oldBegin: Long, newBegin: Long) {
    val store = LeaveByStore(context)
    val oldKey = eventKey(title, oldBegin)
    val newKey = eventKey(title, newBegin)
    store.mode(oldKey)?.let { store.setMode(newKey, it) }
    store.early(oldKey)?.let { store.setEarly(newKey, it) }
    store.orderMinutes(oldKey)?.let { store.setOrderMinutes(newKey, it) }
}

// Apply a text's update to its event: a move changes the time (and adds notes), notes are added, a cancel deletes it;
// the plan is marked added, its prompt goes and "Updated ___" (or "Removed ___") offers Undo for an hour. Returns false
// when it couldn't be written (no access, a repeating event, the event gone)
suspend fun applyUpdate(context: Context, suggestionId: Long): Boolean {
    val graph = AppGraph.get(context)
    val suggestion = graph.dao.suggestion(suggestionId) ?: return false
    val eventId = suggestion.calendarEventId
    val written = withContext(Dispatchers.IO) {
        val old = readEvent(context, eventId)?.takeIf { it.repeat.isBlank() }  // not a whole series
        val calendarId = calendarOf(context, eventId)

        // Only with access, and only an event that's still there
        if (!canWriteCalendar(context) || old == null || calendarId == null) {
            return@withContext null
        }
        val new = planFields(suggestion)
        val notes = updatedNotes(old.notes, suggestion.bring, suggestion.confirmation)
        // Write the change by its kind
        val done = when (suggestion.change) {
            CHANGE_CANCELLED -> deleteEvent(context, eventId)
            CHANGE_MOVED -> updateEvent(context, eventId, old.copy(begin = new.begin, end = new.end,
                allDay = new.allDay, timezone = new.timezone, notes = notes))
            else -> updateEvent(context, eventId, old.copy(notes = notes))
        }

        // Keep the travel settings with a moved event
        if (done && suggestion.change == CHANGE_MOVED) {
            moveTravel(context, old.title, old.begin, new.begin)
        }
        if (done) Pair(old, calendarId) else null
    }
    graph.notifier.cancel(suggestionId)

    // Say when it couldn't be changed; the plan keeps waiting
    if (written == null) {
        return false
    }
    val (old, calendarId) = written
    val removed = suggestion.change == CHANGE_CANCELLED
    UpdateStore(context).save(suggestionId, UndoRecord(old, calendarId, eventId, removed, old.title,
        System.currentTimeMillis() + UNDO_MILLIS))
    graph.dao.setStatus(suggestionId, Suggestion.STATUS_ADDED)
    showUpdated(context, suggestion, old, removed)
    return true
}

// The "Updated Dinner with Sam" (or "Removed Pickup game") notification with Undo, gone by itself after an hour
private fun showUpdated(context: Context, suggestion: Suggestion, old: EventFields, removed: Boolean) {
    // Without notification permission there's nothing to show
    if (!canNotify(context)) {
        return
    }
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    manager.createNotificationChannel(android.app.NotificationChannel(UPDATES_CHANNEL, "Calendar updates",
        android.app.NotificationManager.IMPORTANCE_LOW).apply {
        description = "What Datebook changed on your calendar, with Undo"
    })
    val undo = Intent(context, UndoUpdateReceiver::class.java).putExtra(EXTRA_SUGGESTION_ID, suggestion.id)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val line = if (removed) whenText(old.begin, old.allDay) else {
        val new = planFields(suggestion)
        listOfNotNull(whenText(if (suggestion.change == CHANGE_MOVED) new.begin else old.begin, old.allDay),
            bringOf(updatedNotes(old.notes, suggestion.bring, "")).ifBlank { null }?.let { BRING_LABEL + it })
            .joinToString(" · ")
    }
    val notification = NotificationCompat.Builder(context, UPDATES_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle("${if (removed) "Removed" else "Updated"} ${old.title}")
        .setContentText(line)
        .setSilent(true)
        .setAutoCancel(true)
        .setTimeoutAfter(UNDO_MILLIS)
        .addAction(0, "Undo", PendingIntent.getBroadcast(context, UNDO_IDS + (suggestion.id % 100_000_000).toInt(),
            undo, flags))
        .build()
    NotificationManagerCompat.from(context).notify(UNDO_IDS + (suggestion.id % 100_000_000).toInt(), notification)
}

// Undo an update: put the event's fields back (a removed event is added again on its calendar), and the plan waits
// again; returns false when the Undo is over or couldn't be written
suspend fun undoUpdate(context: Context, suggestionId: Long): Boolean {
    val graph = AppGraph.get(context)
    val store = UpdateStore(context)
    val record = store.undo(suggestionId) ?: return false
    val done = withContext(Dispatchers.IO) {
        // Add a removed event back, or put the old fields back
        if (record.removed) {
            val values = eventValues(record.fields)
            values.put(CalendarContract.Events.CALENDAR_ID, record.calendarId)
            val added = runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }
                .onFailure { Log.w("Datebook", "undo insert failed: ${it.message}") }.getOrNull()
            added?.let { ContentUris.parseId(it) }?.also { newId ->
                graph.dao.suggestion(suggestionId)?.let { graph.dao.updateSuggestion(it.copy(calendarEventId = newId)) }
            } != null
        } else {
            updateEvent(context, record.eventId, record.fields)
        }
    }

    // A written Undo lets the plan wait again and clears its notification
    if (done) {
        store.forget(suggestionId)
        graph.dao.setStatus(suggestionId, Suggestion.STATUS_WAITING)
        NotificationManagerCompat.from(context).cancel(UNDO_IDS + (suggestionId % 100_000_000).toInt())
    }
    return done
}

// Handles Update (or Remove) on a text update's prompt, in the background
class UpdateReceiver : BroadcastReceiver() {
    // Apply the update named in the intent
    override fun onReceive(context: Context, intent: Intent) {
        val suggestionId = intent.getLongExtra(EXTRA_SUGGESTION_ID, -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            // Always tell Android the work is done, even if it fails
            try {
                applyUpdate(context.applicationContext, suggestionId)
            } finally {
                pending.finish()
            }
        }
    }
}

// Handles Undo on the "Updated ___" notification, in the background
class UndoUpdateReceiver : BroadcastReceiver() {
    // Undo the update named in the intent
    override fun onReceive(context: Context, intent: Intent) {
        val suggestionId = intent.getLongExtra(EXTRA_SUGGESTION_ID, -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            // Always tell Android the work is done, even if it fails
            try {
                undoUpdate(context.applicationContext, suggestionId)
            } finally {
                pending.finish()
            }
        }
    }
}
