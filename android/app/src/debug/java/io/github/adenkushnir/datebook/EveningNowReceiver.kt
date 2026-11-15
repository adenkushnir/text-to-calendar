// Debug builds only: lets an adb command show the evening heads-ups (weather and what to bring) for tomorrow now,
// instead of waiting for 7 and 8 PM
package io.github.adenkushnir.datebook
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.adenkushnir.datebook.extras.showBringHeadsUp
import io.github.adenkushnir.datebook.extras.showWeatherHeadsUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Receives the adb broadcast and posts both heads-ups in the background
class EveningNowReceiver : BroadcastReceiver() {
    // Show both heads-ups
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            // Always tell Android the work is done, even if showing them fails
            try {
                showWeatherHeadsUp(context.applicationContext)
                showBringHeadsUp(context.applicationContext)
                Log.i("Datebook", "evening heads-ups checked")
            } finally {
                pending.finish()
            }
        }
    }
}
