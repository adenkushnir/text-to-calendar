// Datebook's icons, drawn from the same shapes as the design workshop's on a 24 by 24 grid (strokes, so they can be
// tinted any colour), plus the icon for each way of getting somewhere
package io.github.adenkushnir.datebook.ui
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.github.adenkushnir.datebook.places.TRAVEL_BIKE
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.places.TRAVEL_WALK

// Build an icon from path strings: rounded 2-unit lines, or filled shapes when filled is true (the tint colours either)
private fun icon(name: String, filled: Boolean, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)

    // Add each path, drawn in black so the tint can recolour it
    for (path in paths) {
        builder.addPath(
            pathData = addPathNodes(path),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

// Every icon Datebook draws
object DatebookIcons {
    // The gear for Settings
    val Gear = icon("gear", filled = false,
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "M5.4 12a6.6 6.6 0 1 0 13.2 0a6.6 6.6 0 1 0 -13.2 0",
        "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1",
    )
    // An arrow pointing back
    val Back = icon("back", filled = false,
        "M19 12H5M11 5l-7 7 7 7",
    )
    // A cross, for ignoring
    val Close = icon("close", filled = false,
        "M6 6l12 12M18 6L6 18",
    )
    // Two arrows, for flipping a trip
    val Swap = icon("swap", filled = false,
        "M4 8h15l-4-4M20 16H5l4 4",
    )
    // A plane, for flights
    val Plane = icon("plane", filled = true,
        "M21.5 12c0-.8-.6-1.3-1.4-1.3h-5.3L9.6 3H7.8l2.6 7.7H5.6L4 8.5H2.6l1 3.5-1 3.5H4l1.6-2.2h4.8L7.8 21" +
            "h1.8l5.2-7.7h5.3c.8 0 1.4-.5 1.4-1.3z",
    )
    // A train
    val Train = icon("train", filled = false,
        "M9.5 3h5a3.5 3.5 0 0 1 3.5 3.5v7a3.5 3.5 0 0 1 -3.5 3.5h-5a3.5 3.5 0 0 1 -3.5 -3.5v-7a3.5 3.5 0 0 1 3.5 -3.5z",
        "M6 10h12M9 21l1.5-4M15 21l-1.5-4",
        "M8.9 13.5a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0",
        "M13.9 13.5a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0",
    )
    // A map pin, for places
    val Pin = icon("pin", filled = false,
        "M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z",
        "M9.6 10a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0",
    )
    // A bell, for notifications
    val Bell = icon("bell", filled = false,
        "M6 16.5v-5a6 6 0 0 1 12 0v5l1.8 2H4.2z",
        "M10 20.5a2 2 0 0 0 4 0",
    )
    // A key, for API keys
    val Key = icon("key", filled = false,
        "M4 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
        "M12 12h9M18 12v3M21 12v2",
    )
    // A lightning bolt, for battery
    val Bolt = icon("bolt", filled = false,
        "M13.5 2.5L5 14h6.5l-1 7.5L19 10h-6.5z",
    )
    // A calendar page
    val Calendar = icon("calendar", filled = false,
        "M6 5h12a2.5 2.5 0 0 1 2.5 2.5v10.5a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 -2.5v-10.5" +
            "a2.5 2.5 0 0 1 2.5 -2.5z",
        "M3.5 10h17M8 3v4M16 3v4",
    )
    // A check mark
    val Check = icon("check", filled = false,
        "M5 12.5l4.5 4.5L19 7.5",
    )
    // Four app squares
    val Apps = icon("apps", filled = false,
        "M5.6 4h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1 -1.6 1.6h-3.3a1.6 1.6 0 0 1 -1.6 -1.6v-3.3" +
            "a1.6 1.6 0 0 1 1.6 -1.6z",
        "M15.1 4h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1 -1.6 1.6h-3.3a1.6 1.6 0 0 1 -1.6 -1.6v-3.3" +
            "a1.6 1.6 0 0 1 1.6 -1.6z",
        "M5.6 13.5h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1 -1.6 1.6h-3.3a1.6 1.6 0 0 1 -1.6 -1.6v-3.3" +
            "a1.6 1.6 0 0 1 1.6 -1.6z",
        "M15.1 13.5h3.3a1.6 1.6 0 0 1 1.6 1.6v3.3a1.6 1.6 0 0 1 -1.6 1.6h-3.3a1.6 1.6 0 0 1 -1.6 -1.6v-3.3" +
            "a1.6 1.6 0 0 1 1.6 -1.6z",
    )
    // A sparkle, for Claude
    val Sparkle = icon("sparkle", filled = false,
        "M12 3l1.8 6.2L20 11l-6.2 1.8L12 19l-1.8-6.2L4 11l6.2-1.8z",
        "M19 3.5v3M17.5 5h3",
    )
    // A padlock, for privacy
    val Lock = icon("lock", filled = false,
        "M7.2 10.5h9.6a2.2 2.2 0 0 1 2.2 2.2v5.6a2.2 2.2 0 0 1 -2.2 2.2h-9.6a2.2 2.2 0 0 1 -2.2 -2.2v-5.6" +
            "a2.2 2.2 0 0 1 2.2 -2.2z",
        "M8 10.5V8a4 4 0 0 1 8 0v2.5",
    )
    // An i in a circle, for About
    val Info = icon("info", filled = false,
        "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0",
        "M12 11v5.5M12 7.6v.2",
    )
    // A circular arrow, for automatic mode
    val Auto = icon("auto", filled = false,
        "M19.5 12a7.5 7.5 0 1 1-2.2-5.3",
        "M19.5 4.5v4.5H15",
    )
    // A car, for driving
    val Car = icon("car", filled = false,
        "M3.5 15.5v-3.3L6 7h12l2.5 5.2v3.3z",
        "M3.5 12h17",
        "M5.8 17.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0",
        "M14.8 17.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0",
    )
    // A bus, for transit
    val Bus = icon("bus", filled = false,
        "M8 3.5h8a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-8a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3z",
        "M5 11h14M8.5 7h7",
        "M7.1 20a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0",
        "M14.1 20a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0",
    )
    // A walking person
    val Walk = icon("walk", filled = false,
        "M11.2 4.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0",
        "M10 21l2.2-6 2.8 2.5V21M12.2 15l-.7-5.5-3.5 2.5v3M11.5 9.5l3.5 3h3",
    )
    // A bicycle
    val Bike = icon("bike", filled = false,
        "M2.2 16a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0",
        "M14.2 16a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0",
        "M6 16l4-7.5h5.5L18 16M10 8.5L13 16H6M13.5 5.5h3",
    )
    // A framed picture of hills and a sun, for the Look page
    val Picture = icon("picture", filled = false,
        "M6 5h12a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5z",
        "M6 16.5l4-4.5 3 3.5 2.5-2.5 3 3.5",
        "M13.9 9a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0",
    )
    // A warning triangle
    val Warn = icon("warn", filled = false,
        "M12 3.5l9.5 16.5h-19z",
        "M12 10v4.5M12 17.3v.2",
    )
}

// The icon for a way of getting somewhere (driving for anything unknown)
fun travelIcon(mode: String): ImageVector {
    // Pick the icon for the mode
    return when (mode) {
        TRAVEL_TRANSIT -> DatebookIcons.Bus
        TRAVEL_WALK -> DatebookIcons.Walk
        TRAVEL_BIKE -> DatebookIcons.Bike
        else -> DatebookIcons.Car
    }
}
