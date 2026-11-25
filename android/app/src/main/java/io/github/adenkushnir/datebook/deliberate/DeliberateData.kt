// Deliberate's saved data: the chats (kept 30 days), what each chat shows, the changes it made (with what Undo puts
// back) and the rules it set up
package io.github.adenkushnir.datebook.deliberate
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
const val KEEP_CHATS_MILLIS = 30L * 24 * 60 * 60 * 1000
const val LINE_ME = "me"
const val LINE_REPLY = "reply"
const val LINE_CHOICES = "choices"
const val LINE_PREVIEW = "preview"
const val LINE_DONE = "done"
const val LINE_NOTE = "note"
const val LINE_ERROR = "error"
const val LINE_JOURNEY = "journey"  // a journey card, with Add as a plan
const val STATE_CONFIRMED = "confirmed"
const val STATE_CANCELLED = "cancelled"

// One conversation: its title (the first thing you asked), when it started and last changed, the conversation as
// Claude sees it (JSON), and what goes to Claude with your next message (answers to its questions, notes about Undo)
@Entity(tableName = "deliberate_chats")
data class DeliberateChat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turns: String,
    val pending: String,
)

// One thing a chat shows: your message, Claude's reply, a question with choices, a preview to confirm, a change made
// (with Undo), a note or an error; data holds the choices, the preview or the change's id (JSON) and state what you
// picked, or whether a preview was confirmed or cancelled
@Entity(tableName = "deliberate_lines")
data class DeliberateLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val kind: String,
    val text: String,
    val data: String,
    val state: String,
    val createdAt: Long,
)

// One change Deliberate made: what it did in words, which chat, when, the steps that undo it (JSON) and whether it
// was undone (kept 30 days)
@Entity(tableName = "deliberate_changes")
data class DeliberateChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val summary: String,
    val createdAt: Long,
    val undo: String,
    val reverted: Boolean,
)

// One rule: its kind, which events or plans it covers (title words, a place, a sender or group; empty means any), its
// value (minutes, a travel mode, things to bring, a habit), whether it's on, and when it was made
@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val words: String,
    val place: String,
    val sender: String,
    val value: String,
    val isOn: Boolean,
    val createdAt: Long,
)

// Reads and writes Deliberate's chats, lines, changes and rules
@Dao
interface DeliberateDao {
    // Watch the chats, the latest first
    @Query("SELECT * FROM deliberate_chats ORDER BY updatedAt DESC")
    fun watchChats(): Flow<List<DeliberateChat>>

    // Return one chat, or null once it's gone
    @Query("SELECT * FROM deliberate_chats WHERE id = :id")
    suspend fun chat(id: Long): DeliberateChat?

    // Save a new chat and return its id
    @Insert
    suspend fun saveChat(chat: DeliberateChat): Long

    // Save a changed chat over its old version
    @Update
    suspend fun updateChat(chat: DeliberateChat)

    // Watch what one chat shows, in order
    @Query("SELECT * FROM deliberate_lines WHERE chatId = :chatId ORDER BY id")
    fun watchLines(chatId: Long): Flow<List<DeliberateLine>>

    // Return one line, or null once it's gone
    @Query("SELECT * FROM deliberate_lines WHERE id = :id")
    suspend fun line(id: Long): DeliberateLine?

    // Save a new line and return its id
    @Insert
    suspend fun saveLine(line: DeliberateLine): Long

    // Save a changed line over its old version
    @Update
    suspend fun updateLine(line: DeliberateLine)

    // Return a chat's lines that still wait for an answer (a question or a preview with no state yet)
    @Query("SELECT * FROM deliberate_lines WHERE chatId = :chatId AND kind IN ('choices', 'preview') AND state = ''")
    suspend fun openLines(chatId: Long): List<DeliberateLine>

    // Watch the changes, the latest first
    @Query("SELECT * FROM deliberate_changes ORDER BY createdAt DESC")
    fun watchChanges(): Flow<List<DeliberateChange>>

    // Return one change, or null once it's gone
    @Query("SELECT * FROM deliberate_changes WHERE id = :id")
    suspend fun change(id: Long): DeliberateChange?

    // Save a new change and return its id
    @Insert
    suspend fun saveChange(change: DeliberateChange): Long

    // Mark a change undone
    @Query("UPDATE deliberate_changes SET reverted = 1 WHERE id = :id")
    suspend fun setReverted(id: Long)

    // Watch the rules, the newest first
    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun watchRules(): Flow<List<Rule>>

    // Return every rule
    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    suspend fun rules(): List<Rule>

    // Return every rule without suspending, for code already off the main thread (the Claude call)
    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun rulesNow(): List<Rule>

    // Return one rule, or null once it's gone
    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun rule(id: Long): Rule?

    // Save a new rule (or one being put back by Undo, keeping its id) and return its id
    @Insert
    suspend fun saveRule(rule: Rule): Long

    // Save a changed rule over its old version
    @Update
    suspend fun updateRule(rule: Rule)

    // Delete a rule
    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    // Delete chats not used since the cutoff
    @Query("DELETE FROM deliberate_chats WHERE updatedAt < :cutoff")
    suspend fun deleteChatsBefore(cutoff: Long)

    // Delete the lines of chats that are gone
    @Query("DELETE FROM deliberate_lines WHERE chatId NOT IN (SELECT id FROM deliberate_chats)")
    suspend fun deleteOrphanLines()

    // Delete changes made before the cutoff
    @Query("DELETE FROM deliberate_changes WHERE createdAt < :cutoff")
    suspend fun deleteChangesBefore(cutoff: Long)
}
