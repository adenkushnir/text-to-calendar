// The home dashboard: a clear top bar over the scene with Settings, banners for anything off, then Today (the weather
// and today's events, reminders, assignments and bills, the one on now or next lit), Waiting for you, Bills due and
// Loose plans, Coming up (the next 3 days after today with something on) and the train board, on dark glass; also the
// glass pieces every screen shares (top bar, labels, cards)
package io.github.adenkushnir.datebook
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adenkushnir.datebook.calendar.Agenda
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.calendar.endText
import io.github.adenkushnir.datebook.calendar.timeText
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.fencing.FencingSection
import io.github.adenkushnir.datebook.fencing.FencingTile
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.followUpDay
import io.github.adenkushnir.datebook.pipeline.summary
import io.github.adenkushnir.datebook.prompts.changedNote
import io.github.adenkushnir.datebook.prompts.guessNote
import io.github.adenkushnir.datebook.prompts.fromLine
import io.github.adenkushnir.datebook.prompts.isChange
import io.github.adenkushnir.datebook.prompts.promptTitle
import io.github.adenkushnir.datebook.today.TODAY_ASSIGNMENT
import io.github.adenkushnir.datebook.today.TODAY_BILL
import io.github.adenkushnir.datebook.today.TODAY_EVENT
import io.github.adenkushnir.datebook.today.TODAY_REMINDER
import io.github.adenkushnir.datebook.today.TodayItem
import io.github.adenkushnir.datebook.today.TodayList
import io.github.adenkushnir.datebook.today.isPast
import io.github.adenkushnir.datebook.today.nothingLeft
import io.github.adenkushnir.datebook.today.startsIn
import io.github.adenkushnir.datebook.trains.TrainBoard
import io.github.adenkushnir.datebook.weather.Forecast
import io.github.adenkushnir.datebook.weather.HourWeather
import io.github.adenkushnir.datebook.weather.SKY_CLEAR
import io.github.adenkushnir.datebook.weather.SKY_NIGHT
import io.github.adenkushnir.datebook.weather.SKY_RAIN
import io.github.adenkushnir.datebook.weather.SKY_SNOW
import io.github.adenkushnir.datebook.weather.dayText
import io.github.adenkushnir.datebook.weather.hourAt
import io.github.adenkushnir.datebook.weather.hourText
import io.github.adenkushnir.datebook.weather.skyOf
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.GelButton
import io.github.adenkushnir.datebook.ui.GlassIconButton
import io.github.adenkushnir.datebook.ui.HERO_BRUSH
import io.github.adenkushnir.datebook.ui.HERO_GLOSS
import io.github.adenkushnir.datebook.ui.LocalInCard
import io.github.adenkushnir.datebook.ui.LocalOnHero
import io.github.adenkushnir.datebook.ui.PlainButton
import io.github.adenkushnir.datebook.ui.TEXT_SHADOW
import io.github.adenkushnir.datebook.ui.glass
import io.github.adenkushnir.datebook.ui.labelPill
import io.github.adenkushnir.datebook.ui.looseBacking
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
private val LABEL_INK = Color(0xFFD6E8FF)
private val HERO_EDGE = Color.White.copy(alpha = 0.5f)
private val LIT_SHAPE = RoundedCornerShape(12.dp)
private val TRIP_SHAPE = RoundedCornerShape(6.dp)
private val LIT_FILL = Color(0xFF0E86C4).copy(alpha = 0.32f)
private val LIT_EDGE = Color(0xFF5ADBEA).copy(alpha = 0.55f)
private val REMINDER_DOT = Color(0xFFC59BFF)
private val ASSIGNMENT_DOT = Color(0xFFFFB547)
private val BILL_DOT = Color(0xFFFF7AA8)
private const val PAST_ALPHA = 0.45f  // how faded rows that are over look
private val ROW_CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val TODAY_DATE = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private const val OPAQUE = 0xFF shl 24  // calendar colours sometimes leave out the alpha

