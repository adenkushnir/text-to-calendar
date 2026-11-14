// Datebook's glass look (see design/DESIGN.md): dark glass tinted by the light that frosts the scene behind it, glass
// tile icons, the glossy Next up colours, green Add buttons, and a soft shadow that keeps loose text readable
package io.github.adenkushnir.datebook.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import io.github.adenkushnir.datebook.scenery.Light
import io.github.adenkushnir.datebook.scenery.SceneLight
import io.github.adenkushnir.datebook.ui.theme.Leaf
// The dark glass colour in each light: navy at night, indigo at blue hour, dusky lilac in the morning, deep sky blue
// at midday, amber at golden hour, plum at sunset
private val GLASS_COLOURS = mapOf(
    Light.NIGHT to Color(12, 24, 54),
    Light.BLUE_HOUR to Color(26, 30, 70),
    Light.MORNING to Color(46, 40, 58),
    Light.MIDDAY to Color(16, 44, 72),
    Light.GOLDEN to Color(62, 44, 28),
    Light.SUNSET to Color(64, 30, 50),
)
private const val GLASS_ALPHA = 0.66f  // medium frost
private const val SCRIM_ALPHA = 0.86f  // the tint is all there is where blur isn't available
private val BLUR_RADIUS = 18.dp
private val GLASS_EDGE = Color.White.copy(alpha = 0.18f)
val TILE_BLUE = Color(0xFF0A6FC0)
val TILE_LEAF = Color(0xFF3A961C)
val TILE_AQUA = Color(0xFF0A98B0)
val TILE_WARN = Color(0xFFD26F00)
val HERO_BRUSH = Brush.linearGradient(listOf(Color(0xE0259BE8), Color(0xD61CC3D6)))
val TEXT_SHADOW = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1.5f), 6f)
private val TILE_BRUSH = Brush.verticalGradient(
    0f to Color.White,
    0.48f to Color(0xFFE8F6FE),
    0.52f to Color(0xFFCFEAFA),
    1f to Color(0xFFEFFAFF),
)
private val TILE_EDGE = Color(0xBF6EAAD2)

// The glass for the screens now: the scene's blur state (null where there's no scene behind, like previews) and the
// light's tint
data class Glass(val haze: HazeState?, val tint: Color)
val LocalGlass = compositionLocalOf { Glass(null, GLASS_COLOURS.getValue(Light.NIGHT)) }

// The glass tint for a light: the two lights' colours blended by how far the fade has gone
fun glassTint(light: SceneLight): Color {
    return lerp(GLASS_COLOURS.getValue(light.from), GLASS_COLOURS.getValue(light.to), light.mix)
}

// Dark glass in a shape: the scene behind frosted and tinted by the light (a solid tint without a scene or blur), with
// a faint light edge; strength under 1 makes it more see-through
@Composable
fun Modifier.glass(shape: Shape, strength: Float = 1f): Modifier {
    val glass = LocalGlass.current
    val tint = glass.tint.copy(alpha = GLASS_ALPHA * strength)
    val style = remember(tint) {
        HazeBlurStyle {
            blurRadius(BLUR_RADIUS)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
            fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = SCRIM_ALPHA)))
        }
    }
    val haze = glass.haze
    val frosted = if (haze != null) clip(shape).hazeBlur(HazeInput.Sources(haze), style)
        else clip(shape).background(tint)
    return frosted.border(1.dp, GLASS_EDGE, shape)
}

// A light tinted pill behind a small label, without blur (labels can sit on glass cards, where blur would cut a hole)
@Composable
fun Modifier.labelPill(): Modifier {
    val tint = LocalGlass.current.tint
    return clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.55f))
}

// A glass tile icon: a rounded glass square with a split highlight and the icon in colour
@Composable
fun GlassTile(icon: ImageVector, description: String?, modifier: Modifier = Modifier, size: Dp = 32.dp,
    tint: Color = TILE_BLUE) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(modifier.size(size).clip(shape).background(TILE_BRUSH).border(1.dp, TILE_EDGE, shape),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(size * 0.62f), tint = tint)
    }
}

// A glass tile that's a button, with a full-size touch target
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = TILE_BLUE) {
    IconButton(onClick = onClick) {
        GlassTile(icon, description, tint = tint)
    }
}

// The leaf-green colours of a button that adds a plan
@Composable
fun addButtonColors(): ButtonColors {
    return ButtonDefaults.buttonColors(containerColor = Leaf, contentColor = Color.White)
}
