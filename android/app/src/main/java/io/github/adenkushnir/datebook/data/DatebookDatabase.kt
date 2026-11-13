// Datebook's on-phone database, created once and shared by the whole app
package io.github.adenkushnir.datebook.data
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Version 5 adds the time a waiting plan had before it changed in the chat (the old one is kept)
val MIGRATION_4_5 = object : Migration(4, 5) {
    // Add the column, empty for every saved suggestion
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestions ADD COLUMN previousStart TEXT NOT NULL DEFAULT ''")
    }
}

// Version 6 marks the plans that came from texts you sent (automatic mode)
val MIGRATION_5_6 = object : Migration(5, 6) {
    // Add the column, false for every saved suggestion
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestions ADD COLUMN fromMe INTEGER NOT NULL DEFAULT 0")
    }
}

// Version 7 links an offline guess to the text waiting for Claude to check it
val MIGRATION_6_7 = object : Migration(6, 7) {
    // Add the column, 0 (not a guess) for every saved suggestion
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestions ADD COLUMN retryId INTEGER NOT NULL DEFAULT 0")
    }
}

// The database holding seen texts, suggestions, the retry queue and failure counts; from version 5 on, saved data is
// converted when the database changes (older versions still start empty)
@Database(entities = [SeenText::class, Suggestion::class, RetryText::class, FailureCount::class], version = 7,
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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
