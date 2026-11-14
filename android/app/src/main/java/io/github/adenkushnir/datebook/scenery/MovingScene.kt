// The moving scene (Android 13 and later): the photo's two current lights run through an AGSL shader that ripples the
// water, sways the plants, flows the ribbons, bobs the orbs, twinkles glints, adds caustic light and bends the view
// through lens bubbles, with the aquarium's fish swimming on top; a surprise comes about every 3 minutes
package io.github.adenkushnir.datebook.scenery
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
private const val FRAME_NANOS = 33_000_000L  // about 30 frames a second
private const val CLOCK_WRAP = 3600.0  // the clock restarts every hour so its numbers stay precise
private const val SURPRISE_CHANCE = 1.0 / 180  // a chance each second: about one surprise every 3 minutes
private const val FISH_STRIPS = 22
// The motion shader: the design workshop's WebGL one in Android's AGSL, plus wind gusts rolling over grass and palms
private const val SCENE_SHADER = """
uniform shader scenePrev;
uniform shader sceneNext;
uniform shader effectMask;
uniform float mixAmount;
uniform float time;
uniform float night;
uniform float late;
uniform float2 size;
uniform float4 move;
uniform float2 shine;
uniform float3 bubbles[14];

float hash(float2 point) {
    return fract(sin(dot(point, float2(127.1, 311.7))) * 43758.5453);
}

float caustic(float2 point, float t) {
    float2 p = mod(point * 6.2832, 6.2832) - 250.0;
    float2 i = p;
    float total = 1.0;
    for (int n = 0; n < 4; n++) {
        float step = t * (1.0 - 3.5 / float(n + 1));
        i = p + float2(cos(step - i.x) + sin(step + i.y), sin(step - i.y) + cos(step + i.x));
        total += 1.0 / length(float2(p.x / (sin(i.x + step) / 0.005), p.y / (cos(i.y + step) / 0.005)));
    }
    total = 1.17 - pow(total / 4.0, 1.4);
    return pow(abs(total), 8.0);
}

half4 main(float2 coord) {
    float2 uv = coord / size;
    float4 mask = float4(effectMask.eval(coord));
    float near = 0.3 + uv.y;
    float2 offset = float2(0.0);
    offset += mask.r * move.x * near * float2(sin(uv.y * 90.0 - time * 1.6) * 0.6
        + sin(uv.x * 40.0 + uv.y * 60.0 + time * 1.1) * 0.4, sin(uv.x * 30.0 + time * 1.3) * 0.5);
    float gust = smoothstep(-0.2, 1.0, sin(uv.x * 5.0 + uv.y * 2.0 - time * 0.55)) * (0.65 + 0.35 * sin(time * 0.19));
    float flutter = sin(time * 1.55 + uv.x * 70.0 + uv.y * 25.0) * 0.35;
    offset.x += mask.g * move.y * (0.35 * sin(time * 0.45 + uv.y * 3.0) + gust * (1.0 + flutter));
    offset.y += mask.g * move.y * gust * 0.25;
    offset += mask.g * move.z * float2(sin(uv.y * 14.0 + time * 0.8), cos(uv.x * 10.0 + time * 0.7) * 0.5);
    offset.y += mask.b * move.w * sin(time * 0.9 + floor(uv.x * 6.0) * 1.7);
    float2 aspect = float2(1.0, size.y / size.x);
    float rim = 0.0;
    float gloss = 0.0;
    float3 film = float3(0.0);
    for (int n = 0; n < 14; n++) {
        float3 bubble = bubbles[n];
        if (bubble.z > 0.0) {
            float2 away = (uv - bubble.xy) * aspect;
            float reach = length(away) / bubble.z;
            if (reach < 1.0) {
                offset -= (away / aspect) * 0.45 * (1.0 - reach * reach);
                float edge = smoothstep(0.8, 1.0, reach);
                rim += edge;
                float angle = atan(away.y, away.x);
                film += edge * (0.5 + 0.5 * cos(float3(0.0, 2.1, 4.2) + angle * 2.0 + time * 0.5));
                gloss += 1.0 - smoothstep(0.0, 0.3, length(away / bubble.z - float2(-0.38, -0.42)));
            }
        }
    }
    float2 at = clamp(uv + offset, 0.001, 0.999) * size;
    float3 colour = mix(float3(scenePrev.eval(at).rgb), float3(sceneNext.eval(at).rgb), mixAmount);
    colour += shine.x * mask.r * caustic(uv * float2(2.2, 2.2 * aspect.y), time * 0.45) * float3(0.75, 1.0, 1.0)
        * (1.0 - night * 0.85);
    float brightness = dot(colour, float3(0.299, 0.587, 0.114));
    float cell = hash(floor(coord / 3.0));
    float twinkle = smoothstep(0.965, 1.0, cell) * (0.5 + 0.5 * sin(time * 3.0 + cell * 60.0));
    colour += shine.y * mask.b * smoothstep(0.62, 0.92, brightness) * twinkle * float3(1.0, 0.97, 0.9);
    colour *= 1.0 + mask.g * step(0.0001, move.y) * gust * 0.07;
    colour = colour * (1.0 - rim * 0.12) + film * 0.28 + rim * 0.12 + gloss * 0.55;
    colour *= 1.0 - late * 0.3;
    return half4(half3(colour), 1.0);
}
"""