// Show the whole home dashboard: today, suggestions waiting for you, the next days and the train board
@Composable
fun HomeScreen(
    waiting: List<Suggestion> = emptyList(),
    agenda: Agenda = Agenda(null, emptyList()),
    today: TodayList = TodayList(),
    forecast: Forecast? = null,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    onOpenEvent: (HomeEvent) -> Unit = {},
    onOpen: (Long) -> Unit = {},
    onAdd: (Long) -> Unit = {},
    onIgnore: (Long) -> Unit = {},
    onSettings: () -> Unit = {},
    fixes: List<Pair<String, () -> Unit>> = emptyList(),
    trains: TrainBoard? = null,
    onFlipTrains: () -> Unit = {},
    checking: Set<Long> = emptySet(),
    bills: List<FollowUp> = emptyList(),
    loosePlans: List<FollowUp> = emptyList(),
    onPaid: (Long) -> Unit = {},
    onDropLoose: (Long) -> Unit = {},
    onOpenChat: (FollowUp) -> Unit = {},
    fencing: List<FencingTile>? = null,
    fencingEmpty: String = "",
    onOpenLink: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {  // see-through, so the scene behind shows
        TopBar("Datebook", onSettings = onSettings)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Show a banner for each required setting that is off, with a button to fix it
            for ((problem, fix) in fixes) {
                FixBanner(problem, fix)
            }
            TodayCard(today, forecast, nowMillis, zone, onOpenEvent, onPaid)
            SectionLabel("Waiting for you (${waiting.size})")
            DatebookCard {
                // Show an empty message until there are suggestions
                if (waiting.isEmpty()) {
                    Muted("No suggestions yet. They appear here when a text mentions a plan.")
                }

                // Show each waiting suggestion with Add and Ignore, a line between them; tapping the text opens it
                waiting.forEachIndexed { index, suggestion ->
                    // A line above every suggestion but the first
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onOpen(suggestion.id) }) {
                            Text(promptTitle(suggestion), fontWeight = FontWeight.SemiBold)
                            Muted(whenAndWhere(suggestion))
                            changedNote(suggestion)?.let { Muted(it) }  // only when it changed in the chat
                            guessNote(suggestion, suggestion.retryId in checking)?.let { Muted(it) }  // guesses only
                            ClashLine(suggestion.clash)
                        }
                        val change = isChange(suggestion)  // changes open the event instead
                        GelButton(onClick = { onAdd(suggestion.id) }, green = !change, small = true) {
                            Text(if (change) "Open" else "Add")
                        }
                        GlassIconButton(DatebookIcons.Close, "Ignore", onClick = { onIgnore(suggestion.id) })
                    }
                }
            }
            FollowUpSections(bills, loosePlans, onPaid, onDropLoose, onOpenChat)
            SectionLabel("Coming up")
            DatebookCard {
                // Say so when the rest of the month is empty
                if (agenda.days.isEmpty()) {
                    Muted("Nothing else on your calendar this month.")
                }

                // Show each day's heading and its events, a line between days
                agenda.days.forEachIndexed { index, day ->
                    // A line above every day but the first
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    Text(day.label, color = LABEL_INK, fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    // One row per event; tapping it opens its card
                    for (event in day.events) {
                        EventRow(event, zone, onOpenEvent)
                    }
                }
            }

            // Show the train board once a trip is saved
            if (trains != null) {
                TrainBoardCard(trains, onFlipTrains)
            }

            // Show the men's foil tournaments while fencing is on
            if (fencing != null) {
                FencingSection(fencing, fencingEmpty, onOpenLink)
            }
        }
    }
}

// Bills due (each with Paid) and Loose plans (tap to open the chat, ✕ to drop it), each only when it has something
@Composable
private fun FollowUpSections(bills: List<FollowUp>, loosePlans: List<FollowUp>, onPaid: (Long) -> Unit,
    onDrop: (Long) -> Unit, onOpenChat: (FollowUp) -> Unit) {
    // Bills and due dates, soonest first
    if (bills.isNotEmpty()) {
        SectionLabel("Bills due")
        DatebookCard {
            // One row per bill, a line between them
            bills.forEachIndexed { index, bill ->
                // A line above every bill but the first
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bill.title, fontWeight = FontWeight.SemiBold)
                        val amount = if (bill.amount.isNotBlank()) "${bill.amount} · " else ""  // only when known
                        Muted("${amount}due ${followUpDay(bill)}")
                    }
                    GelButton(onClick = { onPaid(bill.id) }, small = true) {
                        Text("Paid")
                    }
                }
            }
        }
    }

    // Loose plans to pin down
    if (loosePlans.isNotEmpty()) {
        SectionLabel("Loose plans")
        DatebookCard {
            // One row per loose plan, a line between them; tapping it opens the chat it came from
            loosePlans.forEachIndexed { index, loose ->
                // A line above every loose plan but the first
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onOpenChat(loose) }) {
                        Text(loose.title, fontWeight = FontWeight.SemiBold)
                        val day = followUpDay(loose)
                        val who = loose.conversation.ifBlank { loose.sender }
                        Muted(if (day.isBlank()) "From $who" else "From $who · around $day")
                    }
                    GlassIconButton(DatebookIcons.Close, "Drop", onClick = { onDrop(loose.id) })
                }
            }
        }
    }
}

