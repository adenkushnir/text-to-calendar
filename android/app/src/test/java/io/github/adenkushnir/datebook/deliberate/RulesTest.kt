// Tests for Deliberate's rules: what they cover, what they change for an event, ignore rules, habits and their words
package io.github.adenkushnir.datebook.deliberate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks rule matching, effects and wording
class RulesTest {
    // A rule that's on, made at a given time (newer rules have later times)
    private fun rule(kind: String, words: String = "", value: String = "", place: String = "", sender: String = "",
        createdAt: Long = 1, isOn: Boolean = true): Rule {
        return Rule(kind = kind, words = words, place = place, sender = sender, value = value, isOn = isOn,
            createdAt = createdAt)
    }

    // A rule covers events whose title has one of its phrases, and its place when it names one
    @Test
    fun coversEvents() {
        val practice = rule(RULE_TRAVEL, words = "soccer practice|fencing practice", value = "50")
        assertTrue(coversEvent(practice, "Soccer Practice", ""))
        assertTrue(coversEvent(practice, "Fencing practice (beginners)", ""))
        assertFalse(coversEvent(practice, "Soccer game", ""))
        val atField = rule(RULE_TRAVEL, place = "riverside field", value = "20")
        assertTrue(coversEvent(atField, "Game", "Riverside Field, 1 Park Rd"))
        assertFalse(coversEvent(atField, "Game", "Hilltop Rec Center"))
    }

    // The newest matching rule sets the travel time; bring lists add up; off rules count for nothing
    @Test
    fun effectsForAnEvent() {
        val rules = listOf(
            rule(RULE_TRAVEL, words = "practice", value = "50", createdAt = 3),
            rule(RULE_TRAVEL, words = "practice", value = "30", createdAt = 2),
            rule(RULE_BRING, words = "practice", value = "cleats, water"),
            rule(RULE_BRING, words = "soccer", value = "shin guards|water"),
            rule(RULE_EARLY, words = "practice", value = "15"),
            rule(RULE_MODE, words = "practice", value = "transit"),
            rule(RULE_QUIET, words = "practice", isOn = false),
        )
        val effects = effectsFor(rules, "Soccer practice", "")
        assertEquals(50, effects.minutes)
        assertEquals(15, effects.earlyMinutes)
        assertEquals("TRANSIT", effects.mode)
        assertEquals(listOf("cleats", "water", "shin guards"), effects.bring)
        assertFalse(effects.quiet)
        assertNull(effectsFor(rules, "Dinner", "").minutes)
    }

    // Ignore rules need a sender or words, and can be limited to a kind of plan
    @Test
    fun ignoresPlans() {
        val group = rule(RULE_IGNORE, sender = "Soccer Team")
        assertTrue(ignoredByRules(listOf(group), "Jake", "Soccer Team", "Team dinner", "event"))
        assertFalse(ignoredByRules(listOf(group), "Sam", "", "Lunch", "event"))
        val netflixBills = rule(RULE_IGNORE, words = "netflix", value = "due")
        assertTrue(ignoredByRules(listOf(netflixBills), "Netflix", "", "Netflix renewal", "due"))
        assertFalse(ignoredByRules(listOf(netflixBills), "Sam", "", "Netflix night", "event"))
        assertFalse(coversPlan(rule(RULE_IGNORE), "Sam", "", "Lunch", "event"))
    }

    // Habits that are on are passed to Claude
    @Test
    fun habits() {
        val rules = listOf(rule(RULE_HABIT, value = "Dinners last 2 hours"), rule(RULE_HABIT, value = "x", isOn = false))
        assertEquals(listOf("Dinners last 2 hours"), habitsOf(rules))
    }

    // Bring lists merge without repeats
    @Test
    fun mergesBring() {
        assertEquals("cleats, \$20, water", mergeBring("cleats, \$20", listOf("Cleats", "water")))
        assertEquals("water", mergeBring("", listOf("water")))
    }

    // Rules read like the preview and the Rules list show them
    @Test
    fun wording() {
        assertEquals("Title has “soccer practice” → 50 min travel, instead of Google Maps",
            ruleText(rule(RULE_TRAVEL, words = "soccer practice", value = "50")))
        assertEquals("from “Soccer Team” → never prompt", ruleText(rule(RULE_IGNORE, sender = "Soccer Team")))
        assertEquals("Title has “exam” → remind me the evening before at 8 PM",
            ruleText(rule(RULE_REMIND, words = "exam", value = REMIND_EVENING)))
        assertEquals("Habit: Dinners last 2 hours", ruleText(rule(RULE_HABIT, value = "Dinners last 2 hours")))
        assertEquals("1 h 30 min", minutesText(90))
    }
}
