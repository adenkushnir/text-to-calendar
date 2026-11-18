// Runs Deliberate's conversations: your message goes to Claude with Datebook's tools; its look-ups are answered on the
// phone, its questions become choices to tap and its changes a preview; Confirm makes them (with Undo), Cancel
// doesn't, and every step is saved so a chat picks up where it left off
package io.github.adenkushnir.datebook.deliberate
import android.content.Context
import android.util.Log
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.pipeline.PROBLEM_API_KEY
import io.github.adenkushnir.datebook.pipeline.classifyError
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
private const val MAX_ROUNDS = 8  // look-ups Claude may chain before it has to answer
private const val TITLE_LENGTH = 60

// The words shown when Claude can't be asked, by what went wrong
fun deliberateError(error: Throwable): String {
    val failure = classifyError(error)

    // Pick the words for the problem
    return when {
        failure.problem == PROBLEM_API_KEY -> "Claude didn't accept your API key, or it's out of credit. Check it in " +
            "Settings → Claude & API key."
        failure.retry -> "Couldn't reach Claude. Check your internet and try again."
        else -> "Something went wrong asking Claude. Try again."
    }
}

// Deliberate's conversations; ask sends the instructions and turns to Claude and returns its reply's blocks
class Deliberate(private val context: Context, private val ask: (system: String, turns: List<Turn>) -> List<Block>) {
    private val graph = AppGraph.get(context)
    private val dao = graph.deliberateDao
    private val runner = DeliberateRunner(context)
    private val reader = DeliberateReader(context)

    // Save a line in a chat
    private suspend fun line(chatId: Long, kind: String, text: String, data: Map<String, Any?> = emptyMap()): Long {
        return dao.saveLine(DeliberateLine(chatId = chatId, kind = kind, text = text, data = toJson(data), state = "",
            createdAt = System.currentTimeMillis()))
    }

    // Save a chat's turns and the blocks waiting for your next message
    private suspend fun saveChat(chatId: Long, turns: List<Turn>, pending: List<Block>) {
        val chat = dao.chat(chatId) ?: return
        dao.updateChat(chat.copy(turns = turnsToJson(turns), pending = blocksToJson(pending),
            updatedAt = System.currentTimeMillis()))
    }

    // Answer every question or preview still open in a chat (except one), so Claude gets a result for each of its
    // calls; returns those results
    private suspend fun closeOpen(chatId: Long, exceptLine: Long, typed: Boolean): List<Block> {
        val results = mutableListOf<Block>()

        // Close each open line
        for (open in dao.openLines(chatId).filter { it.id != exceptLine }) {
            val toolUseId = mapFromJson(open.data)["toolUseId"]?.toString().orEmpty()

            // A question answered by typing, or a preview left unconfirmed
            if (open.kind == LINE_CHOICES) {
                dao.updateLine(open.copy(state = "typed"))
                results.add(resultBlock(toolUseId, if (typed) "The user typed a reply instead (see their message)."
                    else "No answer."))
            } else {
                dao.updateLine(open.copy(state = STATE_CANCELLED))
                results.add(resultBlock(toolUseId, "Not confirmed, so nothing changed."))
            }
        }
        return results
    }

    // Send a message: in a new chat when chatId is 0; returns the chat's id
    suspend fun send(chatId: Long, text: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = if (chatId > 0 && dao.chat(chatId) != null) chatId
            else dao.saveChat(DeliberateChat(title = text.trim().take(TITLE_LENGTH), createdAt = now, updatedAt = now,
                turns = "", pending = ""))
        line(id, LINE_ME, text.trim())
        val chat = dao.chat(id) ?: return@withContext id
        val blocks = blocksFromJson(chat.pending) + closeOpen(id, 0, typed = true) + textBlock(text.trim())
        saveChat(id, withUserBlocks(turnsFromJson(chat.turns), blocks), emptyList())
        run(id)
        id
    }

