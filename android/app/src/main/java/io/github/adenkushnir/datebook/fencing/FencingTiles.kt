// The men's foil section at the bottom of Home: a row of glass tiles you swipe sideways, one per tournament this week
// (the live one first and lit), each with its day, name, meet and place, when it starts, and Watch and Results; or a
// short line when nothing is on
package io.github.adenkushnir.datebook.fencing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.DatebookCard
import io.github.adenkushnir.datebook.Muted
import io.github.adenkushnir.datebook.SectionLabel
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GlassButton
import io.github.adenkushnir.datebook.ui.glass
private val TILE_SHAPE = RoundedCornerShape(16.dp)
private val LIVE_RED = Color(0xFFFF5A6E)
private val LIT = Color(0xFF0E86C4).copy(alpha = 0.32f)
private val LIT_EDGE = Color(0xFF5ADBEA).copy(alpha = 0.55f)

// The section: its label, then the tiles, or the line for an empty week
@Composable
fun FencingSection(tiles: List<FencingTile>, emptyLine: String, onOpenLink: (String) -> Unit) {
    SectionLabel("Men's foil · this week")

    // A short line when nothing is on, else the tiles
    if (tiles.isEmpty()) {
        DatebookCard {
            Muted(emptyLine)
        }
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(tiles, key = { tournamentKey(it.tournament) }) { tile ->
            FencingTileCard(tile, onOpenLink)
        }
    }
}

// One tile: the day, the live tag or its days, its name, meet and place, its time, and Watch and Results
@Composable
private fun FencingTileCard(tile: FencingTile, onOpenLink: (String) -> Unit) {
    val tournament = tile.tournament
    val (day, days) = tileDays(tile)
    val look = if (tile.live) Modifier.clip(TILE_SHAPE).background(LIT).border(1.dp, LIT_EDGE, TILE_SHAPE)
        else Modifier.glass(TILE_SHAPE)
    Column(Modifier.width(236.dp).then(look).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(day, fontSize = 22.sp, fontWeight = FontWeight.Bold)

            // The live tag, or the days it runs
            if (tile.live) {
                Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(LIVE_RED)
                        .padding(horizontal = 7.dp, vertical = 2.dp))
            } else {
                Muted(days)
            }
        }
        Text(tournament.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Muted(listOf(tournament.meet, tournament.place).filter { it.isNotBlank() }.joinToString(" · "))
        val time = tileTime(tile)

        // When it starts, or what's on now
        if (time.isNotBlank()) {
            Muted(time)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Watch when there's a stream link
            if (tournament.streamUrl.isNotBlank()) {
                GelButton(onClick = { onOpenLink(tournament.streamUrl) }, small = true) {
                    Text("▶ Watch")
                }
            }
            Spacer(Modifier.weight(1f))

            // Results when there's a results page
            if (tournament.resultsUrl.isNotBlank()) {
                GlassButton(onClick = { onOpenLink(tournament.resultsUrl) }, small = true) {
                    Text("Results")
                }
            }
        }
    }
}
