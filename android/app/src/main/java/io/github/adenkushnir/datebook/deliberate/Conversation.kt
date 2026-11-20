// A Deliberate conversation as Claude sees it: turns of text, tool calls and tool results, saved as JSON between app
// starts, turned into a request with Datebook's tools and web search, and Claude's reply turned back into blocks
package io.github.adenkushnir.datebook.deliberate
import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlockParam
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
const val ROLE_USER = "user"
const val ROLE_ASSISTANT = "assistant"
const val BLOCK_TEXT = "text"
const val BLOCK_TOOL_USE = "tool_use"
const val BLOCK_TOOL_RESULT = "tool_result"
private const val MAX_TOKENS = 4000L
private const val WEB_SEARCHES = 5L
private const val WEB_FETCHES = 5L
private const val WEB_PAGE_TOKENS = 15000L
private const val MAX_PAUSES = 3  // how many times a long web search may be continued
private val MAPPER = ObjectMapper()
private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
private val LIST_TYPE = object : TypeReference<List<Map<String, Any?>>>() {}

// One block of a turn: text, a tool call (its id, the tool's name and its input as JSON) or a tool's result (the
// call's id, the result's text, and whether it's an error)
data class Block(
    val type: String,
    val text: String = "",
    val id: String = "",
    val name: String = "",
    val input: String = "{}",
    val isError: Boolean = false,
)

// One turn: who said it (user or assistant) and its blocks
data class Turn(val role: String, val blocks: List<Block>)

// A tool Claude may call: its name, what it does, and its input's JSON schema
data class ToolSpec(val name: String, val description: String, val schema: Map<String, Any>)

// A text block
fun textBlock(text: String): Block {
    return Block(BLOCK_TEXT, text = text)
}

// A tool result block answering a tool call
fun resultBlock(toolUseId: String, text: String, isError: Boolean = false): Block {
    return Block(BLOCK_TOOL_RESULT, text = text, id = toolUseId, isError = isError)
}

// A block as plain JSON-ready values
private fun blockMap(block: Block): Map<String, Any> {
    return mapOf("type" to block.type, "text" to block.text, "id" to block.id, "name" to block.name,
        "input" to block.input, "isError" to block.isError)
}

// A block from plain JSON values
private fun blockOf(map: Map<String, Any?>): Block {
    return Block(map["type"]?.toString().orEmpty(), map["text"]?.toString().orEmpty(), map["id"]?.toString().orEmpty(),
        map["name"]?.toString().orEmpty(), map["input"]?.toString() ?: "{}", map["isError"] == true)
}

// Turns as JSON, for saving a chat
fun turnsToJson(turns: List<Turn>): String {
    return MAPPER.writeValueAsString(turns.map { mapOf("role" to it.role, "blocks" to it.blocks.map(::blockMap)) })
}

// Turns from saved JSON (empty for an empty or unreadable string)
fun turnsFromJson(json: String): List<Turn> {
    // A new chat has no turns yet
    if (json.isBlank()) {
        return emptyList()
    }
    return runCatching {
        MAPPER.readValue(json, LIST_TYPE).map { turn ->
            @Suppress("UNCHECKED_CAST")
            val blocks = (turn["blocks"] as? List<Map<String, Any?>>).orEmpty().map(::blockOf)
            Turn(turn["role"]?.toString().orEmpty(), blocks)
        }
    }.getOrDefault(emptyList())
}

// Blocks as JSON, for the blocks waiting to go with the next message
fun blocksToJson(blocks: List<Block>): String {
    return MAPPER.writeValueAsString(blocks.map(::blockMap))
}

// Blocks from saved JSON (empty for an empty or unreadable string)
fun blocksFromJson(json: String): List<Block> {
    // Nothing waiting
    if (json.isBlank()) {
        return emptyList()
    }
    return runCatching { MAPPER.readValue(json, LIST_TYPE).map(::blockOf) }.getOrDefault(emptyList())
}

// Add user blocks to the conversation: onto the last turn when it's already the user's (a message after a failed
// call), else as a new turn; tool results go first, as Claude expects
fun withUserBlocks(turns: List<Turn>, blocks: List<Block>): List<Turn> {
    val last = turns.lastOrNull()
    val ordered = blocks.sortedBy { if (it.type == BLOCK_TOOL_RESULT) 0 else 1 }

    // Join the last turn when it's the user's
    if (last != null && last.role == ROLE_USER) {
        val joined = (last.blocks + ordered).sortedBy { if (it.type == BLOCK_TOOL_RESULT) 0 else 1 }
        return turns.dropLast(1) + Turn(ROLE_USER, joined)
    }
    return turns + Turn(ROLE_USER, ordered)
}

// A tool call's input as a map (empty when it can't be read)
fun inputMap(block: Block): Map<String, Any?> {
    return runCatching { MAPPER.readValue(block.input, MAP_TYPE) }.getOrDefault(emptyMap())
}

// A value as JSON
fun toJson(value: Any?): String {
    return MAPPER.writeValueAsString(value)
}

// A JSON object as a map (empty when it can't be read)
fun mapFromJson(json: String): Map<String, Any?> {
    return runCatching { MAPPER.readValue(json, MAP_TYPE) }.getOrDefault(emptyMap())
}

