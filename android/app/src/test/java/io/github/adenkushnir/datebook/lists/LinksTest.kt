// Tests for links on bills, packages and trips (from Claude, from emails, typed in, set by Deliberate) and for Due
// soon's 5-day window
package io.github.adenkushnir.datebook.lists
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.deliberate.ACT_SET_LINK
import io.github.adenkushnir.datebook.deliberate.Change
import io.github.adenkushnir.datebook.deliberate.TARGET_ITEM
import io.github.adenkushnir.datebook.deliberate.changesOf
import io.github.adenkushnir.datebook.deliberate.problemWith
import io.github.adenkushnir.datebook.email.htmlToText
import io.github.adenkushnir.datebook.pipeline.parseResponse
import io.github.adenkushnir.datebook.pipeline.webLink
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks links and the Due soon window with made-up items
class LinksTest {
    private val today = LocalDate.of(2037, 11, 16)

    // An assignment due on a day
    private fun assignment(title: String, date: String): FollowUp {
        return FollowUp(kind = KIND_ASSIGNMENT, title = title, date = date, amount = "", sender = "", app = "",
            conversation = "", status = FollowUp.OPEN, createdAt = 0, nextNudge = 0, nudges = 0)
    }

    // Claude's link is kept only when it's a web address
    @Test
    fun claudeLinks() {
        assertEquals("https://pay.example.com/bill", webLink(" https://pay.example.com/bill "))
        assertEquals("", webLink("pay online"))
        assertEquals("", webLink("https://example.com/a b"))
        val json = """{"events": [{"title": "Phone bill", "start": "2037-11-20", "kind": "due", "amount": "${'$'}45",
            "link": "https://pay.example.com/bill"}]}"""
        assertEquals("https://pay.example.com/bill", parseResponse(false, json, "America/New_York").single().link)
    }

    // An HTML email keeps its useful links as "text (address)" and drops the rest
    @Test
    fun emailLinks() {
        val html = "<p>Your bill is ready. <a href=\"https://pay.example.com/b?x=1&amp;y=2\">Pay your bill</a></p>" +
            "<p><a href=\"https://example.com/unsubscribe\">Unsubscribe</a></p>"
        val text = htmlToText(html)
        assertTrue(text.contains("Pay your bill (https://pay.example.com/b?x=1&y=2)"))
        assertTrue(text.contains("Unsubscribe"))
        assertTrue(!text.contains("example.com/unsubscribe"))
    }

    // A typed link gets https:// when it has none; an empty one removes the link
    @Test
    fun typedLinks() {
        assertEquals("https://example.com/order/1", cleanLink(" example.com/order/1 "))
        assertEquals("http://example.com", cleanLink("http://example.com"))
        assertEquals("", cleanLink("  "))
    }

    // Deliberate's set_link needs an id, a target and a web link (or nothing, to remove it)
    @Test
    fun deliberateLinks() {
        val change = changesOf(mapOf("changes" to listOf(mapOf("action" to "set_link", "id" to 4, "target" to "item",
            "link" to "https://pay.example.com")))).single()
        assertEquals(TARGET_ITEM, change.target)
        assertNull(problemWith(change))
        assertNull(problemWith(change.copy(link = "")))
        assertNotNull(problemWith(change.copy(link = "pay.example.com")))
        assertNotNull(problemWith(Change(ACT_SET_LINK, id = 4)))
    }

    // Due soon lists only what's due in the next 5 days
    @Test
    fun dueSoonWindow() {
        val list = listOf(assignment("Lab", "2037-11-18"), assignment("Essay", "2037-11-21"),
            assignment("Project", "2037-11-22"), assignment("Reading", ""))
        assertEquals(listOf("Lab", "Essay", "Reading"), dueWithin(list, today).map { it.title })
    }
}
