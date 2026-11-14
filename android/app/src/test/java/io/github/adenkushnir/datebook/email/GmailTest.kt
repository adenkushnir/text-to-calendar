// Tests for reading Gmail: an email's parts and text, sender names, the search, emails as texts, what's remembered,
// confirmation codes in the event notes, and the Gmail settings page
package io.github.adenkushnir.datebook.email
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.IncomingText
import io.github.adenkushnir.datebook.pipeline.parseResponse
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.prompts.notesWithTrackLink
import io.github.adenkushnir.datebook.settings.GmailPage
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks Gmail from Google's reply to the settings page
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GmailTest {
    @get:Rule
    val compose = createComposeRule()
    private val newYork = ZoneId.of("America/New_York")
    private val arrived = LocalDateTime.of(2037, 11, 10, 9, 30).atZone(newYork).toInstant().toEpochMilli()

    // Text encoded the way Gmail sends email parts
    private fun encoded(text: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    }

    // A Gmail "full" reply with the given From line, subject and parts
    private fun reply(from: String, subject: String, payload: String): String {
        return """{"id": "18c1", "internalDate": "$arrived", "payload": {"headers": [
            {"name": "From", "value": "$from"}, {"name": "Subject", "value": "$subject"}], $payload}}"""
    }

    // An email with plain-text and HTML parts uses the plain text; its sender's name and subject are read
    @Test
    fun readsPlainText() {
        val plain = encoded("Your table at Luigi's is confirmed for Fri Nov 13 at 7:00 PM.\nConfirmation: 4KX9")
        val email = parseEmail(reply("OpenTable <member_services@opentable.com>", "Reservation confirmed",
            """"mimeType": "multipart/alternative", "parts": [
                {"mimeType": "text/plain", "body": {"data": "$plain"}},
                {"mimeType": "text/html", "body": {"data": "${encoded("<p>ignored</p>")}"}}]"""))!!
        assertEquals("18c1", email.id)
        assertEquals("Reservation confirmed", email.subject)
        assertTrue(email.body.startsWith("Your table at Luigi's"))
        assertEquals("OpenTable", senderName(email.from))
        val incoming = incomingFrom(email, newYork)
        assertEquals("OpenTable", incoming.sender)
        assertEquals(GMAIL_APP, incoming.app)
        assertTrue(incoming.exactTime)
        assertEquals(LocalDateTime.of(2037, 11, 10, 9, 30), incoming.receivedAt)
        assertTrue(incoming.text.startsWith("Subject: Reservation confirmed\n\nYour table"))
        assertEquals("Reservation confirmed", promptQuote(incoming))
        assertEquals("dinner fri", promptQuote(IncomingText("Sam", "dinner fri", incoming.receivedAt, "sms")))
    }

    // An HTML-only email becomes plain text: no styles or tags, line breaks kept, entities decoded
    @Test
    fun readsHtml() {
        val html = "<html><head><style>p { color: red }</style></head><body><p>Pickup&nbsp;ready</p>" +
            "<p>Sat &amp; Sun, 10&#58;00</p></body></html>"
        val email = parseEmail(reply("CVS <noreply@cvs.com>", "Order ready",
            """"mimeType": "text/html", "body": {"data": "${encoded(html)}"}"""))!!
        assertEquals("Pickup ready\nSat & Sun, 10:00", email.body)
    }

    // Senders' names from different From lines; the search skips Promotions and Social; ids come from a list reply
    @Test
    fun namesSearchAndIds() {
        assertEquals("Sam K", senderName("\"Sam K\" <sam@example.com>"))
        assertEquals("only@example.com", senderName("<only@example.com>"))
        assertEquals("plain@example.com", senderName("plain@example.com"))
        assertEquals("in:inbox -category:promotions -category:social after:1700000000", searchQuery(1_700_000_000))
        assertEquals(listOf("a1", "b2"), parseIds("""{"messages": [{"id": "a1"}, {"id": "b2"}]}"""))
        assertEquals(emptyList<String>(), parseIds("""{"resultSizeEstimate": 0}"""))
    }

    // Signing in starts reading from that moment; handled emails are remembered by id; signing out forgets it all
    @Test
    fun settingsRemember() {
        val settings = GmailSettings(ApplicationProvider.getApplicationContext())
        assertFalse(settings.signedIn())
        settings.signIn(1_700_000_000)
        assertTrue(settings.signedIn())
        assertEquals(1_700_000_000L, settings.since())
        settings.markSeen("18c1")
        assertTrue(settings.seen("18c1"))
        assertFalse(settings.seen("18c2"))
        settings.signOut()
        assertFalse(settings.signedIn())
        assertFalse(settings.seen("18c1"))
    }

    // Claude's confirmation code reaches the plan, and the event's notes say it under who it came from
    @Test
    fun confirmationInNotes() {
        val json = """{"events": [{"title": "Dinner at Luigi's", "start": "2037-11-13T19:00", "end": "",
            "all_day": false, "location": "Luigi's", "repeat": "", "timezone": "", "change": "", "calendar_event": 0,
            "flight": "", "package_carrier": "", "tracking_number": "", "confirmation_code": " 4KX9 "}]}"""
        assertEquals("4KX9", parseResponse(false, json, "America/New_York").single().confirmation)
        val plan = Suggestion(sender = "OpenTable", app = GMAIL_APP, title = "Dinner at Luigi's",
            start = "2037-11-13T19:00", end = "", allDay = false, location = "Luigi's", repeat = "", timezone = "",
            phoneTimezone = "America/New_York", createdAt = 0, confirmation = "4KX9")
        assertEquals("OpenTable (Gmail)", fromLine(plan))
        assertEquals("OpenTable (Gmail)\nConfirmation: 4KX9", notesWithTrackLink(plan))
    }

    // Signed out, the page offers Sign in; signed in without Gmail watched, it says checks come every 15 minutes
    @Test
    fun gmailPage() {
        var signIns = 0
        compose.setContent {
            DatebookTheme {
                GmailPage(signedIn = false, address = "", lapsed = false, problem = "", gmailWatched = false,
                    onSignIn = { signIns += 1 }, onSignOut = {}, onBack = {})
            }
        }
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(1, signIns)
    }

    // Signed in, the page names the address, offers Sign out, and points to Apps to watch for faster checks
    @Test
    fun gmailPageSignedIn() {
        compose.setContent {
            DatebookTheme {
                GmailPage(signedIn = true, address = "me@example.com", lapsed = false, problem = "",
                    gmailWatched = false, onSignIn = {}, onSignOut = {}, onBack = {})
            }
        }
        compose.onNodeWithText("Signed in as me@example.com").assertExists()
        compose.onNodeWithText("Sign out").assertExists()
        compose.onNodeWithText("Turn on Gmail in Apps to watch", substring = true).assertExists()
    }
}
