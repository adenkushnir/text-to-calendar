// The apps Datebook can watch (messaging apps, plus shopping apps read only for packages), which are on by default,
// and the switches that remember your choice
package io.github.adenkushnir.datebook.capture
import android.content.Context
const val SAMSUNG_MESSAGES = "com.samsung.android.messaging"
const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
const val GROUPME = "com.groupme.android"
const val AMAZON_APP = "com.amazon.mShop.android.shopping"
const val SHOP_APP = "com.shopify.arrive"

// One app Datebook can read: its full name for Settings, a short name for calendar notes, whether it starts on, and
// whether it's a shopping app (only its shipping notifications are read, and automatic mode never reads its screen)
data class WatchedApp(val packageName: String, val label: String, val shortName: String, val onByDefault: Boolean,
    val isShop: Boolean = false)
val WATCHABLE_APPS = listOf(
    WatchedApp(SAMSUNG_MESSAGES, "Messages (Samsung)", "Messages", true),
    WatchedApp(GOOGLE_MESSAGES, "Google Messages", "Google Messages", true),
    WatchedApp(GROUPME, "GroupMe", "GroupMe", true),
    WatchedApp("com.whatsapp", "WhatsApp", "WhatsApp", false),
    WatchedApp("com.instagram.android", "Instagram", "Instagram", false),
    WatchedApp("com.discord", "Discord", "Discord", false),
    WatchedApp("com.google.android.gm", "Gmail", "Gmail", false),
    WatchedApp(AMAZON_APP, "Amazon Shopping (packages)", "Amazon", true, isShop = true),
    WatchedApp(SHOP_APP, "Shop (packages)", "Shop", true, isShop = true),
)
val SHOP_APPS = WATCHABLE_APPS.filter { it.isShop }.map { it.packageName }.toSet()
private val SHIPPING_WORDS = Regex("(?i)\\b(shipped|shipping|on (its|the) way|arriving|arrives|out for delivery|" +
    "delivered|delivery|tracking)\\b")

// Return true if a shopping app's notification is about a package (not a sale or a deal)
fun isShippingNews(text: String): Boolean {
    return SHIPPING_WORDS.containsMatchIn(text)
}

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
