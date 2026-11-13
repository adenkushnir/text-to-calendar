// The offline backup: Google's ML Kit finds the dates and times in a text on the phone, without internet, so when
// Claude can't read a text the plan still waits on Home as a guess ("Plan with Sam", Fri 7:00 PM) until it can
package io.github.adenkushnir.datebook.offline
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import io.github.adenkushnir.datebook.pipeline.IncomingText
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.concurrent.TimeUnit
private const val TAG = "Datebook"
private const val FIND_SECONDS = 10L
private val LOCAL_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

// A date or time found on the phone: YYYY-MM-DDTHH:MM, or YYYY-MM-DD for a whole day
data class GuessedTime(val start: String, val allDay: Boolean)

// The title of a guess before Claude has read the text: "Plan with Sam", or "Plan in Soccer Team" for a group chat
fun guessTitle(incoming: IncomingText): String {
    return if (incoming.group.isNotBlank()) "Plan in ${incoming.group}" else "Plan with ${incoming.sender}"
}

// Turn a time ML Kit found into a guess in the phone's zone: an hour or minute gives a time, a day gives an all-day
// plan, and anything vaguer (a week, a month) gives none
fun guessedTime(timestampMillis: Long, granularity: Int, zone: ZoneId): GuessedTime? {
    val local = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDateTime()

    // Pick by how exact the time is
    return when {
        granularity >= DateTimeEntity.GRANULARITY_HOUR -> GuessedTime(local.format(LOCAL_MINUTES), false)
        granularity == DateTimeEntity.GRANULARITY_DAY -> GuessedTime(local.toLocalDate().toString(), true)
        else -> null
    }
}

// ML Kit's date finder for English texts, which works on the phone once its model (about 5 MB) is downloaded
class OfflineDates {
    private val extractor by lazy {
        EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build())
    }

    // Download the model if it isn't on the phone yet, over any network; guesses work offline after that
    fun prepare() {
        // Ask for the download, logging how it went
        try {
            extractor.downloadModelIfNeeded()
                .addOnSuccessListener { Log.d(TAG, "offline dates: model ready") }
                .addOnFailureListener { Log.d(TAG, "offline dates: model not downloaded yet (${it.message})") }
        } catch (error: Exception) {  // ML Kit couldn't start
            Log.d(TAG, "offline dates: unavailable (${error.message})")
        }
    }

    // The dates and times in a text, worked out from when it arrived; empty when the model isn't downloaded yet or
    // nothing is found (call it off the main thread)
    fun find(text: String, receivedAt: LocalDateTime, zone: ZoneId): List<GuessedTime> {
        // Find the times, or none when ML Kit can't (no model yet, or an error)
        return try {
            val params = EntityExtractionParams.Builder(text)
                .setReferenceTime(receivedAt.atZone(zone).toInstant().toEpochMilli())
                .setReferenceTimeZone(TimeZone.getTimeZone(zone))
                .setEntityTypesFilter(setOf(Entity.TYPE_DATE_TIME))
                .build()
            val found = Tasks.await(extractor.annotate(params), FIND_SECONDS, TimeUnit.SECONDS)
            found.flatMap { it.entities }.mapNotNull { it.asDateTimeEntity() }
                .mapNotNull { guessedTime(it.timestampMillis, it.dateTimeGranularity, zone) }.distinct()
        } catch (error: Exception) {  // the model isn't downloaded, or ML Kit failed or took too long
            Log.d(TAG, "offline dates: none (${error.message})")
            emptyList()
        }
    }
}
