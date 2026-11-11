// Asks Claude which events a text sets up, with the same prompt and event-list format as the Python version
package io.github.adenkushnir.datebook.pipeline
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
const val DEFAULT_MODEL = "claude-opus-5-5"
private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
private val RECEIVED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
private val WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEEE", Locale.US)
private val MAPPER = ObjectMapper()
val SYSTEM_PROMPT: String = loadSystemPrompt()
private val EVENT_ITEM = mapOf(
    "type" to "object",
    "properties" to mapOf(
        "title" to mapOf("type" to "string"),
        "start" to mapOf("type" to "string"),
        "end" to mapOf("type" to "string"),
        "all_day" to mapOf("type" to "boolean"),
        "location" to mapOf("type" to "string"),
        "repeat" to mapOf("type" to "string"),
        "timezone" to mapOf("type" to "string"),
    ),
    "required" to listOf("title", "start", "end", "all_day", "location", "repeat", "timezone"),
    "additionalProperties" to false,
)
val EVENTS_SCHEMA = mapOf(
    "type" to "object",
    "properties" to mapOf("events" to mapOf("type" to "array", "items" to EVENT_ITEM)),
    "required" to listOf("events"),
    "additionalProperties" to false,
)

// A text to ask Claude about: who sent it, what it says, and when it arrived in the phone's time
data class IncomingText(val sender: String, val text: String, val receivedAt: LocalDateTime, val app: String = "")

// Read Claude's instructions, shared word for word with the Python version, from the app's resources
private fun loadSystemPrompt(): String {
    val stream = Event::class.java.getResourceAsStream("/datebook/system_prompt.txt")
    return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("system_prompt.txt is missing")
}

// Make a Claude client for the given API key, giving each request up to 60 seconds
fun makeClient(apiKey: String): AnthropicClient {
    return AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(60)).build()
}

// Build the message for Claude: when it arrived (with weekday and phone zone), who sent it, then the text in tags
fun buildPrompt(incoming: IncomingText, phoneZone: String): String {
    val received = incoming.receivedAt.format(RECEIVED_FORMAT)
    val weekday = incoming.receivedAt.format(WEEKDAY_FORMAT)
    val zone = if (phoneZone.isNotBlank()) ", phone time zone $phoneZone" else ""  // only when the zone is known
    return "Received: $received ($weekday$zone)\nFrom: ${incoming.sender}\n<message>\n${incoming.text}\n</message>"
}

// Build the request: the chosen model at low effort, the events format, and server-side refusal fallbacks
fun buildRequest(incoming: IncomingText, phoneZone: String, model: String = DEFAULT_MODEL): MessageCreateParams {
    val schema = BetaJsonOutputFormat.Schema.builder()

    // Copy each part of the events format into the request's schema
    for ((name, value) in EVENTS_SCHEMA) {
        schema.putAdditionalProperty(name, JsonValue.from(value))
    }
    val format = BetaJsonOutputFormat.builder().schema(schema.build()).build()
    val output = BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).format(format).build()
    return MessageCreateParams.builder()
        .model(model)
        .maxTokens(16000L)
        .addBeta(FALLBACK_BETA)
        .fallbacksDefault()
        .outputConfig(output)
        .system(SYSTEM_PROMPT)
        .addUserMessage(buildPrompt(incoming, phoneZone))
        .build()
}

// Ask Claude about one text and return the events it found, or an empty list (SDK errors are passed on to the caller)
fun extractEvents(client: AnthropicClient, incoming: IncomingText, phoneZone: String, model: String): List<Event> {
    val response = client.beta().messages().create(buildRequest(incoming, phoneZone, model))
    val stop = response.stopReason().orElse(null)
    val texts = response.content().mapNotNull { it.text().orElse(null)?.text() }  // keep only the text blocks
    val stopped = stop == BetaStopReason.REFUSAL || stop == BetaStopReason.MAX_TOKENS  // declined or cut off
    return parseResponse(stopped, texts.joinToString(""), phoneZone)
}

// Turn Claude's reply into events, empty if it declined, was cut off or found none (throws on JSON that can't be read)
fun parseResponse(stopped: Boolean, json: String, phoneZone: String): List<Event> {
    // Give up on refusals, replies that hit the token limit, and empty replies
    if (stopped || json.isBlank()) {
        return emptyList()
    }
    val found = MAPPER.readTree(json).path("events")

    // Turn each item into an event, dropping any without a start time
    return found.mapNotNull { item ->
        val event = Event(
            title = item.path("title").asText(""),
            start = item.path("start").asText(""),
            end = item.path("end").asText(""),
            allDay = item.path("all_day").asBoolean(false),
            location = item.path("location").asText(""),
            repeat = item.path("repeat").asText(""),
            timezone = item.path("timezone").asText(""),
            phoneTimezone = phoneZone,
        )
        if (event.start.isBlank()) null else event  // no start means nothing to put on the calendar
    }
}
