// Tests for sorting Claude call failures into log lines, retries and alert kinds, like the Python version
package io.github.adenkushnir.datebook.pipeline
import com.anthropic.errors.AnthropicIoException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

// Checks each kind of failure gets the right line, retry and alert kind
class FailuresTest {
    // A rejected key alerts straight away and is not retried
    @Test
    fun badKey() {
        assertEquals(Failure("date found, API key rejected (401): invalid x-api-key", false, PROBLEM_API_KEY),
            classifyApiError(401, "invalid x-api-key"))
        assertEquals(PROBLEM_API_KEY, classifyApiError(403, "no access").problem)
    }

    // Running out of credit counts as a key problem and is not retried
    @Test
    fun outOfCredit() {
        val failure = classifyApiError(400, "Your credit balance is too low to access the Anthropic API.")
        assertEquals(PROBLEM_API_KEY, failure.problem)
        assertEquals(false, failure.retry)
    }

    // Rate limits and server errors are retried and count toward "API keeps failing"
    @Test
    fun retriedErrors() {
        assertEquals(Failure("date found, rate limited", true, PROBLEM_API), classifyApiError(429, "slow down"))
        assertEquals(true, classifyApiError(529, "overloaded").retry)
        assertEquals(PROBLEM_API, classifyApiError(500, "down").problem)
    }

    // Other bad requests are not retried
    @Test
    fun otherBadRequest() {
        assertEquals(Failure("date found, API error 400: bad", false, PROBLEM_API), classifyApiError(400, "bad"))
    }

    // No internet is retried but never alerts, and an unreadable reply counts as an API problem
    @Test
    fun connectionAndUnreadable() {
        assertEquals(Failure("date found, no connection", true, null), classifyError(AnthropicIoException("offline")))
        val unreadable = Failure("date found, unreadable reply", false, PROBLEM_API)
        assertEquals(unreadable, classifyError(IOException("bad json")))
    }
}