// A blue banner saying what's off, with a button that opens the right setting
@Composable
private fun FixBanner(problem: String, fix: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Surface(color = MaterialTheme.colorScheme.primary, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(problem, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            PlainButton(onClick = fix) {
                Text("Fix", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// The Today card under its date: today's weather, then every row of today in order with a line between them, rows
// that are over faded and the event on now or next lit; "Nothing left today." once there's nothing (more) on
@Composable
private fun TodayCard(today: TodayList, forecast: Forecast?, nowMillis: Long, zone: ZoneId,
    onOpen: (HomeEvent) -> Unit, onPaid: (Long) -> Unit) {
    val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val weather = forecast?.let { dayText(it, nowMillis, zone) }
    SectionLabel("Today · ${date.format(TODAY_DATE)}")
    DatebookCard {
        // The day's weather on top, once a forecast has loaded
        if (forecast != null && weather != null) {
            WeatherLine(forecast.now, weather)
        }

        // One row per item, a line between them and under the weather
        today.items.forEachIndexed { index, item ->
            // A line above every row but a first one with no weather above it
            if (index > 0 || weather != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            TodayRow(item, item == today.lit, forecast, nowMillis, zone, onOpen, onPaid)
        }

        // Say so when nothing is on, or once it's all over
        if (nothingLeft(today, nowMillis)) {
            Muted("Nothing left today.")
        }
    }
}

// Today's weather line: the sky now, the temperature now (when known) and the day in words
@Composable
private fun WeatherLine(now: HourWeather?, day: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The sky and temperature right now, when the forecast has them
        if (now != null) {
            Icon(skyIcon(now), contentDescription = null, tint = LABEL_INK, modifier = Modifier.size(22.dp))
            Text("${now.temp}°", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text(day, color = LABEL_INK, fontSize = 13.sp)
    }
}

// The icon for an hour's sky
private fun skyIcon(hour: HourWeather): ImageVector {
    // Pick the icon for the kind of sky
    return when (skyOf(hour.code, hour.daytime)) {
        SKY_CLEAR -> DatebookIcons.Sun
        SKY_NIGHT -> DatebookIcons.Moon
        SKY_RAIN -> DatebookIcons.Rain
        SKY_SNOW -> DatebookIcons.Snow
        else -> DatebookIcons.Cloud
    }
}

// The dot colour for a row: its calendar's colour for events, else one colour per kind
@Composable
private fun dotFor(item: TodayItem): Color {
    // Pick the colour by what the row is
    return when {
        item.kind == TODAY_EVENT && item.color != 0 -> Color(item.color or OPAQUE)
        item.kind == TODAY_REMINDER -> REMINDER_DOT
        item.kind == TODAY_ASSIGNMENT -> ASSIGNMENT_DOT
        item.kind == TODAY_BILL -> BILL_DOT
        else -> MaterialTheme.colorScheme.primary
    }
}

// One row of Today: its time ("All day" or "Today" without one), a dot, the title and second line, the weather at
// events with a place, and Paid on bills; the lit row also says when it ends or how long until it starts, and an
// event opens its card when tapped
@Composable
private fun TodayRow(item: TodayItem, lit: Boolean, forecast: Forecast?, nowMillis: Long, zone: ZoneId,
    onOpen: (HomeEvent) -> Unit, onPaid: (Long) -> Unit) {
    val faded = if (isPast(item, nowMillis)) Modifier.alpha(PAST_ALPHA) else Modifier
    val look = if (lit) Modifier.clip(LIT_SHAPE).background(LIT_FILL).border(1.dp, LIT_EDGE, LIT_SHAPE)
        .padding(6.dp) else Modifier
    val open = item.event?.let { event -> Modifier.clickable { onOpen(event) } } ?: Modifier
    val time = item.at?.let { Instant.ofEpochMilli(it).atZone(zone).format(ROW_CLOCK) }
        ?: if (item.kind == TODAY_EVENT) "All day" else "Today"
    val hour = if (item.kind == TODAY_EVENT && item.event?.location?.isNotBlank() == true && item.at != null)
        forecast?.let { hourAt(it, item.at) } else null  // weather only where you're going
    val spacing = Arrangement.spacedBy(10.dp)
    Row(Modifier.fillMaxWidth().then(faded).then(look).then(open), horizontalArrangement = spacing) {
        Text(time, color = LABEL_INK, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.width(62.dp).padding(top = 2.dp))
        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(dotFor(item)))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold)

            // The second line, when there is one
            if (item.detail.isNotBlank()) {
                Muted(item.detail)
            }

            // The lit row says when it ends, or how long until it starts
            if (lit && item.at != null) {
                val going = item.at <= nowMillis
                val label = if (going) "Now · ends ${endText(item.event!!, zone)}" else startsIn(item.at, nowMillis)
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        // The weather where you're going, at that hour
        if (hour != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(skyIcon(hour), contentDescription = null, tint = LABEL_INK, modifier = Modifier.size(16.dp))
                Text(hourText(hour), color = LABEL_INK, fontSize = 12.sp)
            }
        }

        // A bill due today can be marked paid right here
        if (item.kind == TODAY_BILL && item.followUp != null) {
            GelButton(onClick = { onPaid(item.followUp.id) }, small = true) {
                Text("Paid")
            }
        }
    }
}

// One event in Coming up: its calendar's colour dot, title, time and place, and who it came from for Datebook plans
@Composable
private fun EventRow(event: HomeEvent, zone: ZoneId, onOpen: (HomeEvent) -> Unit) {
    val dot = if (event.color == 0) MaterialTheme.colorScheme.primary else Color(event.color or OPAQUE)
    Row(Modifier.fillMaxWidth().clickable { onOpen(event) }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(event.title, fontWeight = FontWeight.SemiBold)
            val place = if (event.location.isBlank()) "" else " · ${event.location}"  // only when there is one
            Muted(timeText(event, zone) + place)
            event.source?.let { Muted("From ${fromLine(it)}") }  // Datebook plans only
        }
    }
}

// The train board at the bottom of Home: the saved trip with a button to flip it, then the train that just left
// (faded) and the next 3, or why there are none
@Composable
private fun TrainBoardCard(board: TrainBoard, onFlip: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f, fill = false)) {
            SectionLabel("Trains · ${board.fromName} → ${board.toName}", TRIP_SHAPE, 5.dp)  // fits two lines
        }
        Spacer(Modifier.weight(1f))
        GlassIconButton(DatebookIcons.Swap, "Flip the trip", onClick = onFlip)
    }
    DatebookCard {
        // The train that just left, faded
        if (board.last.isNotBlank()) {
            Text(board.last, fontWeight = FontWeight.SemiBold, modifier = Modifier.alpha(PAST_ALPHA))
        }

        // List the trains, or say why there are none
        if (board.lines.isEmpty()) {
            Muted(board.note.ifBlank { "Loading…" })
        } else {
            board.lines.forEach { Text(it, fontWeight = FontWeight.SemiBold) }  // one line per train
        }
    }
}

// When and where a suggestion is, like "Fri Nov 13, 7:00 PM · Joe's"
fun whenAndWhere(suggestion: Suggestion): String {
    return summary(suggestion.toEvent()).removePrefix("${suggestion.title} · ")
}

// The clash warning under a suggestion: a warning icon and "You already have Practice 6:00 PM–8:00 PM" (nothing when
// it doesn't clash)
@Composable
fun ClashLine(clash: String) {
    // Only show it when something clashes
    if (clash.isNotBlank()) {
        val color = MaterialTheme.colorScheme.tertiary
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(DatebookIcons.Warn, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
            Text(clash, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// The clear bar across the top, below the status bar: the title floating over the scene with a soft shadow, an
// optional back button and an optional Settings button, both glass tiles
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A back button on screens below Home
        if (onBack != null) {
            GlassIconButton(DatebookIcons.Back, "Back", onClick = onBack)
        }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp,
            style = LocalTextStyle.current.copy(shadow = TEXT_SHADOW),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))

        // The Settings button on Home
        if (onSettings != null) {
            GlassIconButton(DatebookIcons.Gear, "Settings", onClick = onSettings)
        }
    }
}

// A small uppercase heading above a group of cards, on a tinted pill (or a squarer shape for labels that can wrap) so
// it reads over the scene
@Composable
fun SectionLabel(text: String, shape: Shape = RoundedCornerShape(50), vertical: Dp = 3.dp) {
    Text(
        text.uppercase(),
        color = if (LocalOnHero.current) Color.White else LABEL_INK,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.labelPill(shape).padding(horizontal = 10.dp, vertical = vertical),
    )
}

// Supporting text in the muted colour (white-ish on the Next up card), with a soft shadow, and a small frosted backing
// when it sits straight on the scene
@Composable
fun Muted(text: String) {
    val color = if (LocalOnHero.current) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text, color = color, fontSize = 13.5.sp, lineHeight = 19.sp,
        style = LocalTextStyle.current.copy(shadow = TEXT_SHADOW), modifier = Modifier.looseBacking())
}

// A card of dark glass tinted by the light, or the glossy blue Next up card when it's the highlighted one
@Composable
fun DatebookCard(accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val surface = if (accent) Modifier.clip(shape).background(HERO_BRUSH).background(HERO_GLOSS)
        .border(1.dp, HERO_EDGE, shape) else Modifier.glass(shape)
    val ink = if (accent) Color.White else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides ink, LocalOnHero provides accent, LocalInCard provides true) {
        Column(Modifier.fillMaxWidth().then(surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content)
    }
}

// Preview of the home screen in Android Studio's design view
@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    DatebookTheme {
        HomeScreen()
    }
}
