// Counts problems in a row toward the quiet alerts, with the Python version's thresholds (the alerts arrive in step 12)
package io.github.adenkushnir.datebook.pipeline
import io.github.adenkushnir.datebook.data.DatebookDao
import io.github.adenkushnir.datebook.data.FailureCount
val ALERT_THRESHOLDS = mapOf(PROBLEM_NOTIFICATIONS to 3, PROBLEM_API_KEY to 1, PROBLEM_API to 3)
val ALERT_TITLES = mapOf(
    PROBLEM_NOTIFICATIONS to "Can't read notifications",
    PROBLEM_API_KEY to "Claude API key or credit problem",
    PROBLEM_API to "Claude API keeps failing",
)

// Keeps the failure counts in the database, so they survive restarts
class Health(private val dao: DatebookDao) {
    // Count one more failure, and return true the moment it reaches the threshold (when the alert should appear)
    suspend fun failed(kind: String): Boolean {
        val count = (dao.failures(kind) ?: 0) + 1
        dao.saveFailures(FailureCount(kind, count))
        return count == ALERT_THRESHOLDS.getValue(kind)
    }

    // Reset the count after a success, and return true if an alert was showing (so it can be removed)
    suspend fun succeeded(kind: String): Boolean {
        val count = dao.failures(kind) ?: 0

        // Only write when there is something to reset
        if (count > 0) {
            dao.saveFailures(FailureCount(kind, 0))
        }
        return count >= ALERT_THRESHOLDS.getValue(kind)
    }
}
