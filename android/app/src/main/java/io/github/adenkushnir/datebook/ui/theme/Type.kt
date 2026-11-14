// Datebook's type: Selawik, Microsoft's free Segoe UI look-alike (bundled, see
// android/third_party/selawik/LICENSE.txt), for every text style
package io.github.adenkushnir.datebook.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.R
val Selawik = FontFamily(
    Font(R.font.selawik_light, FontWeight.Light),
    Font(R.font.selawik_regular, FontWeight.Normal),
    Font(R.font.selawik_semibold, FontWeight.SemiBold),
    Font(R.font.selawik_bold, FontWeight.Bold),
)
private val Defaults = Typography()

// Use Selawik in a text style with no added letter spacing, like the mockup (medium weights use the nearest Selawik
// weight)
private fun TextStyle.inSelawik(): TextStyle {
    return copy(fontFamily = Selawik, letterSpacing = 0.sp)
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
    bodyLarge = Defaults.bodyLarge.inSelawik().copy(fontSize = 15.sp, lineHeight = 21.sp),  // card text
    bodyMedium = Defaults.bodyMedium.inSelawik(),
    bodySmall = Defaults.bodySmall.inSelawik(),
    labelLarge = Defaults.labelLarge.inSelawik().copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),  // buttons
    labelMedium = Defaults.labelMedium.inSelawik(),
    labelSmall = Defaults.labelSmall.inSelawik(),
)
