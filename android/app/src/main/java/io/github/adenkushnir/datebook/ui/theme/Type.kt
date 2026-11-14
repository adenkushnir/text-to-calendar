// Datebook's type: Selawik, Microsoft's free Segoe UI look-alike (bundled, see android/third_party/selawik/LICENSE.txt),
// for every text style
package io.github.adenkushnir.datebook.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.adenkushnir.datebook.R
val Selawik = FontFamily(
    Font(R.font.selawik_light, FontWeight.Light),
    Font(R.font.selawik_regular, FontWeight.Normal),
    Font(R.font.selawik_semibold, FontWeight.SemiBold),
    Font(R.font.selawik_bold, FontWeight.Bold),
)
private val Defaults = Typography()

// Use Selawik in a text style, keeping its size, weight and spacing (medium weights use the nearest Selawik weight)
private fun TextStyle.inSelawik(): TextStyle {
    return copy(fontFamily = Selawik)
}
val DatebookTypography = Typography(
    displayLarge = Defaults.displayLarge.inSelawik(),
    displayMedium = Defaults.displayMedium.inSelawik(),
    displaySmall = Defaults.displaySmall.inSelawik(),
    headlineLarge = Defaults.headlineLarge.inSelawik(),
    headlineMedium = Defaults.headlineMedium.inSelawik(),
    headlineSmall = Defaults.headlineSmall.inSelawik(),
    titleLarge = Defaults.titleLarge.inSelawik(),
    titleMedium = Defaults.titleMedium.inSelawik(),
    titleSmall = Defaults.titleSmall.inSelawik(),
    bodyLarge = Defaults.bodyLarge.inSelawik(),
    bodyMedium = Defaults.bodyMedium.inSelawik(),
    bodySmall = Defaults.bodySmall.inSelawik(),
    labelLarge = Defaults.labelLarge.inSelawik(),
    labelMedium = Defaults.labelMedium.inSelawik(),
    labelSmall = Defaults.labelSmall.inSelawik(),
)
