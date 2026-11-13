// The database queries Datebook uses, one per action (a port of the Python version's store.py)
package io.github.adenkushnir.datebook.data
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Reads and writes the seen texts, suggestions, retry queue and failure counts
@Dao
interface DatebookDao {
    // Return when a text with this fingerprint was last seen, or null if never
    @Query("SELECT seenAt FROM seen WHERE hash = :hash")
    suspend fun seenAt(hash: String): Long?

    // Record (or refresh) when a text was seen
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSeen(seen: SeenText)

    // Save a suggestion and return its id
    @Insert
    suspend fun saveSuggestion(suggestion: Suggestion): Long

    // Return one suggestion by id, or null if it is gone
    @Query("SELECT * FROM suggestions WHERE id = :id")
    suspend fun suggestion(id: Long): Suggestion?

    // Change a suggestion's status, like waiting to added
    @Query("UPDATE suggestions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    // Watch the suggestions with a status, newest first, for the home screen
    @Query("SELECT * FROM suggestions WHERE status = :status ORDER BY createdAt DESC")
    fun watchSuggestions(status: String): Flow<List<Suggestion>>

    // Save a text to try again later
    @Insert
    suspend fun saveRetry(retry: RetryText): Long

    // Return the saved texts due by now, oldest first
    @Query("SELECT * FROM retry WHERE nextTry <= :now ORDER BY id")
    suspend fun dueRetries(now: Long): List<RetryText>

    // Return the time of the next retry, or null if none are waiting
    @Query("SELECT MIN(nextTry) FROM retry")
    suspend fun nextRetryAt(): Long?

    // Set when to try a saved text next
    @Query("UPDATE retry SET nextTry = :nextTry WHERE id = :id")
    suspend fun rescheduleRetry(id: Long, nextTry: Long)

    // Remove a saved text once it worked or was given up on
    @Query("DELETE FROM retry WHERE id = :id")
    suspend fun deleteRetry(id: Long)

    // Return how many times in a row a kind of problem has happened, or null if never
    @Query("SELECT failures FROM health WHERE kind = :kind")
    suspend fun failures(kind: String): Int?

    // Set the failure count for a kind of problem
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFailures(count: FailureCount)

    // Delete fingerprints of texts seen before the cutoff
    @Query("DELETE FROM seen WHERE seenAt < :cutoff")
    suspend fun deleteSeenBefore(cutoff: Long)

    // Return every suggestion with a status
    @Query("SELECT * FROM suggestions WHERE status = :status")
    suspend fun suggestionsWithStatus(status: String): List<Suggestion>

    // Delete every suggestion with a status
    @Query("DELETE FROM suggestions WHERE status = :status")
    suspend fun deleteWithStatus(status: String)

    // Delete one suggestion
    @Query("DELETE FROM suggestions WHERE id = :id")
    suspend fun deleteSuggestion(id: Long)

    // Return the flight plans that aren't ignored, for the flight checks
    @Query("SELECT * FROM suggestions WHERE flight != '' AND status != 'ignored'")
    suspend fun flightSuggestions(): List<Suggestion>

    // Delete suggestions created before the cutoff
    @Query("DELETE FROM suggestions WHERE createdAt < :cutoff")
    suspend fun deleteSuggestionsBefore(cutoff: Long)
}
