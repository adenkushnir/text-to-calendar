// Datebook's Frutiger Aero theme: the night palette, rounded shapes and the Selawik font, always dark
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
    primary = AeroBlue,
    onPrimary = Color.White,
    primaryContainer = AeroBlueTint,
    onPrimaryContainer = AeroBlueLight,
    secondary = Leaf,
    onSecondary = Color.White,
    secondaryContainer = AeroBlueTint,
    onSecondaryContainer = AeroBlueLight,
    background = BackgroundNight,
    onBackground = TextNight,
    surface = SurfaceNight,
    onSurface = TextNight,
    surfaceVariant = SurfaceNightHigh,
    onSurfaceVariant = MutedNight,
    surfaceContainerLowest = BackgroundNight,
    surfaceContainerLow = SurfaceNightLow,
    surfaceContainer = SurfaceNight,
    surfaceContainerHigh = SurfaceNightHigh,
    surfaceContainerHighest = SurfaceNightHighest,
    outline = LineNight,
    outlineVariant = LineNight,
    tertiary = WarnNight,
    tertiaryContainer = WarnTintNight,
)
private val RoundShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Wrap the app's screens in Datebook's colours and rounded glass shapes; always dark, so nothing glares over the
// photo scenes (light stays available for previews and tests)
@Composable
fun DatebookTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors  // dark unless a preview or test asks for light
    MaterialTheme(colorScheme = colors, shapes = RoundShapes, typography = DatebookTypography, content = content)
}
