// Datebook's on-phone database, created once and shared by the whole app
package io.github.adenkushnir.datebook.data
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.adenkushnir.datebook.deliberate.DeliberateChange
import io.github.adenkushnir.datebook.deliberate.DeliberateChat
import io.github.adenkushnir.datebook.deliberate.DeliberateDao
import io.github.adenkushnir.datebook.deliberate.DeliberateLine
import io.github.adenkushnir.datebook.deliberate.Rule

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

// Version 8 keeps a plan's confirmation code (from an email or text), for the event's notes
val MIGRATION_7_8 = object : Migration(7, 8) {
    // Add the column, empty for every saved suggestion
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestions ADD COLUMN confirmation TEXT NOT NULL DEFAULT ''")
    }
}

// Version 9 adds the follow-ups: loose plans to pin down, and bills and due dates to be reminded about
val MIGRATION_8_9 = object : Migration(8, 9) {
    // Create the new table, empty
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `followups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `date` TEXT NOT NULL, `amount` TEXT NOT NULL, " +
            "`sender` TEXT NOT NULL, `app` TEXT NOT NULL, `conversation` TEXT NOT NULL, `status` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `nextNudge` INTEGER NOT NULL, `nudges` INTEGER NOT NULL)")
    }
}

// Version 11 keeps what a plan's texts say to bring, and whether it's outdoors
val MIGRATION_10_11 = object : Migration(10, 11) {
    // Add the two columns, empty for older plans
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestions ADD COLUMN bring TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE suggestions ADD COLUMN outdoor INTEGER NOT NULL DEFAULT 0")
    }
}

// Version 10 gives follow-ups a time, a repeat rule and a note's text, and plans the trip they belong to
val MIGRATION_9_10 = object : Migration(9, 10) {
    // Add the columns, empty for everything saved
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE followups ADD COLUMN time TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE followups ADD COLUMN `repeat` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE followups ADD COLUMN body TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE suggestions ADD COLUMN trip TEXT NOT NULL DEFAULT ''")
    }
}

// Version 12 keeps the message a plan came from (for Deliberate), and adds Deliberate's chats, changes and rules
val MIGRATION_11_12 = object : Migration(11, 12) {
    // Add the message columns, empty for everything saved, and create the new tables, empty
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestions ADD COLUMN message TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE followups ADD COLUMN message TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS `deliberate_chats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `turns` TEXT NOT NULL, " +
            "`pending` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `deliberate_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`chatId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `data` TEXT NOT NULL, " +
            "`state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `deliberate_changes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`chatId` INTEGER NOT NULL, `summary` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `undo` TEXT NOT NULL, " +
            "`reverted` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`kind` TEXT NOT NULL, `words` TEXT NOT NULL, `place` TEXT NOT NULL, `sender` TEXT NOT NULL, " +
            "`value` TEXT NOT NULL, `isOn` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
    }
}

// Version 13 remembers which Canvas assignment or class event a follow-up or plan is, by its id in the feed
val MIGRATION_12_13 = object : Migration(12, 13) {
    // Add the column, empty for everything saved
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestions ADD COLUMN sourceId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE followups ADD COLUMN sourceId TEXT NOT NULL DEFAULT ''")
    }
}

// The database holding seen texts, suggestions, the retry queue and failure counts; from version 5 on, saved data is
// converted when the database changes (older versions still start empty)
@Database(entities = [SeenText::class, Suggestion::class, RetryText::class, FailureCount::class, FollowUp::class,
    DeliberateChat::class, DeliberateLine::class, DeliberateChange::class, Rule::class],
    version = 13,
    exportSchema = false)
abstract class DatebookDatabase : RoomDatabase() {
    // The queries for all four tables
    abstract fun dao(): DatebookDao

    // The queries for Deliberate's chats, changes and rules
    abstract fun deliberateDao(): DeliberateDao

    // Opens the one shared database
    companion object {
        @Volatile private var instance: DatebookDatabase? = null

        // Return the shared database, opening it the first time
        fun get(context: Context): DatebookDatabase {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                instance ?: Room.databaseBuilder(appContext, DatebookDatabase::class.java, "datebook.db")
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                        MIGRATION_12_13)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
