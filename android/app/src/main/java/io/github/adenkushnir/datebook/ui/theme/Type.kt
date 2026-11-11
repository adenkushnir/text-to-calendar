// Datebook's type: the Inter font (bundled, see android/third_party/inter/LICENSE.txt) for every text style
package io.github.adenkushnir.datebook.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.adenkushnir.datebook.R
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
private val Defaults = Typography()

// Use Inter in a text style, keeping its size, weight and spacing
private fun TextStyle.inInter(): TextStyle {
    return copy(fontFamily = Inter)
}
val DatebookTypography = Typography(
    displayLarge = Defaults.displayLarge.inInter(),
    displayMedium = Defaults.displayMedium.inInter(),
    displaySmall = Defaults.displaySmall.inInter(),
    headlineLarge = Defaults.headlineLarge.inInter(),
    headlineMedium = Defaults.headlineMedium.inInter(),
    headlineSmall = Defaults.headlineSmall.inInter(),
    titleLarge = Defaults.titleLarge.inInter(),
    titleMedium = Defaults.titleMedium.inInter(),
    titleSmall = Defaults.titleSmall.inInter(),
    bodyLarge = Defaults.bodyLarge.inInter(),
    bodyMedium = Defaults.bodyMedium.inInter(),
    bodySmall = Defaults.bodySmall.inInter(),
    labelLarge = Defaults.labelLarge.inInter(),
    labelMedium = Defaults.labelMedium.inInter(),
    labelSmall = Defaults.labelSmall.inInter(),
)
