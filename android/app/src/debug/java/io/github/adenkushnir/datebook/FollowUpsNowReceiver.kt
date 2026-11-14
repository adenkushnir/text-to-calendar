// Debug builds only: lets an adb command send every follow-up alert now (nudges, bill reminders, "Still on?" for
// tomorrow's waiting plans, and the week ahead), whatever the time, or only the week ahead with --ez week_only true;
// what it sent is logged under the tag Datebook
package io.github.adenkushnir.datebook
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.adenkushnir.datebook.followups.runFollowUps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Receives the adb broadcast and sends the follow-up alerts in the background
class FollowUpsNowReceiver : BroadcastReceiver() {
    // Send them all and log what was sent
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            // Always tell Android the work is done, even if sending fails
            try {
                val weekOnly = intent.getBooleanExtra("week_only", false)
                val lines = runFollowUps(context.applicationContext, System.currentTimeMillis(), force = true,
                    weekOnly = weekOnly)
                lines.forEach { Log.i("Datebook", it) }  // one line each
            } finally {
                pending.finish()
            }
        }
    }
}
