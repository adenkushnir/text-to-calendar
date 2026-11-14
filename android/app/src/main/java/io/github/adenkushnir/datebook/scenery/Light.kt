// The light of the day: which two of a scene's six pictures show at any minute, how far the fade between them has
// gone, and how dim it is late at night
package io.github.adenkushnir.datebook.scenery
import kotlin.math.max

// The six lights every scene comes in, in the order a day goes through them, with each picture's name part
enum class Light(val part: String) {
    NIGHT("night"),
    BLUE_HOUR("bluehour"),
    MORNING("morning"),
    MIDDAY("midday"),
    GOLDEN("golden"),
    SUNSET("sunset"),
}

// The light at one moment: the two pictures it sits between, how far the fade has gone (0 to 1), how dark it is (0 to
// 1, for the glass and fish in later steps), and how far into late night it is (0 to 1)
data class SceneLight(val from: Light, val to: Light, val mix: Float, val dark: Float, val late: Float)

// An S-shaped 0-to-1 ramp from start to end, so fades start and end gently
fun eased(minute: Double, start: Double, end: Double): Double {
    val share = ((minute - start) / max(1.0, end - start)).coerceIn(0.0, 1.0)
    return share * share * (3 - 2 * share)
}

// The day's keyframes: at each minute (relative to today's sunrise and sunset) which light is fully showing; the
// pictures fade between neighbours
fun keyframes(sun: SunTimes): List<Pair<Double, Light>> {
    return listOf(
        Pair(sun.rise - 80, Light.NIGHT),
        Pair(sun.rise - 25, Light.BLUE_HOUR),
        Pair(sun.rise + 25, Light.MORNING),
        Pair(sun.rise + 110, Light.MORNING),
        Pair(sun.rise + 230, Light.MIDDAY),
        Pair(sun.set - 220, Light.MIDDAY),
        Pair(sun.set - 95, Light.GOLDEN),
        Pair(sun.set - 30, Light.SUNSET),
        Pair(sun.set + 10, Light.SUNSET),
        Pair(sun.set + 45, Light.BLUE_HOUR),
        Pair(sun.set + 95, Light.NIGHT),
    )
}

// How far into late night a minute of the day is: easing in from 11 PM and back out by 5 AM (0 = not late)
fun lateNight(minute: Double): Float {
    // Ease in after 11 PM, and out before 5 AM
    if (minute >= 1380) {
        return eased(minute, 1380.0, 1410.0).toFloat()
    } else if (minute < 300) {
        return (1 - eased(minute, 270.0, 300.0)).toFloat()
    }
    return 0f
}

// The light at a minute of the day (0 to 1440) for today's sun times; with the phone's dark mode on it's night all day
fun lightAt(minute: Double, sun: SunTimes, darkMode: Boolean = false): SceneLight {
    val late = lateNight(minute)

    // Dark mode keeps the night look all day
    if (darkMode) {
        return SceneLight(Light.NIGHT, Light.NIGHT, 0f, 1f, late)
    }
    val keys = keyframes(sun)
    var from = Light.NIGHT
    var to = Light.NIGHT
    var mix = 0.0

    // Find the two keyframes around the minute (before the first or after the last, it's night)
    for (index in 0 until keys.size - 1) {
        // The minute falls in this stretch
        if (minute >= keys[index].first && minute < keys[index + 1].first) {
            from = keys[index].second
            to = keys[index + 1].second
            mix = if (from == to) 0.0 else eased(minute, keys[index].first, keys[index + 1].first)
        }
    }
    val dark = darkness(from) * (1 - mix) + darkness(to) * mix
    return SceneLight(from, to, mix.toFloat(), dark.toFloat(), late)
}

// How dark a light is: night fully, blue hour partly, the daylight ones not at all
private fun darkness(light: Light): Double {
    // Pick the darkness for the light
    return when (light) {
        Light.NIGHT -> 1.0
        Light.BLUE_HOUR -> 0.6
        else -> 0.0
    }
}
