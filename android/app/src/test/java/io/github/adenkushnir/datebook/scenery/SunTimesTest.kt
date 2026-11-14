// Tests for working out sunrise and sunset on the phone
package io.github.adenkushnir.datebook.scenery
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the sun times against known New York ones, in winter and summer time, and the polar cases
class SunTimesTest {
    private val newYork = ZoneId.of("America/New_York")

    // A minute count as a clock time, for readable failure messages
    private fun clock(minute: Double): String {
        return "%d:%02d".format((minute / 60).toInt(), (minute % 60).toInt())
    }

    // Check a worked-out time is within 4 minutes of the published one
    private fun assertNear(expected: Double, actual: Double) {
        assertTrue("expected about ${clock(expected)}, got ${clock(actual)}", kotlin.math.abs(expected - actual) <= 4)
    }

    // New York on Nov 13, 2026 (standard time): sunrise about 6:43 AM, sunset about 4:43 PM
    @Test
    fun newYorkInNovember() {
        val sun = sunTimes(LocalDate.of(2026, 11, 13), 40.71, -74.01, newYork)
        assertNear(6 * 60 + 43.0, sun.rise)
        assertNear(16 * 60 + 43.0, sun.set)
    }

    // New York on June 21, 2026 (summer time): sunrise about 5:25 AM, sunset about 8:31 PM
    @Test
    fun newYorkInJune() {
        val sun = sunTimes(LocalDate.of(2026, 6, 21), 40.71, -74.01, newYork)
        assertNear(5 * 60 + 25.0, sun.rise)
        assertNear(20 * 60 + 31.0, sun.set)
    }

    // Far north in midwinter the sun never rises, so sunrise and sunset meet
    @Test
    fun polarNight() {
        val sun = sunTimes(LocalDate.of(2026, 12, 21), 69.65, 18.96, ZoneId.of("Europe/Oslo"))
        assertEquals(sun.rise, sun.set, 0.001)
    }

    // Far north in midsummer the sun never sets, so the day spans the whole 24 hours
    @Test
    fun polarDay() {
        val sun = sunTimes(LocalDate.of(2026, 6, 21), 69.65, 18.96, ZoneId.of("Europe/Oslo"))
        assertEquals(1440.0, sun.set - sun.rise, 0.001)
    }

    // The time zone guess puts New York's longitude 75 degrees west, at a middle latitude
    @Test
    fun zoneGuessForNewYork() {
        val guess = zoneGuess(newYork)
        assertEquals(ZONE_GUESS_LATITUDE, guess.first, 0.001)
        assertEquals(-75.0, guess.second, 0.001)
    }
}
