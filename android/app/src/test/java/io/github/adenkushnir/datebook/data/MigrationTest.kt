// Tests that a phone's saved data survives the database update from version 4 to 5
package io.github.adenkushnir.datebook.data
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Builds a version 4 database like the one on the phone, then opens it with the current app
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    // The suggestions table as version 4 had it, without previousStart
    private val suggestionsV4 = "CREATE TABLE IF NOT EXISTS `suggestions` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sender` TEXT NOT NULL, `app` TEXT NOT NULL, " +
        "`conversation` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`start` TEXT NOT NULL, `end` TEXT NOT NULL, `allDay` INTEGER NOT NULL, `location` TEXT NOT NULL, " +
        "`repeat` TEXT NOT NULL, `timezone` TEXT NOT NULL, `phoneTimezone` TEXT NOT NULL, `status` TEXT NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, `clash` TEXT NOT NULL, `change` TEXT NOT NULL, " +
        "`calendarEventId` INTEGER NOT NULL, `flight` TEXT NOT NULL, `packageCarrier` TEXT NOT NULL, " +
        "`trackingNumber` TEXT NOT NULL, `placeLat` REAL, `placeLng` REAL)"

    // A saved plan keeps its details after the update, with an empty previous start
    @Test
    fun keepsSavedPlans() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE IF NOT EXISTS `seen` (`hash` TEXT NOT NULL, `seenAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`hash`))")
        old.execSQL(suggestionsV4)
        old.execSQL("CREATE TABLE IF NOT EXISTS `retry` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sender` TEXT NOT NULL, `text` TEXT NOT NULL, `receivedAt` TEXT NOT NULL, `app` TEXT NOT NULL, " +
            "`conversation` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, `nextTry` INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE IF NOT EXISTS `health` (`kind` TEXT NOT NULL, `failures` INTEGER NOT NULL, " +
            "PRIMARY KEY(`kind`))")
        old.execSQL("INSERT INTO suggestions VALUES (1, 'Sam', 'sms', '', 'Flight DL701 JFK → LAX', " +
            "'2037-11-13T14:00', '2037-11-13T20:16', 0, 'JFK', '', 'America/New_York', 'America/New_York', " +
            "'added', 0, '', '', 0, 'DL701', '', '', 40.64, -73.78)")
        old.version = 4
        old.close()
        val database = Room.databaseBuilder(context, DatebookDatabase::class.java, "migration-test.db")
            .addMigrations(MIGRATION_4_5).allowMainThreadQueries().build()
        val plan = database.dao().suggestion(1)!!
        assertEquals("Flight DL701 JFK → LAX", plan.title)
        assertEquals("DL701", plan.flight)
        assertEquals("", plan.previousStart)
        database.close()
    }
}
