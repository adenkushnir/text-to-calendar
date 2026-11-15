// The database queries Datebook uses, one per action (a port of the Python version's store.py)
package io.github.adenkushnir.datebook.data
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    // Save a changed suggestion over its old version
    @Update
    suspend fun updateSuggestion(suggestion: Suggestion)

    // Return the flight plans that aren't ignored, for the flight checks
    @Query("SELECT * FROM suggestions WHERE flight != '' AND status != 'ignored'")
    suspend fun flightSuggestions(): List<Suggestion>

    // Delete suggestions created before the cutoff
    @Query("DELETE FROM suggestions WHERE createdAt < :cutoff")
    suspend fun deleteSuggestionsBefore(cutoff: Long)

    // Return the offline guesses made for a text in the retry queue
    @Query("SELECT * FROM suggestions WHERE retryId = :retryId")
    suspend fun guessesFor(retryId: Long): List<Suggestion>

    // Return a text in the retry queue, or null once it's been checked or given up on
    @Query("SELECT * FROM retry WHERE id = :id")
    suspend fun retryText(id: Long): RetryText?

    // Watch which texts are still in the retry queue, so guesses show whether Claude is still to check them
    @Query("SELECT id FROM retry")
    fun watchRetryIds(): Flow<List<Long>>

    // Save a new follow-up and return its id
    @Insert
    suspend fun saveFollowUp(followUp: FollowUp): Long

    // Return a follow-up by id, or null if it's gone
    @Query("SELECT * FROM followups WHERE id = :id")
    suspend fun followUp(id: Long): FollowUp?

    // Save a changed follow-up over its old version
    @Update
    suspend fun updateFollowUp(followUp: FollowUp)

    // Return the open follow-ups of a kind
    @Query("SELECT * FROM followups WHERE kind = :kind AND status = 'open' ORDER BY date, createdAt")
    suspend fun openFollowUps(kind: String): List<FollowUp>

    // Watch the open follow-ups of a kind, soonest day first, for Home and the lists
    @Query("SELECT * FROM followups WHERE kind = :kind AND status = 'open' ORDER BY date, time, createdAt")
    fun watchOpenFollowUps(kind: String): Flow<List<FollowUp>>

    // Return the open follow-ups whose next nudge or reminder is due
    @Query("SELECT * FROM followups WHERE status = 'open' AND nextNudge > 0 AND nextNudge <= :now")
    suspend fun followUpsDue(now: Long): List<FollowUp>

    // Watch the plans that are deliveries (a carrier or tracking number) and not ignored, soonest first
    @Query("SELECT * FROM suggestions WHERE (packageCarrier != '' OR trackingNumber != '') AND status != 'ignored' " +
        "ORDER BY start")
    fun watchPackages(): Flow<List<Suggestion>>

    // Watch the plans that belong to a trip and aren't ignored, soonest first
    @Query("SELECT * FROM suggestions WHERE trip != '' AND status != 'ignored' ORDER BY start")
    fun watchTripPlans(): Flow<List<Suggestion>>

    // When the next open follow-up is due (null when none is booked)
    @Query("SELECT MIN(nextNudge) FROM followups WHERE status = 'open' AND nextNudge > 0")
    suspend fun soonestNudge(): Long?

    // Watch the notes, newest first
    @Query("SELECT * FROM followups WHERE kind = 'note' AND status = 'open' ORDER BY createdAt DESC")
    fun watchNotes(): Flow<List<FollowUp>>

    // Watch the reminders that go off (or went off) between two times, for Today: open ones and one-offs already done
    @Query("SELECT * FROM followups WHERE kind = 'reminder' AND status IN ('open', 'done') AND nextNudge >= :from " +
        "AND nextNudge < :until ORDER BY nextNudge")
    fun watchRemindersBetween(from: Long, until: Long): Flow<List<FollowUp>>

    // Watch the open reminders, the next to go off first
    @Query("SELECT * FROM followups WHERE kind = 'reminder' AND status = 'open' ORDER BY nextNudge")
    fun watchReminders(): Flow<List<FollowUp>>

    // Mark a follow-up done, dropped or open again
    @Query("UPDATE followups SET status = :status WHERE id = :id")
    suspend fun setFollowUpStatus(id: Long, status: String)
}
