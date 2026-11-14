// Datebook's teal, crisp and square theme in the Inter font, always in its dark colours
package io.github.adenkushnir.datebook.ui.theme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealTint,
    onPrimaryContainer = TealStrong,
    background = BackgroundLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    onSurface = TextLight,
    onSurfaceVariant = MutedLight,
    outline = LineLight,
    tertiary = WarnLight,
    tertiaryContainer = WarnTintLight,
)
private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = OnTealDark,
    primaryContainer = TealTintDark,
    onPrimaryContainer = TealStrongDark,
    background = BackgroundDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    onSurfaceVariant = MutedDark,
    outline = LineDark,
    tertiary = WarnDark,
    tertiaryContainer = WarnTintDark,
)
private val CrispShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

// Wrap the app's screens in Datebook's colours and small-cornered shapes; always dark, so nothing glares over the
// photo scenes (light stays available for previews and tests)
@Composable
fun DatebookTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors  // dark unless a preview or test asks for light
    MaterialTheme(colorScheme = colors, shapes = CrispShapes, typography = DatebookTypography, content = content)
}
