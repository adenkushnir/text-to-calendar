// Datebook's on-phone database, created once and shared by the whole app
package io.github.adenkushnir.datebook.data
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// The database holding seen texts, suggestions, the retry queue and failure counts; until version 1.0.0 is
// released, a new version starts from an empty database instead of converting the old one
@Database(entities = [SeenText::class, Suggestion::class, RetryText::class, FailureCount::class], version = 2,
    exportSchema = false)
abstract class DatebookDatabase : RoomDatabase() {
    // The queries for all four tables
    abstract fun dao(): DatebookDao

    // Opens the one shared database
    companion object {
        @Volatile private var instance: DatebookDatabase? = null

        // Return the shared database, opening it the first time
        fun get(context: Context): DatebookDatabase {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                instance ?: Room.databaseBuilder(appContext, DatebookDatabase::class.java, "datebook.db")
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
