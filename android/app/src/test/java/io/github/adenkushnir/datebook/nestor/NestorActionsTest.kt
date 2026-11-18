// Tests for the actions Nestor sends (from the wall screen or Hey John), run against the real app pieces
package io.github.adenkushnir.datebook.nestor
import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_NOTE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import io.github.adenkushnir.datebook.data.Suggestion
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Carries out each action Nestor can send and checks what changed
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NestorActionsTest {
    // A follow-up waiting to be done
    private fun item(kind: String, title: String, date: String, repeat: String = "", nextNudge: Long = 0): FollowUp {
        return FollowUp(kind = kind, title = title, date = date, amount = "", sender = "you", app = "",
            conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = nextNudge, nudges = 0,
            repeat = repeat)
    }

    // add shows the prompt again, ignore ignores, done finishes bills and moves repeating reminders on, and new_plan
    // is read like something typed (kept as a note without a Claude key)
    @Test
    fun actions() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManager.initialize(context, Configuration.Builder().build())  // reminders are booked through WorkManager
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val dao = AppGraph.get(context).dao
        val plan = Suggestion(sender = "Sam", app = "sms", title = "Lunch", start = "2037-11-21T12:00", end = "",
            allDay = false, location = "", repeat = "", timezone = "", phoneTimezone = "America/New_York",
            createdAt = 0)
        val lunch = dao.saveSuggestion(plan)
        val movie = dao.saveSuggestion(plan.copy(title = "Movie"))

        perform(context, "add", lunch, "")
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.any { it.extras.getCharSequence("android.title") == "Lunch" })

        perform(context, "ignore", movie, "")
        assertEquals(Suggestion.STATUS_IGNORED, dao.suggestion(movie)!!.status)

        val rent = dao.saveFollowUp(item(KIND_DUE, "Rent", "2037-12-01", repeat = "FREQ=MONTHLY"))
        perform(context, "done", rent, "")
        assertEquals(FollowUp.DONE, dao.followUp(rent)!!.status)
        assertTrue(dao.openFollowUps(KIND_DUE).any { it.title == "Rent" && it.date == "2038-01-01" })

        val zone = ZoneId.systemDefault()
        val monday = LocalDateTime.of(2037, 11, 16, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val trash = dao.saveFollowUp(item(KIND_REMINDER, "Take out the trash", "2037-11-16",
            repeat = "FREQ=WEEKLY;BYDAY=MO", nextNudge = monday))
        perform(context, "done", trash, "")
        assertEquals(FollowUp.OPEN, dao.followUp(trash)!!.status)
        assertTrue(dao.followUp(trash)!!.nextNudge > System.currentTimeMillis())

        perform(context, "new_plan", 0, "Gate code 4412")
        assertTrue(dao.allOpenFollowUps().any { it.kind == KIND_NOTE && it.body == "Gate code 4412" })
    }
}
