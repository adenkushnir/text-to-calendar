// Background checks of Gmail while you're signed in: every 15 minutes once there's internet, and right away when a
// Gmail notification arrives; each new email goes through the same steps as a text (only emails with a date reach
// Claude), and only the emails' ids are remembered
package io.github.adenkushnir.datebook.email
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.adenkushnir.datebook.AppGraph
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
private const val TAG = "Datebook"
private const val EVERY_15_MINUTES = "datebook-gmail"
private const val RIGHT_NOW = "datebook-gmail-now"
private const val SINCE_MARGIN_SECONDS = 60L * 60  // read a little further back; seen ids stop repeats

// Checks Gmail once and sends each new email through the pipeline
class GmailCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Fetch the new emails and handle each, logging what happened (never the email's text)
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val settings = GmailSettings(applicationContext)

        // Fetch the new emails; a failed check just waits for the next one
        val check = try {
            withContext(Dispatchers.IO) { newEmails(applicationContext) }
        } catch (error: Exception) {  // no internet, Gmail or Google sign-in trouble
            Log.i(TAG, "[gmail] check failed (${error.javaClass.simpleName})")
            return Result.success()
        }

        // Say why nothing was read, or how many new emails there are
        if (check.note.isNotBlank()) {
            Log.d(TAG, "[gmail] ${check.note}")
        } else {
            Log.d(TAG, "[gmail] checked: ${check.emails.size} new emails")
        }

        // Handle each new email, oldest first
        for (email in check.emails) {
            val outcome = graph.pipeline.receive(incomingFrom(email, ZoneId.systemDefault()))
            settings.markSeen(email.id)
            settings.setSince(maxOf(settings.since(), email.receivedMillis / 1000 - SINCE_MARGIN_SECONDS))
            val lines = outcome?.lines ?: listOf("no date")  // emails without a date leave no trace
            lines.forEach { Log.i(TAG, "[gmail] ${senderName(email.from)} -> $it") }
        }
        return Result.success()
    }
}

// The rule both checks follow: only with internet
private fun withInternet(): Constraints {
    return Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

// Check Gmail every 15 minutes (Android's shortest repeat), keeping the schedule if it's already booked
fun scheduleGmailChecks(context: Context) {
    val request = PeriodicWorkRequestBuilder<GmailCheckWorker>(15, TimeUnit.MINUTES).setConstraints(withInternet())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(EVERY_15_MINUTES, ExistingPeriodicWorkPolicy.KEEP,
        request)
}

// Check Gmail right away (when a Gmail notification arrives, or just after signing in)
fun checkGmailNow(context: Context) {
    val request = OneTimeWorkRequestBuilder<GmailCheckWorker>().setConstraints(withInternet()).build()
    WorkManager.getInstance(context).enqueueUniqueWork(RIGHT_NOW, ExistingWorkPolicy.REPLACE, request)
}

// Stop checking Gmail (after signing out)
fun stopGmailChecks(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(EVERY_15_MINUTES)
    WorkManager.getInstance(context).cancelUniqueWork(RIGHT_NOW)
}
