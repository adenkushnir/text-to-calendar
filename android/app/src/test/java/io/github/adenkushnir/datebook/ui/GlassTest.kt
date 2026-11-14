// Tests for the glass look: the tint follows the light, and every icon's shapes can be read
package io.github.adenkushnir.datebook.ui
import androidx.compose.ui.graphics.Color
import io.github.adenkushnir.datebook.scenery.Light
import io.github.adenkushnir.datebook.scenery.SceneLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the glass tint between lights and that all the icons build
class GlassTest {
    // A light with no fade gets that light's glass colour (navy at night)
    @Test
    fun tintForOneLight() {
        val night = SceneLight(Light.NIGHT, Light.NIGHT, 0f, 1f, 0f)
        assertEquals(Color(12, 24, 54), glassTint(night))
    }

    // Halfway through a fade the glass sits between the two lights' colours (Compose blends in a perceptual colour
    // space, so it isn't exactly the plain average): redder than midday's, less blue than midday's
    @Test
    fun tintHalfwayThroughAFade() {
        val fading = SceneLight(Light.MIDDAY, Light.GOLDEN, 0.5f, 0f, 0f)
        val tint = glassTint(fading)
        assertTrue(tint.red > 16 / 255f && tint.red < 62 / 255f)
        assertTrue(tint.blue < 72 / 255f && tint.blue > 28 / 255f)
    }

    // Every icon's path strings parse into drawable shapes (a typo would otherwise only show on the phone)
    @Test
    fun everyIconBuilds() {
        val icons = listOf(DatebookIcons.Gear, DatebookIcons.Back, DatebookIcons.Close, DatebookIcons.Swap,
            DatebookIcons.Plane, DatebookIcons.Train, DatebookIcons.Pin, DatebookIcons.Bell, DatebookIcons.Key,
            DatebookIcons.Bolt, DatebookIcons.Calendar, DatebookIcons.Check, DatebookIcons.Apps, DatebookIcons.Sparkle,
            DatebookIcons.Lock, DatebookIcons.Info, DatebookIcons.Auto, DatebookIcons.Car, DatebookIcons.Bus,
            DatebookIcons.Walk, DatebookIcons.Bike, DatebookIcons.Warn)

        // Each icon has at least one shape, on the 24 by 24 grid
        for (icon in icons) {
            assertTrue("${icon.name} has no shapes", icon.root.size > 0)
            assertEquals(24f, icon.viewportWidth, 0.001f)
        }
    }
}
