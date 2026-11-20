// The quiet notification when a package's arrival day changes ("Cleats from Amazon now arriving Sat Nov 21 (was Thu
// Nov 19)"), opening Datebook's Lists tab
package io.github.adenkushnir.datebook.lists
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.prompts.canNotify
import io.github.adenkushnir.datebook.widgets.EXTRA_OPEN_LIST
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
const val PACKAGES_CHANNEL = "packages"
private const val NOTIFICATION_BASE = 1_094_000_000  // between the fencing and Canvas ranges
private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

// A day as "Sat Nov 21" (the text as given when it isn't a date)
private fun dayText(day: String): String {
    return runCatching { LocalDate.parse(day).format(DAY) }.getOrDefault(day)
}

// The notification's words: "Cleats from Amazon now arriving Sat Nov 21 (was Thu Nov 19)"
fun packageMovedText(title: String, newDay: String, oldDay: String): String {
    return "$title now arriving ${dayText(newDay)} (was ${dayText(oldDay)})"
}

// Tell you a package's arrival day changed
fun showPackageMoved(context: Context, title: String, newDay: String, oldDay: String) {
    // Nothing can be shown without permission to notify
    if (!canNotify(context)) {
        return
    }
    val channel = NotificationChannel(PACKAGES_CHANNEL, "Package changes", NotificationManager.IMPORTANCE_LOW)
    channel.description = "When a package's arrival day changes"
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val id = NOTIFICATION_BASE + (title.hashCode() and 0xFFFFF)
    val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_OPEN_LIST, LIST_PACKAGES)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val notification = NotificationCompat.Builder(context, PACKAGES_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle("Package moved")
        .setContentText(packageMovedText(title, newDay, oldDay))
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, id, open, flags))
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}
