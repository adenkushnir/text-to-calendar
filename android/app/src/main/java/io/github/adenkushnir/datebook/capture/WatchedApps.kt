// The messaging apps Datebook can watch, which are on by default, and the switches that remember your choice
package io.github.adenkushnir.datebook.capture
import android.content.Context
const val SAMSUNG_MESSAGES = "com.samsung.android.messaging"
const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
const val GROUPME = "com.groupme.android"

// One app Datebook can read texts from
data class WatchedApp(val packageName: String, val label: String, val onByDefault: Boolean)
val WATCHABLE_APPS = listOf(
    WatchedApp(SAMSUNG_MESSAGES, "Messages (Samsung)", true),
    WatchedApp(GOOGLE_MESSAGES, "Google Messages", true),
    WatchedApp(GROUPME, "GroupMe", true),
    WatchedApp("com.whatsapp", "WhatsApp", false),
    WatchedApp("com.instagram.android", "Instagram", false),
    WatchedApp("com.discord", "Discord", false),
    WatchedApp("com.google.android.gm", "Gmail", false),
)

// Remembers which apps are switched on; apps not in the list are never watched
class AppSwitches(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-apps", Context.MODE_PRIVATE)

    // Return true if Datebook should read this app's notifications
    fun isOn(packageName: String): Boolean {
        val app = WATCHABLE_APPS.firstOrNull { it.packageName == packageName } ?: return false  // unknown app
        return prefs.getBoolean(packageName, app.onByDefault)
    }

    // Switch an app on or off
    fun set(packageName: String, on: Boolean) {
        prefs.edit().putBoolean(packageName, on).apply()
    }
}
