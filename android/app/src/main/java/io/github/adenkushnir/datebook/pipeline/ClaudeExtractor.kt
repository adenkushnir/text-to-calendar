// Asks Claude which events a text sets up, with the same prompt and event-list format as the Python version
package io.github.adenkushnir.datebook.pipeline
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
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
const val SHARED_TEXT_APP = "datebook.shared.text"
const val SHARED_IMAGE_APP = "datebook.shared.image"
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

// A text to ask Claude about: who sent it, what it says, when it arrived in the phone's time, which app, and which
// group chat it came from (empty for one-to-one); exactTime means receivedAt is the message's own timestamp, and
// image is a shared picture as base64 JPEG (empty for texts)
data class IncomingText(
    val sender: String,
    val text: String,
    val receivedAt: LocalDateTime,
    val app: String = "",
    val group: String = "",
    val exactTime: Boolean = false,
    val image: String = "",
)

// Return true if something came from long-press or Share rather than a notification
fun isShared(app: String): Boolean {
    return app == SHARED_TEXT_APP || app == SHARED_IMAGE_APP
}

// Read Claude's instructions, shared word for word with the Python version, from the app's resources
private fun loadSystemPrompt(): String {
    val stream = Event::class.java.getResourceAsStream("/datebook/system_prompt.txt")
    return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("system_prompt.txt is missing")
}

// Make a Claude client for the given API key, giving each request up to 60 seconds
fun makeClient(apiKey: String): AnthropicClient {
    return AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(60)).build()
}

// Build the message for Claude: when it arrived (with weekday and phone zone), who sent it, then the text in tags;
// for group chats the group's name is sent instead of the sender's, and shared things say they were shared
fun buildPrompt(incoming: IncomingText, phoneZone: String): String {
    val received = incoming.receivedAt.format(RECEIVED_FORMAT)
    val weekday = incoming.receivedAt.format(WEEKDAY_FORMAT)
    val zone = if (phoneZone.isNotBlank()) ", phone time zone $phoneZone" else ""  // only when the zone is known

    // Say who it's from: the phone's owner for shared things, the group for group chats (never the sender too)
    val from = when {
        isShared(incoming.app) -> "the phone's owner (shared to Datebook)"
        incoming.group.isNotBlank() -> "${incoming.group} (group chat)"
        else -> incoming.sender
    }

    // Point Claude at the picture when one was shared
    val text = when {
        incoming.image.isBlank() -> incoming.text
        incoming.text.isBlank() -> "(see the attached image)"
        else -> "${incoming.text}\n(and the attached image)"
    }
    return "Received: $received ($weekday$zone)\nFrom: $from\n<message>\n$text\n</message>"
}

// Return true if the model supports server-side refusal fallbacks (Haiku models don't)
fun supportsFallbacks(model: String): Boolean {
    return !model.contains("haiku")
}

// Build the request: the chosen model at low effort, the events format, and server-side refusal fallbacks when the
// model supports them
fun buildRequest(incoming: IncomingText, phoneZone: String, model: String = DEFAULT_MODEL): MessageCreateParams {
    val schema = BetaJsonOutputFormat.Schema.builder()

    // Copy each part of the events format into the request's schema
    for ((name, value) in EVENTS_SCHEMA) {
        schema.putAdditionalProperty(name, JsonValue.from(value))
    }
    val format = BetaJsonOutputFormat.builder().schema(schema.build()).build()
    val output = BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).format(format).build()
    val request = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16000L)
        .outputConfig(output)
        .system(SYSTEM_PROMPT)
    val prompt = buildPrompt(incoming, phoneZone)

    // Send a shared picture before the words, or just the words for a text
    if (incoming.image.isNotBlank()) {
        val image = BetaBase64ImageSource.builder().data(incoming.image)
            .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).build()
        request.addUserMessageOfContentBlockParams(listOf(BetaContentBlockParam.ofImage(image),
            BetaContentBlockParam.ofText(prompt)))
    } else {
        request.addUserMessage(prompt)
    }

    // Ask for a fallback model on refusals, where the chosen model supports it
    if (supportsFallbacks(model)) {
        request.addBeta(FALLBACK_BETA).fallbacksDefault()
    }
    return request.build()
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