    // Tap one of a question's choices
    suspend fun pick(lineId: Long, choice: String) = withContext(Dispatchers.IO) {
        val question = dao.line(lineId)?.takeIf { it.state.isBlank() } ?: return@withContext
        val chat = dao.chat(question.chatId) ?: return@withContext
        dao.updateLine(question.copy(state = "picked:$choice"))
        line(chat.id, LINE_ME, choice)
        val toolUseId = mapFromJson(question.data)["toolUseId"]?.toString().orEmpty()
        val blocks = blocksFromJson(chat.pending) + closeOpen(chat.id, lineId, typed = false) +
            resultBlock(toolUseId, choice)
        saveChat(chat.id, withUserBlocks(turnsFromJson(chat.turns), blocks), emptyList())
        run(chat.id)
    }

    // Confirm a preview: make its changes, keep how to undo them, and tell Claude with your next message
    suspend fun confirm(lineId: Long) = withContext(Dispatchers.IO) {
        val preview = dao.line(lineId)?.takeIf { it.state.isBlank() } ?: return@withContext
        val chat = dao.chat(preview.chatId) ?: return@withContext
        val data = mapFromJson(preview.data)
        dao.updateLine(preview.copy(state = STATE_CONFIRMED))
        @Suppress("UNCHECKED_CAST")
        val applied = runner.apply(changesOf((data["input"] as? Map<String, Any?>).orEmpty()))
        val summary = data["summary"]?.toString().orEmpty().ifBlank { data["title"]?.toString().orEmpty() }
        var changeId = 0L

        // Keep the change for Undo when anything was made
        if (applied.done.isNotEmpty()) {
            changeId = dao.saveChange(DeliberateChange(chatId = chat.id, summary = summary,
                createdAt = System.currentTimeMillis(), undo = toJson(applied.undo), reverted = false))
        }
        val failed = if (applied.failed.isEmpty()) "" else "\nCouldn't do: " + applied.failed.joinToString("; ")
        val text = if (applied.done.isEmpty()) "Nothing could be changed.$failed" else "Done: $summary.$failed"
        line(chat.id, LINE_DONE, text, mapOf("changeId" to changeId))
        val result = "Confirmed. Made: ${applied.done.joinToString("; ").ifBlank { "nothing" }}." +
            if (applied.failed.isEmpty()) "" else " Couldn't make: ${applied.failed.joinToString("; ")}."
        val toolUseId = data["toolUseId"]?.toString().orEmpty()
        saveChat(chat.id, turnsFromJson(chat.turns), blocksFromJson(chat.pending) + resultBlock(toolUseId, result))
    }

    // Cancel a preview: nothing changes
    suspend fun cancel(lineId: Long) = withContext(Dispatchers.IO) {
        val preview = dao.line(lineId)?.takeIf { it.state.isBlank() } ?: return@withContext
        val chat = dao.chat(preview.chatId) ?: return@withContext
        dao.updateLine(preview.copy(state = STATE_CANCELLED))
        line(chat.id, LINE_NOTE, "Cancelled. Nothing changed.")
        val toolUseId = mapFromJson(preview.data)["toolUseId"]?.toString().orEmpty()
        saveChat(chat.id, turnsFromJson(chat.turns), blocksFromJson(chat.pending) +
            resultBlock(toolUseId, "The user cancelled, so nothing changed."))
    }

    // Undo a change: put everything back and tell the chat it came from
    suspend fun undo(changeId: Long) = withContext(Dispatchers.IO) {
        val change = dao.change(changeId)?.takeIf { !it.reverted } ?: return@withContext
        runner.undo(listFromJson(change.undo))
        dao.setReverted(change.id)
        val chat = dao.chat(change.chatId) ?: return@withContext

        // Say so in the chat it came from, and tell Claude with the next message
        line(chat.id, LINE_NOTE, "Undone: ${change.summary}.")
        saveChat(chat.id, turnsFromJson(chat.turns), blocksFromJson(chat.pending) +
            textBlock("(The user undid an earlier change: ${change.summary}.)"))
    }

    // Ask Claude again after a failure
    suspend fun retry(lineId: Long) = withContext(Dispatchers.IO) {
        val failed = dao.line(lineId)?.takeIf { it.state.isBlank() } ?: return@withContext
        dao.updateLine(failed.copy(state = "retried"))
        run(failed.chatId)
    }

    // Turn a rule on or off from the Rules list
    suspend fun setRuleOn(rule: Rule, on: Boolean) = withContext(Dispatchers.IO) {
        dao.updateRule(rule.copy(isOn = on))
    }

