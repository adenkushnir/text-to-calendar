// Deliberate's settings: whether message texts are kept (so it can quote them), and alerts paused until a time
// ("no leave-by alerts this weekend", "skip tomorrow's summary")
package io.github.adenkushnir.datebook.deliberate
import android.content.Context
const val PAUSE_LEAVE_BY = "leave_by"
const val PAUSE_SUMMARY = "summary"
const val PAUSE_EVENING = "evening"
const val PAUSE_WEEK_AHEAD = "week_ahead"
val PAUSE_KINDS = listOf(PAUSE_LEAVE_BY, PAUSE_SUMMARY, PAUSE_EVENING, PAUSE_WEEK_AHEAD)

// What a paused kind of alert is called
fun pauseName(what: String): String {
    // Name each kind
    return when (what) {
        PAUSE_LEAVE_BY -> "Leave-by alerts"
        PAUSE_SUMMARY -> "The morning summary"
        PAUSE_EVENING -> "Evening heads-ups"
        PAUSE_WEEK_AHEAD -> "The Friday week ahead"
        else -> what
    }
}

// Return true if a pause (until a time in milliseconds, 0 for none) is still on
fun isPaused(until: Long, nowMillis: Long): Boolean {
    return until > nowMillis
}

// Remembers the message-text switch and the pauses
class DeliberateSettings(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-deliberate", Context.MODE_PRIVATE)

    // Return true if message texts are kept with their plans (on to start)
    fun keepTexts(): Boolean {
        return prefs.getBoolean("keep-texts", true)
    }

    // Turn keeping message texts on or off
    fun setKeepTexts(on: Boolean) {
        prefs.edit().putBoolean("keep-texts", on).apply()
    }

    // Return when a kind of alert is paused until, in milliseconds (0 when it isn't)
    fun pausedUntil(what: String): Long {
        return prefs.getLong("pause:$what", 0)
    }

    // Pause a kind of alert until a time (0 ends the pause)
    fun setPausedUntil(what: String, until: Long) {
        prefs.edit().putLong("pause:$what", until).apply()
    }

    // Return true if a kind of alert is paused right now
    fun paused(what: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return isPaused(pausedUntil(what), nowMillis)
    }
}
