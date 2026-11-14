// Tests for the moving scenes: each scene's strengths, the lens bubbles, the fish, and when motion may run
package io.github.adenkushnir.datebook.scenery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the motion rules from DESIGN.md
class MotionTest {
    // Every scene moves somehow, only the aquarium has caustics, and no scene asks for more bubbles than fit
    @Test
    fun everySceneMoves() {
        // Each scene has at least one moving part and fits the shader's bubble slots
        for (scene in Scene.entries) {
            val effects = effectsFor(scene)
            val moving = effects.water + effects.sway + effects.flow + effects.bob + effects.glint + effects.bubbles
            assertTrue(scene.name, moving > 0f)
            assertTrue(scene.name, effects.bubbles < MAX_BUBBLES)
            assertEquals(scene.name, scene == Scene.AQUARIUM, effects.caustics > 0f)
        }
    }

    // Every scene has its own mask and every fish in the school has a picture
    @Test
    fun masksAndFishExist() {
        val masks = Scene.entries.map { maskFor(it) }
        assertEquals(6, masks.toSet().size)
        assertTrue(masks.all { it != 0 })
        assertTrue(SCHOOL.all { fishPicture(it.fish) != 0 })
    }

    // The rising bubbles stay on screen sideways and never rise above their scene's top
    @Test
    fun bubblesStayInTheirWater() {
        // Check every few seconds over ten minutes
        for (second in 0..600 step 7) {
            val bubbles = bubblesAt(Scene.AQUARIUM, second.toDouble(), null)
            assertEquals(8, bubbles.size)
            assertTrue(bubbles.all { it.x in -0.05f..1.05f && it.y >= 0.45f - 0.001f && it.y <= 1.05f })
        }
    }

    // The dew has no bubbles until a surprise brings a giant one; the aquarium's surprise is a fish, not a bubble
    @Test
    fun surpriseBubble() {
        assertEquals(0, bubblesAt(Scene.DEW, 30.0, null).size)
        val giant = bubblesAt(Scene.DEW, 30.0, SURPRISE_SECONDS / 2).single()
        assertEquals(0.3f, giant.radius, 0.001f)
        assertEquals(0.5f, giant.x, 0.01f)
        assertEquals(8, bubblesAt(Scene.AQUARIUM, 30.0, SURPRISE_SECONDS / 2).size)
    }

    // A fish starts just off one side, swims across and leaves off the other, whichever way it faces
    @Test
    fun fishCrossTheScreen() {
        val right = Swimmer("goldfish", 0.6f, 100f, 0.1, 0.0, 1, 0f)
        val left = right.copy(direction = -1)
        assertEquals(-100f, swimmerX(right, 0.0, 400f), 0.01f)
        assertEquals(200f, swimmerX(right, 5.0, 400f), 0.01f)
        assertEquals(500f, swimmerX(left, 0.0, 400f), 0.01f)
        assertEquals(200f, swimmerX(left, 5.0, 400f), 0.01f)
    }

    // Motion runs only with the switch on, no battery saver, and Android's animations on
    @Test
    fun whenMotionRuns() {
        assertTrue(motionAllowed(true, false, 1f))
        assertTrue(motionAllowed(true, false, 0.5f))
        assertFalse(motionAllowed(false, false, 1f))
        assertFalse(motionAllowed(true, true, 1f))
        assertFalse(motionAllowed(true, false, 0f))
    }
}