// Make the motion shader, or null if this phone can't compile it (the scene then stays still)
@RequiresApi(33)
fun makeSceneShader(): RuntimeShader? {
    // A shader that fails to compile shouldn't take the app down
    return try {
        RuntimeShader(SCENE_SHADER)
    } catch (error: Exception) {  // the phone's shader compiler refused it
        Log.e("Datebook", "scene shader failed: ${error.message}")
        null
    }
}

// The seconds since the scene started moving, ticking about 30 times a second only while frames are being drawn (so it
// stops by itself when Datebook isn't on screen)
@Composable
private fun rememberSceneSeconds(): Double {
    val state = produceState(0.0) {
        val start = withFrameNanos { it }
        var last = start

        // Tick with the screen's frames, but only update about 30 times a second
        while (true) {
            withFrameNanos { now ->
                // Skip frames that come sooner than a thirtieth of a second
                if (now - last >= FRAME_NANOS) {
                    last = now
                    value = ((now - start) / 1e9).mod(CLOCK_WRAP)
                }
            }
        }
    }
    return state.value
}

// A bitmap shader that places a picture over a size like ContentScale.Crop would, lined up by an alignment
private fun croppedShader(picture: ImageBitmap, width: Float, height: Float, alignment: Alignment): Shader {
    val scale = max(width / picture.width, height / picture.height)
    val drawn = IntSize((picture.width * scale).roundToInt(), (picture.height * scale).roundToInt())
    val spot = alignment.align(drawn, IntSize(width.roundToInt(), height.roundToInt()), LayoutDirection.Ltr)
    val matrix = Matrix()
    matrix.setScale(scale, scale)
    matrix.postTranslate(spot.x.toFloat(), spot.y.toFloat())
    val shader = BitmapShader(picture.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    shader.setLocalMatrix(matrix)
    return shader
}

// Show a scene moving in a light through the motion shader at the given strengths, with the aquarium's fish on top
@RequiresApi(33)
@Composable
fun MovingScene(scene: Scene, light: SceneLight, shader: RuntimeShader, modifier: Modifier, alignment: Alignment,
    effects: SceneEffects) {
    val previous = ImageBitmap.imageResource(pictureFor(scene, light.from))
    val next = ImageBitmap.imageResource(pictureFor(scene, light.to))
    val mask = ImageBitmap.imageResource(maskFor(scene))
    val fish = SCHOOL.associate { it.fish to ImageBitmap.imageResource(fishPicture(it.fish)) }
    val time = rememberSceneSeconds()
    val now by rememberUpdatedState(time)
    var surpriseStart by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        // Once a second, maybe start a surprise, and end one after it has crossed the screen
        while (true) {
            delay(1000)
            val started = surpriseStart

            // Start one now and then, or end the one showing
            if (started == null && Random.nextDouble() < SURPRISE_CHANCE) {
                surpriseStart = now
            } else if (started != null && (now - started).mod(CLOCK_WRAP) > SURPRISE_SECONDS) {
                surpriseStart = null
            }
        }
    }
    val surprise = surpriseStart?.let { (time - it).mod(CLOCK_WRAP) }?.takeIf { it <= SURPRISE_SECONDS }
    Box(modifier.drawWithCache {
        // Fit the pictures to this size (done again only when the size or the pictures change)
        val previousSource = croppedShader(previous, size.width, size.height, alignment)
        val nextSource = croppedShader(next, size.width, size.height, alignment)
        val maskSource = croppedShader(mask, size.width, size.height, alignment)
        onDrawBehind {
            shader.setInputShader("scenePrev", previousSource)
            shader.setInputShader("sceneNext", nextSource)
            shader.setInputShader("effectMask", maskSource)
            shader.setFloatUniform("mixAmount", light.mix)
            shader.setFloatUniform("time", time.toFloat())
            shader.setFloatUniform("night", light.dark)
            shader.setFloatUniform("late", light.late)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("move", effects.water, effects.sway, effects.flow, effects.bob)
            shader.setFloatUniform("shine", effects.caustics, effects.glint)
            shader.setFloatUniform("bubbles", packBubbles(bubblesAt(scene, time, surprise, effects)))
            drawRect(ShaderBrush(shader))

            // The aquarium's fish (and its surprise: a big goldfish swimming by up close)
            if (scene == Scene.AQUARIUM) {
                drawSchool(fish, time, light, surprise)
            }
        }
    })
}

