// Datebook's six photo scenes, the picture for each scene in each light, and which screen shows which scene
package io.github.adenkushnir.datebook.scenery
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.ROUTE_HOME
import io.github.adenkushnir.datebook.ROUTE_SETTINGS
import io.github.adenkushnir.datebook.ROUTE_SETUP

// The six scenes (see design/DESIGN.md)
enum class Scene {
    LAKE,
    AQUARIUM,
    RIBBONS,
    DEW,
    BUBBLES,
    BEACH,
}
private val PICTURES = mapOf(
    Pair(Scene.LAKE, Light.NIGHT) to R.drawable.scene_lake_night,
    Pair(Scene.LAKE, Light.BLUE_HOUR) to R.drawable.scene_lake_bluehour,
    Pair(Scene.LAKE, Light.MORNING) to R.drawable.scene_lake_morning,
    Pair(Scene.LAKE, Light.MIDDAY) to R.drawable.scene_lake_midday,
    Pair(Scene.LAKE, Light.GOLDEN) to R.drawable.scene_lake_golden,
    Pair(Scene.LAKE, Light.SUNSET) to R.drawable.scene_lake_sunset,
    Pair(Scene.AQUARIUM, Light.NIGHT) to R.drawable.scene_aquarium_night,
    Pair(Scene.AQUARIUM, Light.BLUE_HOUR) to R.drawable.scene_aquarium_bluehour,
    Pair(Scene.AQUARIUM, Light.MORNING) to R.drawable.scene_aquarium_morning,
    Pair(Scene.AQUARIUM, Light.MIDDAY) to R.drawable.scene_aquarium_midday,
    Pair(Scene.AQUARIUM, Light.GOLDEN) to R.drawable.scene_aquarium_golden,
    Pair(Scene.AQUARIUM, Light.SUNSET) to R.drawable.scene_aquarium_sunset,
    Pair(Scene.RIBBONS, Light.NIGHT) to R.drawable.scene_ribbons_night,
    Pair(Scene.RIBBONS, Light.BLUE_HOUR) to R.drawable.scene_ribbons_bluehour,
    Pair(Scene.RIBBONS, Light.MORNING) to R.drawable.scene_ribbons_morning,
    Pair(Scene.RIBBONS, Light.MIDDAY) to R.drawable.scene_ribbons_midday,
    Pair(Scene.RIBBONS, Light.GOLDEN) to R.drawable.scene_ribbons_golden,
    Pair(Scene.RIBBONS, Light.SUNSET) to R.drawable.scene_ribbons_sunset,
    Pair(Scene.DEW, Light.NIGHT) to R.drawable.scene_dew_night,
    Pair(Scene.DEW, Light.BLUE_HOUR) to R.drawable.scene_dew_bluehour,
    Pair(Scene.DEW, Light.MORNING) to R.drawable.scene_dew_morning,
    Pair(Scene.DEW, Light.MIDDAY) to R.drawable.scene_dew_midday,
    Pair(Scene.DEW, Light.GOLDEN) to R.drawable.scene_dew_golden,
    Pair(Scene.DEW, Light.SUNSET) to R.drawable.scene_dew_sunset,
    Pair(Scene.BUBBLES, Light.NIGHT) to R.drawable.scene_bubbles_night,
    Pair(Scene.BUBBLES, Light.BLUE_HOUR) to R.drawable.scene_bubbles_bluehour,
    Pair(Scene.BUBBLES, Light.MORNING) to R.drawable.scene_bubbles_morning,
    Pair(Scene.BUBBLES, Light.MIDDAY) to R.drawable.scene_bubbles_midday,
    Pair(Scene.BUBBLES, Light.GOLDEN) to R.drawable.scene_bubbles_golden,
    Pair(Scene.BUBBLES, Light.SUNSET) to R.drawable.scene_bubbles_sunset,
    Pair(Scene.BEACH, Light.NIGHT) to R.drawable.scene_beach_night,
    Pair(Scene.BEACH, Light.BLUE_HOUR) to R.drawable.scene_beach_bluehour,
    Pair(Scene.BEACH, Light.MORNING) to R.drawable.scene_beach_morning,
    Pair(Scene.BEACH, Light.MIDDAY) to R.drawable.scene_beach_midday,
    Pair(Scene.BEACH, Light.GOLDEN) to R.drawable.scene_beach_golden,
    Pair(Scene.BEACH, Light.SUNSET) to R.drawable.scene_beach_sunset,
)

// The picture for a scene in a light, as a drawable (scene_lake_bluehour and so on, made by design/make_app_scenes.py)
fun pictureFor(scene: Scene, light: Light): Int {
    return PICTURES.getValue(Pair(scene, light))
}

// The scene behind a screen, by its navigation route: Home the lake, a suggestion the dewdrop, setup the bubbles, the
// Settings menu the ribbons and every Settings page the aquarium
fun sceneForRoute(route: String?): Scene {
    // Pick the scene for the screen
    return when {
        route == null || route == ROUTE_HOME -> Scene.LAKE
        route == ROUTE_SETUP -> Scene.BUBBLES
        route.startsWith("suggestion") -> Scene.DEW
        route == ROUTE_SETTINGS -> Scene.RIBBONS
        route.startsWith("$ROUTE_SETTINGS/") -> Scene.AQUARIUM
        else -> Scene.LAKE
    }
}
