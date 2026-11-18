// Tests for reading Deliberate's proposed changes and saving its conversations
package io.github.adenkushnir.datebook.deliberate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// Checks change parsing and checking, plan times, pauses and conversation JSON
class ChangesTest {
    // A propose_changes input is read into changes, with numbers and tidied fields
    @Test
    fun readsChanges() {
        val input = mapOf("title" to "New rule", "changes" to listOf(
            mapOf("action" to "add_rule", "rule_kind" to "travel_minutes", "words" to "Soccer Practice", "value" to "50"),
            mapOf("action" to "move_event", "id" to 42, "begin" to 1794600000000L, "date" to "2026-11-20",
                "time" to "18:30"),
            "not a change",
        ))
        val changes = changesOf(input)
        assertEquals(2, changes.size)
        assertEquals("soccer practice", changes[0].words)
        assertEquals(42L, changes[1].id)
        assertEquals(1794600000000L, changes[1].begin)
    }

    // Each action says what it's missing
    @Test
    fun checksChanges() {
        assertNull(problemWith(Change(ACT_NEW_PLAN, title = "Lunch", date = "2026-11-21", time = "12:00")))
        assertNotNull(problemWith(Change(ACT_NEW_PLAN, title = "Lunch")))
        assertNotNull(problemWith(Change(ACT_MOVE_EVENT, id = 3, date = "2026-11-21")))
        assertNotNull(problemWith(Change(ACT_ADD_RULE, ruleKind = RULE_TRAVEL, value = "50")))
        assertNull(problemWith(Change(ACT_ADD_RULE, ruleKind = RULE_TRAVEL, words = "practice", value = "50")))
        assertNull(problemWith(Change(ACT_ADD_RULE, ruleKind = RULE_IGNORE, sender = "Soccer Team")))
        assertNotNull(problemWith(Change(ACT_PAUSE, pause = PAUSE_LEAVE_BY, until = "someday")))
        assertNotNull(problemWith(Change("send_money")))
    }

    // Plan times are a date and time, or the date alone for all day
    @Test
    fun planTimes() {
        assertEquals("2026-11-21T12:00", planTime("2026-11-21", "12:00"))
        assertEquals("2026-11-21", planTime("2026-11-21", ""))
        assertEquals("", planTime("Saturday", "12:00"))
    }

    // A pause ends at a time or at the start of a day
    @Test
    fun pauseEnds() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(untilMillis("2026-11-23T06:00", zone), untilMillis("2026-11-23", zone)!! + 6 * 3_600_000L)
        assertNull(untilMillis("Monday", zone))
    }

    // A conversation survives being saved and read back, and tool results go first in the user's turn
    @Test
    fun savesConversations() {
        val turns = listOf(
            Turn(ROLE_USER, listOf(textBlock("Every practice takes 50 minutes"))),
            Turn(ROLE_ASSISTANT, listOf(Block(BLOCK_TOOL_USE, id = "call1", name = TOOL_ASK,
                input = toJson(mapOf("question" to "Which practice?", "choices" to listOf("Soccer", "Fencing")))))),
        )
        val back = turnsFromJson(turnsToJson(turns))
        assertEquals(turns, back)
        assertEquals("Which practice?", inputMap(back[1].blocks[0])["question"])
        val answered = withUserBlocks(back, listOf(textBlock("note"), resultBlock("call1", "Soccer")))
        assertEquals(BLOCK_TOOL_RESULT, answered.last().blocks.first().type)
        val joined = withUserBlocks(answered, listOf(textBlock("more")))
        assertEquals(3, joined.size)
        assertEquals(3, joined.last().blocks.size)
        assertEquals(emptyList<Turn>(), turnsFromJson("not json"))
    }
}
