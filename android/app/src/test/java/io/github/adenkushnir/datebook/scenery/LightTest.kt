// Tests for the light of the day: which pictures show, how far the fades have gone, and late-night dimming
package io.github.adenkushnir.datebook.scenery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Walks through a November day (sunrise 6:43 AM, sunset 4:45 PM) and checks each part of it
class LightTest {
    private val sun = SunTimes(rise = 403.0, set = 1005.0)

    // Check the light at a minute is between two lights, with the fade within 0.01 of a share
    private fun assertLight(minute: Double, from: Light, to: Light, mix: Double) {
        val light = lightAt(minute, sun)
        assertEquals(from, light.from)
        assertEquals(to, light.to)
        assertEquals(mix.toFloat(), light.mix, 0.01f)
    }

    // Before dawn and late in the evening it's night, with nothing fading
    @Test
    fun nightAtBothEnds() {
        assertLight(200.0, Light.NIGHT, Light.NIGHT, 0.0)
        assertLight(1200.0, Light.NIGHT, Light.NIGHT, 0.0)
    }

    // At dawn the blue hour fades into morning; halfway through the stretch the eased fade is exactly half
    @Test
    fun dawnFadesIntoMorning() {
        assertLight(403.0, Light.BLUE_HOUR, Light.MORNING, 0.5)
    }

    // Mid-morning is fully morning, and on a short November day the fade into golden hour is halfway by 2:07 PM
    @Test
    fun morningThenGolden() {
        assertLight(450.0, Light.MORNING, Light.MORNING, 0.0)
        assertLight(847.5, Light.MIDDAY, Light.GOLDEN, 0.5)
    }

    // Just after sunset the sunset picture is fully showing, then it fades into the blue hour
    @Test
    fun sunsetThenBlueHour() {
        assertLight(1010.0, Light.SUNSET, Light.SUNSET, 0.0)
        assertLight(1032.5, Light.SUNSET, Light.BLUE_HOUR, 0.5)
    }

    // The eased fade starts and ends gently: a tenth of the way in, it has moved less than a tenth
    @Test
    fun fadesAreEased() {
        assertTrue(eased(10.0, 0.0, 100.0) < 0.1)
        assertEquals(0.5, eased(50.0, 0.0, 100.0), 0.001)
        assertEquals(1.0, eased(150.0, 0.0, 100.0), 0.001)
    }

    // Late night eases in from 11 PM, is full from 11:30 PM to 4:30 AM, and is gone by 5 AM
    @Test
    fun lateNightDimming() {
        assertEquals(0f, lateNight(1379.0), 0.001f)
        assertEquals(0.5f, lateNight(1395.0), 0.001f)
        assertEquals(1f, lateNight(60.0), 0.001f)
        assertEquals(0f, lateNight(300.0), 0.001f)
        assertEquals(0f, lateNight(720.0), 0.001f)
    }
}
