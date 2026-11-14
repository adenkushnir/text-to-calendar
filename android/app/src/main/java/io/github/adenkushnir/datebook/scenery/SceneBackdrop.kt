// The photo scene behind a screen: two of its lights cross-fading with the real sun, dimmed late at night, and
// moving (Android 13 and later) unless motion is switched off, battery saver is on or Android's animations are off
package io.github.adenkushnir.datebook.scenery
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import io.github.adenkushnir.datebook.places.TravelSettings
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
private const val TICK_MILLIS = 10_000L
private const val LATE_DIM = 0.3f
private const val MOTION_CHECK_MILLIS = 5_000L
// A faint dark wash so the brightest midday spots never glare behind the light text outside the glass cards
private val VEIL = Color(0xFF0A1420).copy(alpha = 0.12f)

// The light right now, from the real sun where the phone is; work it out once for the whole app so every scene
// shares it
@Composable
fun rememberSceneLight(): SceneLight {
    return lightAt(rememberMinute(), rememberSunTimes())
}

// Show a scene in a light, filling the space it's given, moving when it may; alignment says which part shows when the
// screen's shape crops the picture (the share sheet shows the bottom)
@Composable
fun SceneBackdrop(scene: Scene, light: SceneLight, modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center) {
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) makeSceneShader() else null }
    val moving = rememberMotionAllowed()
    Box(modifier) {
        // The moving scene where it can run, else the still one
        if (shader != null && moving && Build.VERSION.SDK_INT >= 33) {
            MovingScene(scene, light, shader, Modifier.fillMaxSize(), alignment)
        } else {
            StillScene(scene, light, alignment)
        }
        Box(Modifier.fillMaxSize().background(VEIL))
    }
}

// The scene standing still: the light's picture, the next light fading in on top, dimmed late at night
@Composable
private fun StillScene(scene: Scene, light: SceneLight, alignment: Alignment) {
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(pictureFor(scene, light.from)), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = alignment)

        // During a fade, the next light shows through on top
        if (light.mix > 0f) {
            Image(painterResource(pictureFor(scene, light.to)), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = light.mix }, contentScale = ContentScale.Crop,
                alignment = alignment)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = light.late * LATE_DIM)))
    }
}

// Whether the scene may move: the Moving backgrounds switch is on, and (checked every few seconds, since they can
// change any time) the phone isn't saving battery and Android's animations aren't turned off
@Composable
private fun rememberMotionAllowed(): Boolean {
    val context = LocalContext.current.applicationContext
    val system = produceState(Pair(false, 1f)) {
        // Check the phone's battery saver and animation settings now and again while the scene is showing
        while (true) {
            val saving = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
            val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            value = Pair(saving, scale)
            delay(MOTION_CHECK_MILLIS)
        }
    }
    return motionAllowed(movingSwitch.value, system.value.first, system.value.second)
}

// The minute of the day now, with seconds as a fraction (6:43:30 AM is 403.5)
fun minuteNow(now: LocalTime = LocalTime.now()): Double {
    return now.toSecondOfDay() / 60.0
}

// The minute of the day, updated every 10 seconds so fades move smoothly
@Composable
private fun rememberMinute(): Double {
    val state = produceState(minuteNow()) {
        // Keep the minute current while the scene is showing
        while (true) {
            delay(TICK_MILLIS)
            value = minuteNow()
        }
    }
    return state.value
}

// Today's sunrise and sunset where the phone is: a time zone guess at first, then the real place (found off the main
// thread), worked out again just after each midnight
@Composable
private fun rememberSunTimes(): SunTimes {
    val context = LocalContext.current.applicationContext
    val zone = remember { ZoneId.systemDefault() }
    val guess = remember { zoneGuess(zone) }
    val state = produceState(sunTimes(LocalDate.now(zone), guess.first, guess.second, zone)) {
        // Each day, find the place and work out that day's sun times
        while (true) {
            val place = withContext(Dispatchers.IO) { SunPlace(context).find(TravelSettings(context).home(), zone) }
            value = sunTimes(LocalDate.now(zone), place.first, place.second, zone)
            val now = ZonedDateTime.now(zone)
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).toMillis() + 1000)
        }
    }
    return state.value
}
