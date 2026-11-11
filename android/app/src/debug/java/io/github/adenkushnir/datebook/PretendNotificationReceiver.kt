// Debug builds only: lets an adb command pretend a notification arrived, so the listener's steps can be tested
// without anyone texting you; the result is logged under the tag Datebook
package io.github.adenkushnir.datebook
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.adenkushnir.datebook.capture.SAMSUNG_MESSAGES
import io.github.adenkushnir.datebook.capture.RawNotification
import io.github.adenkushnir.datebook.capture.handleNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Receives the adb broadcast and runs it through the same steps as a real notification
class PretendNotificationReceiver : BroadcastReceiver() {
    // Build a notification from the command's extras (app, title, body) and handle it in the background
    override fun onReceive(context: Context, intent: Intent) {
        val raw = RawNotification(
            app = intent.getStringExtra("app") ?: SAMSUNG_MESSAGES,
            title = intent.getStringExtra("title") ?: "Test sender",
            body = intent.getStringExtra("body") ?: "",
            postedMillis = System.currentTimeMillis(),
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            // Always tell Android the work is done, even if handling fails
            try {
                handleNotification(context.applicationContext, raw)
            } finally {
                pending.finish()
            }
        }
    }
}
