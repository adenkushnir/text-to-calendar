// Tests for which scene each screen shows and that every scene has a picture for every light
package io.github.adenkushnir.datebook.scenery
import io.github.adenkushnir.datebook.ROUTE_HOME
import io.github.adenkushnir.datebook.ROUTE_SETTINGS
import io.github.adenkushnir.datebook.ROUTE_SETTINGS_APPS
import io.github.adenkushnir.datebook.ROUTE_SETTINGS_PRIVACY
import io.github.adenkushnir.datebook.ROUTE_SETUP
import io.github.adenkushnir.datebook.ROUTE_SUGGESTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Checks the screen-to-scene choices from DESIGN.md and the 36 pictures
class ScenesTest {
    // Each screen gets its scene, with the Settings menu and its pages told apart
    @Test
    fun scenePerScreen() {
        assertEquals(Scene.LAKE, sceneForRoute(ROUTE_HOME))
        assertEquals(Scene.LAKE, sceneForRoute(null))
        assertEquals(Scene.BUBBLES, sceneForRoute(ROUTE_SETUP))
        assertEquals(Scene.DEW, sceneForRoute(ROUTE_SUGGESTION))
        assertEquals(Scene.RIBBONS, sceneForRoute(ROUTE_SETTINGS))
        assertEquals(Scene.AQUARIUM, sceneForRoute(ROUTE_SETTINGS_APPS))
        assertEquals(Scene.AQUARIUM, sceneForRoute(ROUTE_SETTINGS_PRIVACY))
    }

    // Every scene has a picture in every light, and no two share one
    @Test
    fun everyPictureExists() {
        val pictures = Scene.entries.flatMap { scene -> Light.entries.map { light -> pictureFor(scene, light) } }
        assertEquals(36, pictures.size)
        assertEquals(36, pictures.toSet().size)
        assertTrue(pictures.all { it != 0 })
    }
}
