// The motion over the photo scenes (see design/DESIGN.md): how strongly each scene moves, where its lens bubbles and
// fish are at any moment, when a surprise shows, and when motion is allowed at all
package io.github.adenkushnir.datebook.scenery
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlin.math.sin
const val MAX_BUBBLES = 14
const val SURPRISE_SECONDS = 14.0

// How strongly a scene moves: water ripples, plant sway, ribbon flow and orb bobbing (fractions of the screen),
// caustic light and glints (brightness), how many lens bubbles rise, and the highest point they rise to (0 = top)
data class SceneEffects(
    val water: Float = 0f,
    val sway: Float = 0f,
    val flow: Float = 0f,
    val bob: Float = 0f,
    val caustics: Float = 0f,
    val glint: Float = 0f,
    val bubbles: Int = 0,
    val top: Float = 0f,
)

// The strengths for each scene, the same as the design workshop's
fun effectsFor(scene: Scene): SceneEffects {
    // Pick the scene's strengths
    return when (scene) {
        Scene.LAKE -> SceneEffects(water = 0.0035f, sway = 0.006f, glint = 0.9f)
        Scene.AQUARIUM -> SceneEffects(water = 0.005f, caustics = 0.45f, glint = 0.5f, bubbles = 8, top = 0.45f)
        Scene.RIBBONS -> SceneEffects(flow = 0.006f, glint = 0.9f, bubbles = 3)
        Scene.DEW -> SceneEffects(glint = 1.2f)
        Scene.BUBBLES -> SceneEffects(bob = 0.006f, glint = 0.9f, bubbles = 9)
        Scene.BEACH -> SceneEffects(water = 0.0035f, sway = 0.004f, glint = 0.9f)
    }
}

// The share sheet's beach: only a strip of water shows through the glass wash, so its waves are stronger and a few
// lens bubbles rise behind the plans
val SHARE_SHEET_EFFECTS = effectsFor(Scene.BEACH).copy(water = 0.008f, bubbles = 6)

// One fish in the aquarium: which picture, its lane (a share of the screen's height), its width in dp, its speed (a
// share of the trip each second), where on its trip it starts, which way it swims (1 = right), and how far back it is
// (0 = nearest; farther fish are fainter)
data class Swimmer(val fish: String, val lane: Float, val size: Float, val speed: Double, val start: Double,
    val direction: Int, val depth: Float)
val SCHOOL = listOf(
    Swimmer("goldfish", 0.58f, 92f, 0.022, 0.1, 1, 0f),
    Swimmer("bluetang", 0.7f, 70f, 0.03, 0.55, -1, 0.4f),
    Swimmer("clownfish", 0.82f, 58f, 0.026, 0.85, 1, 0.2f),
    Swimmer("yellowtang", 0.54f, 50f, 0.034, 0.35, -1, 0.7f),
)

// A lens bubble on screen: its centre as shares of the screen's width and height, and its radius as a share of the
// width
data class LensBubble(val x: Float, val y: Float, val radius: Float)

// The share of the way through its loop something is at a time (0 to 1)
private fun loop(start: Double, time: Double, speed: Double): Double {
    return (start + time * speed).mod(1.0)
}

// The lens bubbles showing at a time (in seconds): the rising bubbles the effects ask for, plus a giant bubble drifting
// across during a surprise (the aquarium gets a big goldfish instead)
fun bubblesAt(scene: Scene, time: Double, surprise: Double?,
    effects: SceneEffects = effectsFor(scene)): List<LensBubble> {
    val list = mutableListOf<LensBubble>()
    val end = if (effects.top > 0f) effects.top.toDouble() else -0.05 // Pop at the water's surface, or leave the top

    // Each rising bubble has its own lane, size and speed
    for (n in 0 until effects.bubbles) {
        val speed = 0.03 + (n * 0.37).mod(1.0) * 0.05
        val progress = loop(n * 0.61, time, speed)
        val radius = 0.012 + (n * 0.53).mod(1.0) * 0.035
        val y = 1.05 - progress * (1.05 - end)
        val x = (n * 0.29 + 0.07).mod(1.0) + sin(time * 1.2 + n) * 0.012
        list.add(LensBubble(x.toFloat(), y.toFloat(), radius.toFloat()))
    }

    // The giant bubble crossing the screen during a surprise
    if (surprise != null && scene != Scene.AQUARIUM) {
        val share = surprise / SURPRISE_SECONDS
        list.add(LensBubble((-0.35 + share * 1.7).toFloat(), (0.35 + sin(share * 3) * 0.05).toFloat(), 0.3f))
    }
    return list.take(MAX_BUBBLES)
}

// Where a fish's middle is at a time, in dp from the left edge: it swims from just off one side to just off the other
fun swimmerX(swimmer: Swimmer, time: Double, widthDp: Float): Float {
    val progress = loop(swimmer.start, time, swimmer.speed).toFloat()
    val span = widthDp + swimmer.size * 2
    return if (swimmer.direction > 0) -swimmer.size + progress * span else widthDp + swimmer.size - progress * span
}

// Whether the scenes may move: the switch is on, the phone isn't saving battery, and Android's animations aren't off
fun motionAllowed(switchOn: Boolean, batterySaver: Boolean, animationScale: Float): Boolean {
    return switchOn && !batterySaver && animationScale > 0f
}

// The "Moving backgrounds" switch as the screens see it, so they stop or start straight away when it's flipped; each
// activity loads it from MotionSettings when it starts
val movingSwitch = mutableStateOf(true)

// Remembers the "Moving backgrounds" switch (on to start)
class MotionSettings(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-look", Context.MODE_PRIVATE)

    // Return true if the backgrounds may move
    fun isOn(): Boolean {
        return prefs.getBoolean("moving", true)
    }

    // Turn the moving backgrounds on or off
    fun set(on: Boolean) {
        prefs.edit().putBoolean("moving", on).apply()
        movingSwitch.value = on
    }
}
