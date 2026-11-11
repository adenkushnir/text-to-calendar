// Background job that tries saved texts again once the phone has internet, every minute for up to a day
package io.github.adenkushnir.datebook
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
private const val RETRY_WORK = "datebook-retry"
private const val TAG = "Datebook"

// Runs the due retries, then books the next run if texts are still waiting
class RetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Try the due texts and log what happened to each
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)

        // Log each retried text's result
        for (outcome in graph.pipeline.retryDue()) {
            outcome.lines.forEach { Log.i(TAG, "retried -> $it") }  // one log line per result
        }
        val next = graph.dao.nextRetryAt()

        // Book the next run while texts are still waiting
        if (next != null) {
            scheduleRetry(applicationContext, next, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }
}

// Book the retry job for the given time; WorkManager waits until the phone has internet before running it
fun scheduleRetry(context: Context, atMillis: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
    val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0)
    val request = OneTimeWorkRequestBuilder<RetryWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(RETRY_WORK, policy, request)
}
