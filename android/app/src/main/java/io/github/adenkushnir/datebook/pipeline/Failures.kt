// Sorts what went wrong with a Claude call, the same way the Python version does: log line, retry, and alert kind
package io.github.adenkushnir.datebook.pipeline
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import java.io.IOException
const val PROBLEM_NOTIFICATIONS = "notifications"
const val PROBLEM_API_KEY = "api_key"
const val PROBLEM_API = "api"

// What went wrong: the line for the log, whether to try again later, and which alert it counts toward (null for none)
data class Failure(val line: String, val retry: Boolean, val problem: String?)

// Sort an API error by its HTTP status and message
fun classifyApiError(status: Int, message: String): Failure {
    // Pick the result for this kind of error
    return when {
        status == 401 || status == 403 ->
            Failure("date found, API key rejected ($status): $message", false, PROBLEM_API_KEY)
        status == 429 -> Failure("date found, rate limited", true, PROBLEM_API)
        status >= 500 -> Failure("date found, API error $status: $message", true, PROBLEM_API)
        "credit" in message.lowercase() -> Failure("date found, API error $status: $message", false, PROBLEM_API_KEY)
        else -> Failure("date found, API error $status: $message", false, PROBLEM_API)
    }
}

// Sort any error thrown while asking Claude; no internet is retried but never alerts, since retries cover it
fun classifyError(error: Throwable): Failure {
    // Pick the result for this kind of error
    return when (error) {
        is AnthropicServiceException ->
            classifyApiError(error.statusCode(), error.apiMessage().orElse(error.message ?: ""))
        is AnthropicIoException, is AnthropicRetryableException -> Failure("date found, no connection", true, null)
        is IOException -> Failure("date found, unreadable reply", false, PROBLEM_API)
        else -> Failure("date found, error: ${error.message}", false, PROBLEM_API)
    }
}
