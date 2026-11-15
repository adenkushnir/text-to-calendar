// Quiet problem alerts: one silent notification the first time a problem reaches its limit (a Claude key or credit
// problem, Claude failing 3 times in a row, notification access turned off), cleared by itself once things work
// again; Home shows the same problems as banners
package io.github.adenkushnir.datebook.alerts
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
import io.github.adenkushnir.datebook.ROUTE_SETTINGS_CLAUDE
import io.github.adenkushnir.datebook.ROUTE_SETTINGS_NOTIFICATIONS
import io.github.adenkushnir.datebook.capture.hasNotificationAccess
import io.github.adenkushnir.datebook.data.DatebookDao
import io.github.adenkushnir.datebook.email.EXTRA_OPEN_ROUTE
import io.github.adenkushnir.datebook.pipeline.ALERT_THRESHOLDS
import io.github.adenkushnir.datebook.pipeline.ALERT_TITLES
import io.github.adenkushnir.datebook.pipeline.Health
import io.github.adenkushnir.datebook.pipeline.PROBLEM_API
import io.github.adenkushnir.datebook.pipeline.PROBLEM_API_KEY
import io.github.adenkushnir.datebook.pipeline.PROBLEM_NOTIFICATIONS
import io.github.adenkushnir.datebook.prompts.canNotify
const val ALERT_CHANNEL = "datebook-problems"
private const val ALERT_IDS = 7000  // notification ids for problem alerts start here
private val ALERT_ORDER = listOf(PROBLEM_API_KEY, PROBLEM_API, PROBLEM_NOTIFICATIONS)

// What a problem means and what to do, for its notification
fun alertText(kind: String): String {
    // Pick the words for the problem
    return when (kind) {
        PROBLEM_API_KEY -> "Claude turned down your API key, or it's out of credit. Texts wait until it's fixed."
        PROBLEM_API -> "Claude failed 3 times in a row. Datebook keeps retrying the texts."
        else -> "Android turned off Datebook's notification access, so no texts are being read."
    }
}

// The Datebook page that fixes a problem
fun alertRoute(kind: String): String {
    return if (kind == PROBLEM_NOTIFICATIONS) ROUTE_SETTINGS_NOTIFICATIONS else ROUTE_SETTINGS_CLAUDE
}

// The notification id for a problem
private fun alertId(kind: String): Int {
    return ALERT_IDS + ALERT_ORDER.indexOf(kind)
}

// Post a problem's silent notification; tapping it opens the page that fixes it
fun showProblemAlert(context: Context, kind: String) {
    // Nothing can be shown without notification permission; Home's banner still says so
    if (!canNotify(context)) {
        return
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Problems",
        NotificationManager.IMPORTANCE_LOW).apply { description = "When Datebook can't read texts or reach Claude" })
    val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_ROUTE, alertRoute(kind))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val tap = PendingIntent.getActivity(context, alertId(kind), open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle(ALERT_TITLES.getValue(kind))
        .setContentText(alertText(kind))
        .setStyle(NotificationCompat.BigTextStyle().bigText(alertText(kind)))
        .setContentIntent(tap)
        .setAutoCancel(true)
        .setSilent(true)
        .build()
    NotificationManagerCompat.from(context).notify(alertId(kind), notification)
}

// Remove a problem's notification once it's fixed
fun clearProblemAlert(context: Context, kind: String) {
    NotificationManagerCompat.from(context).cancel(alertId(kind))
}

// The problems at their limit right now, for Home's banners
suspend fun activeProblems(dao: DatebookDao): List<String> {
    return ALERT_ORDER.filter { (dao.failures(it) ?: 0) >= ALERT_THRESHOLDS.getValue(it) }
}

// Forget the Claude problems after a new key is saved, clearing their alerts
suspend fun resetClaudeProblems(context: Context, dao: DatebookDao) {
    val health = Health(dao)

    // Both Claude problems start over
    for (kind in listOf(PROBLEM_API_KEY, PROBLEM_API)) {
        health.succeeded(kind)
        clearProblemAlert(context, kind)
    }
}

// Check notification access: alert the first time it's found off, and clear the alert once it's back on
suspend fun checkNotificationAccess(context: Context, dao: DatebookDao) {
    val health = Health(dao)

    // On means fixed; off counts toward the alert
    if (hasNotificationAccess(context)) {
        // Remove the alert if one was showing
        if (health.succeeded(PROBLEM_NOTIFICATIONS)) {
            clearProblemAlert(context, PROBLEM_NOTIFICATIONS)
        }
    } else if (health.failed(PROBLEM_NOTIFICATIONS)) {
        showProblemAlert(context, PROBLEM_NOTIFICATIONS)
    }
}