// The lens bubbles as the shader wants them: x, y and radius for each of its 14 slots (empty slots have no radius)
private fun packBubbles(bubbles: List<LensBubble>): FloatArray {
    val packed = FloatArray(MAX_BUBBLES * 3)

    // Fill a slot per bubble
    bubbles.forEachIndexed { n, bubble ->
        packed[n * 3] = bubble.x
        packed[n * 3 + 1] = bubble.y
        packed[n * 3 + 2] = bubble.radius
    }
    return packed
}

// The fish, farthest first, tinted by the water and darker as night falls; during a surprise a big goldfish swims
// across close by, in front of the rest
private fun DrawScope.drawSchool(fish: Map<String, ImageBitmap>, time: Double, light: SceneLight, surprise: Double?) {
    val widthDp = size.width / density
    val dark = light.dark
    val tint = Color(20 - dark * 10, 120 - dark * 80, 170 - dark * 90, 0.18f + dark * 0.5f)

    // Draw each fish from the farthest to the nearest
    for (swimmer in SCHOOL.sortedByDescending { it.depth }) {
        val bob = sin(time * 0.7 + swimmer.start * 9).toFloat() * 8f
        val center = Offset(swimmerX(swimmer, time, widthDp) * density, swimmer.lane * size.height + bob * density)
        val alpha = 1f - swimmer.depth * 0.35f
        drawSwimmer(fish.getValue(swimmer.fish), center, swimmer.size * density, swimmer.direction,
            time * 7 + swimmer.start * 20, alpha, tint)
    }

    // The surprise goldfish, crossing from left to right over the surprise's 14 seconds
    if (surprise != null) {
        val crossing = (surprise / SURPRISE_SECONDS).toFloat() * (widthDp + 380f) - 190f
        val center = Offset(crossing * density, 0.66f * size.height)
        drawSwimmer(fish.getValue("goldfish"), center, 190f * density, 1, time * 7, 1f, tint)
    }
}

// One fish drawn in thin upright strips, each nudged up or down so the tail swings as it swims (pictures face right;
// a direction of -1 flips it to swim left)
private fun DrawScope.drawSwimmer(picture: ImageBitmap, center: Offset, width: Float, direction: Int, phase: Double,
    alpha: Float, tint: Color) {
    val height = width * picture.height / picture.width
    val stripSource = picture.width / FISH_STRIPS
    val stripWidth = (width / FISH_STRIPS).roundToInt() + 1
    withTransform({ scale(direction.toFloat(), 1f, pivot = center) }) {
        // Each strip, from the tail (left) to the nose (right)
        for (n in 0 until FISH_STRIPS) {
            val along = n.toFloat() / FISH_STRIPS
            val swing = (sin(phase - along * 3.2) * (1 - along).pow(2) * height * 0.13).toFloat()
            val spot = IntOffset((center.x - width / 2 + along * width).roundToInt(),
                (center.y - height / 2 + swing).roundToInt())
            drawImage(picture, srcOffset = IntOffset(n * stripSource, 0),
                srcSize = IntSize(stripSource + 1, picture.height), dstOffset = spot,
                dstSize = IntSize(stripWidth, height.roundToInt()), alpha = alpha,
                colorFilter = ColorFilter.tint(tint, BlendMode.SrcAtop))
        }
    }
}
