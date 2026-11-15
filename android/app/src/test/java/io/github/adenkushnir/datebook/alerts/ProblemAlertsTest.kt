// Tests for the quiet problem alerts: which problems are showing, where each one's Fix goes, notification access
// alerting once, and a new key starting the Claude problems over
package io.github.adenkushnir.datebook.alerts
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.ROUTE_SETTINGS_CLAUDE
import io.github.adenkushnir.datebook.ROUTE_SETTINGS_NOTIFICATIONS
import io.github.adenkushnir.datebook.data.DatebookDao
import io.github.adenkushnir.datebook.data.DatebookDatabase
import io.github.adenkushnir.datebook.pipeline.Health
import io.github.adenkushnir.datebook.pipeline.PROBLEM_API
import io.github.adenkushnir.datebook.pipeline.PROBLEM_API_KEY
import io.github.adenkushnir.datebook.pipeline.PROBLEM_NOTIFICATIONS
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Checks the alerts against an in-memory database, with notification access off (as Robolectric starts)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProblemAlertsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: DatebookDatabase
    private lateinit var dao: DatebookDao

    // Open a fresh in-memory database before each test
    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, DatebookDatabase::class.java).allowMainThreadQueries().build()
        dao = database.dao()
    }

    // Close the database after each test
    @After
    fun closeDatabase() {
        database.close()
    }

    // The notifications Datebook is showing right now
    private fun shown(): Int {
        return shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size
    }

    // A key problem shows at once, Claude failing only after 3; each Fix opens the right page
    @Test
    fun showingProblems() = runTest {
        val health = Health(dao)
        health.failed(PROBLEM_API_KEY)
        health.failed(PROBLEM_API)
        health.failed(PROBLEM_API)
        assertEquals(listOf(PROBLEM_API_KEY), activeProblems(dao))
        health.failed(PROBLEM_API)
        assertEquals(listOf(PROBLEM_API_KEY, PROBLEM_API), activeProblems(dao))
        assertEquals(ROUTE_SETTINGS_CLAUDE, alertRoute(PROBLEM_API_KEY))
        assertEquals(ROUTE_SETTINGS_NOTIFICATIONS, alertRoute(PROBLEM_NOTIFICATIONS))
    }

    // With notification access off, the first check alerts and later checks don't alert again
    @Test
    fun accessOffAlertsOnce() = runTest {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        checkNotificationAccess(context, dao)
        assertEquals(listOf(PROBLEM_NOTIFICATIONS), activeProblems(dao))
        assertEquals(1, shown())
        clearProblemAlert(context, PROBLEM_NOTIFICATIONS)
        checkNotificationAccess(context, dao)
        assertEquals(0, shown())
    }

    // Saving a new key forgets both Claude problems
    @Test
    fun newKeyResets() = runTest {
        val health = Health(dao)
        health.failed(PROBLEM_API_KEY)
        repeat(3) { health.failed(PROBLEM_API) }
        resetClaudeProblems(context, dao)
        assertEquals(emptyList<String>(), activeProblems(dao))
    }
}
