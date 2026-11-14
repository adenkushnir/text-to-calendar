// Datebook's Aero controls, matching the design workshop: glossy gel pill buttons (blue, or leaf green to add), glass
// buttons, plain text buttons, a glossy gel switch, glass text fields and a glossy progress bar
package io.github.adenkushnir.datebook.ui
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.adenkushnir.datebook.ui.theme.AeroBlueLight
import io.github.adenkushnir.datebook.ui.theme.MutedNight
private val BLUE_GEL = Brush.verticalGradient(listOf(Color(0xFF52C0F6), Color(0xFF178DE0)))
private val GREEN_GEL = Brush.verticalGradient(listOf(Color(0xFF8BDC5C), Color(0xFF46A926)))
private val OFF_TRACK = Brush.verticalGradient(listOf(Color(0xFF2A3646), Color(0xFF4A5A6E)))
private val GLOSS = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.38f), 0.5f to Color.Transparent)
private val KNOB = Brush.radialGradient(0f to Color.White, 0.45f to Color.White, 1f to Color(0xFFDBE8F0))
private val GEL_EDGE = Color.White.copy(alpha = 0.45f)
private val BUTTON_PADDING = PaddingValues(horizontal = 18.dp, vertical = 0.dp)
private val SMALL_BUTTON_PADDING = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
private val FIELD_SHAPE = RoundedCornerShape(12.dp)

// Gel on a shape: a gradient with a soft highlight across its top half and a light edge
private fun Modifier.gel(fill: Brush, shape: Shape): Modifier {
    return clip(shape).background(fill).background(GLOSS).border(1.dp, GEL_EDGE, shape)
}

// A pill button exactly as tall as it looks (38dp, or 32dp when small), without Android's padded touch area, so the
// gel or glass fills the whole button like in the mockup
@Composable
private fun PillButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, small: Boolean,
    contentColor: Color, content: @Composable RowScope.() -> Unit) {
    val colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = contentColor,
        disabledContainerColor = Color.Transparent, disabledContentColor = contentColor)
    val padding = if (small) SMALL_BUTTON_PADDING else BUTTON_PADDING
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Button(onClick = onClick, modifier = modifier.height(if (small) 32.dp else 38.dp), enabled = enabled,
            shape = CircleShape, colors = colors, contentPadding = padding, content = content)
    }
}

// The main glossy pill button: sky-blue gel, or leaf-green gel for adding a plan; faded while it can't be used
@Composable
fun GelButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, green: Boolean = false,
    small: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val glossy = modifier.alpha(if (enabled) 1f else 0.45f).gel(if (green) GREEN_GEL else BLUE_GEL, CircleShape)
    PillButton(onClick, glossy, enabled, small, Color.White, content)
}

// A secondary button of glass tinted by the light, with light-blue text; faded while it can't be used
@Composable
fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false,
    content: @Composable RowScope.() -> Unit) {
    val tint = LocalGlass.current.tint
    val glassy = modifier.alpha(if (enabled) 1f else 0.45f).clip(CircleShape).background(tint.copy(alpha = 0.75f))
        .background(GLOSS).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
    PillButton(onClick, glassy, enabled, small, AeroBlueLight, content)
}

// A text-only button in light blue, for small actions and dialog choices
@Composable
fun PlainButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val colors = ButtonDefaults.textButtonColors(contentColor = AeroBlueLight, disabledContentColor = MutedNight)
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, content = content)
}

// A glossy gel switch: a leaf-green gel track when on, a dark glass track when off, with a shiny white knob
@Composable
fun GelSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    val knobAt by animateDpAsState(if (checked) 22.dp else 2.dp, label = "knob")
    val fill = if (checked) GREEN_GEL else OFF_TRACK
    val track = modifier.size(46.dp, 26.dp).alpha(if (enabled) 1f else 0.45f).gel(fill, CircleShape)
    val tappable = if (onCheckedChange == null) track
        else track.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    Box(tappable, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset(x = knobAt).size(20.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(KNOB))
    }
}

// The colours of a glass text field: glass tinted by the light, a faint light edge, light-blue when it has focus
@Composable
private fun glassFieldColors(): TextFieldColors {
    val tint = LocalGlass.current.tint
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = tint.copy(alpha = 0.7f),
        unfocusedContainerColor = tint.copy(alpha = 0.55f),
        focusedBorderColor = AeroBlueLight,
        unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
        focusedLabelColor = AeroBlueLight,
        cursorColor = AeroBlueLight,
    )
}

// A glossy progress bar like Windows Vista's: a dark glass track filled with green gel up to a share from 0 to 1
@Composable
fun GlassProgress(share: Float, modifier: Modifier = Modifier) {
    val tint = LocalGlass.current.tint
    val track = modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(tint.copy(alpha = 0.7f))
        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
    Box(track) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().gel(GREEN_GEL, CircleShape))
    }
}

// A glass text field: an outlined field in the glass colours with rounded corners
@Composable
fun GlassField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null, singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None) {
    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = modifier, label = label,
        singleLine = singleLine, visualTransformation = visualTransformation, colors = glassFieldColors(),
        shape = FIELD_SHAPE)
}
