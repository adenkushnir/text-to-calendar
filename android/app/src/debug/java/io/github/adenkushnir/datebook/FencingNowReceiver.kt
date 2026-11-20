// Debug builds only: start the fencing search from the PC, as a background job, to test it without the Settings page:
// adb shell am broadcast -a io.github.adenkushnir.datebook.FENCING_NOW
//     -n io.github.adenkushnir.datebook/.FencingNowReceiver
package io.github.adenkushnir.datebook
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.adenkushnir.datebook.fencing.searchInBackground

// Starts the search job; its result goes to the log ("fencing now: …")
class FencingNowReceiver : BroadcastReceiver() {
    // Hand the search to WorkManager, which can run for minutes
    override fun onReceive(context: Context, intent: Intent) {
        searchInBackground(context.applicationContext)
    }
}
