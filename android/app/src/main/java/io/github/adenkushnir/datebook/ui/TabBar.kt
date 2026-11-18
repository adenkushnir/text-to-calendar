// The glass tab bar along the bottom of the main screens (Home · Lists · Notes · Deliberate): each tab's icon above
// its name, the open one lit in aqua
package io.github.adenkushnir.datebook.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.ui.theme.AeroBlueLight
private val BAR_SHAPE = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val TAB_SHAPE = RoundedCornerShape(12.dp)
private val LIT = Color(0xFF0E86C4).copy(alpha = 0.4f)

// One tab: the screen it opens, its icon and its name
class TabItem(val route: String, val icon: ImageVector, val name: String)

// The tab bar: one tab per screen, the selected one lit; tapping another opens it
@Composable
fun TabBar(tabs: List<TabItem>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().glass(BAR_SHAPE).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // One button per tab
        for (tab in tabs) {
            val open = tab.route == selected
            val look = if (open) Modifier.clip(TAB_SHAPE).background(LIT) else Modifier.clip(TAB_SHAPE)
            val ink = if (open) Color.White else AeroBlueLight
            Column(Modifier.weight(1f).then(look).selectable(open, role = Role.Tab) { onSelect(tab.route) }
                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(tab.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
                Text(tab.name, color = ink, fontSize = 11.5.sp, maxLines = 1,
                    fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