    // Delete a rule from the Rules list
    suspend fun deleteRule(ruleId: Long) = withContext(Dispatchers.IO) {
        dao.deleteRule(ruleId)
    }

    // Delete chats and changes over 30 days old
    suspend fun cleanUp(nowMillis: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        dao.deleteChatsBefore(nowMillis - KEEP_CHATS_MILLIS)
        dao.deleteOrphanLines()
        dao.deleteChangesBefore(nowMillis - KEEP_CHATS_MILLIS)
    }

    // Ask Claude, answering its look-ups on the phone, until it replies, asks a question or proposes changes
    private suspend fun run(chatId: Long) {
        var turns = turnsFromJson(dao.chat(chatId)?.turns.orEmpty())

        // Without a key there's nobody to ask
        if (graph.keyStore.load().isNullOrBlank()) {
            line(chatId, LINE_ERROR, "Add your Claude API key in Settings → Claude & API key first.")
            return
        }
        var results = emptyList<Block>()

        // One round per reply from Claude
        for (round in 1..MAX_ROUNDS) {
            val reply = try {
                ask(deliberateSystem(ZonedDateTime.now()), turns)
            } catch (error: Exception) {  // no internet, a rejected key, an API error
                Log.w("Datebook", "deliberate: ${error.message}")
                saveChat(chatId, turns, emptyList())
                line(chatId, LINE_ERROR, deliberateError(error))
                return
            }
            val blocks = reply.filter { it.type != BLOCK_TEXT || it.text.isNotBlank() }

            // An empty reply ends the round
            if (blocks.isEmpty()) {
                break
            }
            turns = turns + Turn(ROLE_ASSISTANT, blocks)
            val handled = handleReply(chatId, blocks)
            results = handled.first

            // A question or a preview waits for you; its look-up results go with your answer
            if (handled.second) {
                saveChat(chatId, turns, results)
                return
            }

            // A plain answer ends the conversation's turn
            if (results.isEmpty()) {
                saveChat(chatId, turns, emptyList())
                return
            }
            turns = withUserBlocks(turns, results)
            results = emptyList()
        }
        line(chatId, LINE_REPLY, "I couldn't finish looking that up. Try asking a simpler question.")
        saveChat(chatId, turns, results)
    }

    // Show Claude's text, answer its look-ups, and turn its question or proposal into a line; returns the look-up
    // results and whether it now waits for you
    private suspend fun handleReply(chatId: Long, blocks: List<Block>): Pair<List<Block>, Boolean> {
        val results = mutableListOf<Block>()
        var waiting = false

        // Handle each block in order
        for (block in blocks) {
            val input = inputMap(block)

            // Text is shown; each tool call is answered or shown
            if (block.type == BLOCK_TEXT) {
                line(chatId, LINE_REPLY, block.text.trim())
            } else if (block.type != BLOCK_TOOL_USE) {
                continue
            } else if (block.name == TOOL_ASK) {
                val choices = (input["choices"] as? List<*>).orEmpty().map { it.toString() }.filter { it.isNotBlank() }
                line(chatId, LINE_CHOICES, input["question"]?.toString().orEmpty(),
                    mapOf("toolUseId" to block.id, "choices" to choices.take(4)))
                waiting = true
            } else if (block.name == TOOL_PROPOSE) {
                val changes = changesOf(input)
                val problems = changes.mapNotNull { problemWith(it) }

                // A proposal Datebook can't show goes back to Claude to fix
                if (changes.isEmpty() || problems.isNotEmpty()) {
                    results.add(resultBlock(block.id, "Can't show this: " + problems.ifEmpty { listOf("no changes") }
                        .joinToString("; ") + ". Fix it and propose again.", isError = true))
                    continue
                }
                val preview = runner.preview(changes)
                line(chatId, LINE_PREVIEW, input["title"]?.toString().orEmpty().ifBlank { "Changes" },
                    mapOf("toolUseId" to block.id, "title" to input["title"], "summary" to input["summary"],
                        "lines" to preview.lines, "matches" to preview.matches, "input" to input))
                waiting = true
            } else {
                val answer = try {
                    reader.read(block.name, input)
                } catch (error: Exception) {  // a calendar or network problem while looking up
                    "Couldn't look that up: ${error.message}"
                }
                results.add(resultBlock(block.id, answer))
            }
        }
        return Pair(results, waiting)
    }
}
