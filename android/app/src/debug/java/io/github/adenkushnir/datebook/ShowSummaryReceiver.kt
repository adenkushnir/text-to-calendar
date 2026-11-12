// Debug builds only: lets an adb command show the morning summary now instead of waiting for its time; how many
// events it listed is logged under the tag Datebook
package io.github.adenkushnir.datebook
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.adenkushnir.datebook.summary.showSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Receives the adb broadcast and posts today's summary in the background
class ShowSummaryReceiver : BroadcastReceiver() {
    // Show the summary and log how many events it listed
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            // Always tell Android the work is done, even if showing it fails
            try {
                val count = showSummary(context.applicationContext)
                Log.i("Datebook", "summary shown with $count event(s)")
            } finally {
                pending.finish()
            }
        }
    }
}
