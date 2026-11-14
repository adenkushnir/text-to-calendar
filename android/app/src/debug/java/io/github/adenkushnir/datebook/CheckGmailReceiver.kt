// Debug builds only: lets an adb command check Gmail now instead of waiting for the 15-minute check; what it found
// is logged under the tag Datebook
package io.github.adenkushnir.datebook
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.adenkushnir.datebook.email.checkGmailNow

// Receives the adb broadcast and books a Gmail check right away
class CheckGmailReceiver : BroadcastReceiver() {
    // Start the check (it runs as soon as there's internet)
    override fun onReceive(context: Context, intent: Intent) {
        checkGmailNow(context.applicationContext)
    }
}