// A JSON list of objects as maps (empty when it can't be read), like a change's undo steps
fun listFromJson(json: String): List<Map<String, Any?>> {
    return runCatching { MAPPER.readValue(json, LIST_TYPE) }.getOrDefault(emptyList())
}

// One block as the request's content block
private fun paramOf(block: Block): BetaContentBlockParam {
    // Each kind of block has its own request type
    return when (block.type) {
        BLOCK_TOOL_USE -> {
            val input = BetaToolUseBlockParam.Input.builder()

            // Copy each input value over
            for ((name, value) in inputMap(block)) {
                input.putAdditionalProperty(name, JsonValue.from(value))
            }
            BetaContentBlockParam.ofToolUse(BetaToolUseBlockParam.builder().id(block.id).name(block.name)
                .input(input.build()).build())
        }
        BLOCK_TOOL_RESULT -> BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
            .toolUseId(block.id).content(block.text.ifBlank { "(empty)" }).isError(block.isError).build())
        else -> BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(block.text.ifBlank { "…" }).build())
    }
}

// A tool as the request's tool
private fun toolOf(spec: ToolSpec): BetaTool {
    val properties = BetaTool.InputSchema.Properties.builder()

    // Copy each input field's schema over
    @Suppress("UNCHECKED_CAST")
    for ((name, schema) in (spec.schema["properties"] as? Map<String, Any>).orEmpty()) {
        properties.putAdditionalProperty(name, JsonValue.from(schema))
    }
    @Suppress("UNCHECKED_CAST")
    val required = (spec.schema["required"] as? List<String>).orEmpty()
    val schema = BetaTool.InputSchema.builder().properties(properties.build()).required(required).build()
    return BetaTool.builder().name(spec.name).description(spec.description).inputSchema(schema).build()
}

// Build the request: the chosen model at medium effort, Deliberate's instructions and tools (plus web search and web
// fetch, which run on Anthropic's side), the whole conversation, and prompt caching so later turns cost less
fun deliberateRequest(model: String, system: String, tools: List<ToolSpec>, turns: List<Turn>):
    MessageCreateParams.Builder {
    val output = BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build()
    val request = MessageCreateParams.builder()
        .model(model)
        .maxTokens(MAX_TOKENS)
        .outputConfig(output)
        .system(system)
        .cacheControl(BetaCacheControlEphemeral.builder().build())
        .addTool(BetaWebSearchTool20260209.builder().maxUses(WEB_SEARCHES).build())
        .addTool(BetaWebFetchTool20260209.builder().maxUses(WEB_FETCHES).maxContentTokens(WEB_PAGE_TOKENS).build())

    // Offer each tool
    for (tool in tools) {
        request.addTool(toolOf(tool))
    }

    // Add each turn in order
    for (turn in turns) {
        val blocks = turn.blocks.map(::paramOf)
        request.addMessage(if (turn.role == ROLE_ASSISTANT) BetaMessageParam.ofAssistantOfContentBlockParams(blocks)
            else BetaMessageParam.ofUserOfContentBlockParams(blocks))
    }
    return request
}

// The finished request (see deliberateRequest)
fun buildDeliberateRequest(model: String, system: String, tools: List<ToolSpec>, turns: List<Turn>):
    MessageCreateParams {
    return deliberateRequest(model, system, tools, turns).build()
}

// Join text blocks that follow each other into one (a web answer comes as many small blocks, one per citation)
fun joinTexts(blocks: List<Block>): List<Block> {
    val joined = mutableListOf<Block>()

    // Add each block, or extend the text just before it
    for (block in blocks) {
        val last = joined.lastOrNull()

        // Text after text joins it
        if (block.type == BLOCK_TEXT && last?.type == BLOCK_TEXT) {
            joined[joined.size - 1] = last.copy(text = last.text + block.text)
        } else {
            joined.add(block)
        }
    }
    return joined
}

// Ask Claude for its next turn and return its text and tool calls as blocks, continuing a long web search when it
// pauses (SDK errors are passed on to the caller); web search blocks stay on Anthropic's side
fun askClaude(client: AnthropicClient, model: String, system: String, tools: List<ToolSpec>, turns: List<Turn>):
    List<Block> {
    val request = deliberateRequest(model, system, tools, turns)
    val blocks = mutableListOf<Block>()
    var response = client.beta().messages().create(request.build())

    // Keep each answer's text and tool calls, continuing while the web search pauses
    for (round in 0..MAX_PAUSES) {
        // Keep the text and tool calls, in order
        for (content in response.content()) {
            val text = content.text().orElse(null)
            val call = content.toolUse().orElse(null)

            // Text and tool calls are all Deliberate uses
            if (text != null) {
                blocks.add(textBlock(text.text()))
            } else if (call != null) {
                blocks.add(Block(BLOCK_TOOL_USE, id = call.id(), name = call.name(),
                    input = MAPPER.writeValueAsString(call._input().convert(MAP_TYPE))))
            }
        }

        // Only a paused answer needs another round
        if (response.stopReason().orElse(null) != BetaStopReason.PAUSE_TURN || round == MAX_PAUSES) {
            break
        }
        request.addMessage(response)
        response = client.beta().messages().create(request.build())
    }
    return joinTexts(blocks)
}
