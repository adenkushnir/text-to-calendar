// Datebook's home-screen widgets, in Android's own style (colours from the wallpaper): Next trains (the saved trip,
// the train that just left and the next 3, with ⇄) and Lists at a glance (packages, the next bill and the next
// assignment); plus the jobs that keep them fresh: every 15 minutes (which also checks notification access) and at
// each train's departure (every 5 minutes in the half hour before it). The text grows with the widget's size, and
// tapping a train opens the NJ Transit app
package io.github.adenkushnir.datebook.widgets
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.ROUTE_LISTS
import io.github.adenkushnir.datebook.ROUTE_SETTINGS_FLIGHTS
import io.github.adenkushnir.datebook.alerts.checkNotificationAccess
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.email.EXTRA_OPEN_ROUTE
import io.github.adenkushnir.datebook.lists.LIST_BILLS
import io.github.adenkushnir.datebook.lists.LIST_PACKAGES
import io.github.adenkushnir.datebook.lists.LIST_DUE
import io.github.adenkushnir.datebook.lists.PackageStore
import io.github.adenkushnir.datebook.trains.TrainBoard
import io.github.adenkushnir.datebook.trains.nextTrains
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
const val EXTRA_OPEN_LIST = "io.github.adenkushnir.datebook.OPEN_LIST"
private const val REFRESH_WORK = "datebook-widgets"
private const val TRAIN_TICK_WORK = "datebook-widget-trains"
private const val MIN_TICK_MILLIS = 60_000L
private const val NJT_APP = "com.njtransit.njtapp"
private val CORNER = 26.dp
private val REFRESHES = intPreferencesKey("refreshes")  // bumped to make an open widget load its data again

// An action that opens Datebook, at a page when one is given (and a list when one is given)
private fun openDatebook(context: Context, route: String? = null, list: String? = null): Action {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .setData(Uri.parse("datebook://open/${route ?: "home"}/${list ?: ""}"))  // keeps each button's intent apart
    route?.let { intent.putExtra(EXTRA_OPEN_ROUTE, it) }  // only when a page was asked for
    list?.let { intent.putExtra(EXTRA_OPEN_LIST, it) }  // only when a list was asked for
    return actionStartActivity(intent)
}

// An action that opens the NJ Transit app, or its Play Store page when it isn't installed
private fun openNjTransit(context: Context): Action {
    val app = context.packageManager.getLaunchIntentForPackage(NJT_APP)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$NJT_APP"))
    return actionStartActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// How many times a widget has been asked to load again, when its content starts
private suspend fun refreshesOf(context: Context, id: GlanceId): Int {
    return getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)[REFRESHES] ?: 0
}

// Redraw every copy of a widget with fresh data, even one still open from a recent tap (Glance only recomposes an
// open widget, so its content watches a counter and loads again when it goes up)
private suspend fun refresh(context: Context, widget: GlanceAppWidget) {
    val ids = GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass)

    // Bump each copy's counter, then redraw it
    for (id in ids) {
        updateAppWidgetState(context, id) { it[REFRESHES] = (it[REFRESHES] ?: 0) + 1 }
        widget.update(context, id)
    }
}

// The widget's rounded background in the wallpaper's colours
@Composable
private fun card(): GlanceModifier {
    return GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(CORNER)
        .padding(horizontal = 14.dp, vertical = 12.dp)
}

// The Next trains widget
class TrainsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact  // redraw at the widget's real size, so the text can fill it

    // Draw the saved trip's board, loading it again whenever the widget is refreshed (like after ⇄)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = loadTrains(context)
        val start = refreshesOf(context, id)
        val pickTrip = openDatebook(context, ROUTE_SETTINGS_FLIGHTS)
        val njTransit = openNjTransit(context)
        provideContent {
            val refreshes = currentState(REFRESHES) ?: 0
            val board by produceState(first, refreshes) {
                // The first board is already loaded; load again after each refresh
                if (refreshes != start) {
                    value = loadTrains(context)
                }
            }
            GlanceTheme {
                TrainsContent(board, pickTrip, njTransit)
            }
        }
    }
}

// The saved trip's board (null without a trip), booking the next redraw: every 5 minutes in the half hour before the
// next train, else half an hour before it
private suspend fun loadTrains(context: Context): TrainBoard? {
    val graph = AppGraph.get(context)
    val trip = graph.trainSettings.trip() ?: return null
    val board = withContext(Dispatchers.IO) { nextTrains(graph, trip.first, trip.second) }
    val tick = nextTickAt(board.nextDeparture, System.currentTimeMillis())

    // Come back when the board will have changed
    if (tick > 0) {
        bookTrainTick(context, tick)
    }
    return board
}

// The trains widget's face: the trip with ⇄, then the train that left (faded) and the next 3; without a
// trip, how to pick one (tapping opens Datebook's trip setting)
@Composable
private fun TrainsContent(board: TrainBoard?, pickTrip: Action, njTransit: Action) {
    val colors = GlanceTheme.colors
    val face = if (board == null) card().clickable(pickTrip) else card()  // with a trip, only ⇄ and trains react
    val size = trainsText(LocalSize.current.height.value)
    Column(face) {
        // Without a trip there's nothing to show yet
        if (board == null) {
            Text("Next trains", style = TextStyle(color = colors.onSurface, fontWeight = FontWeight.Bold,
                fontSize = 14.sp))
            Text("Pick a trip in Datebook: Settings → Flights & trains.",
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp))
            return@Column
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${board.fromName} → ${board.toName}", modifier = GlanceModifier.defaultWeight(), maxLines = 2,
                style = TextStyle(color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium,
                    fontSize = headerText(size).sp))
            Spacer(GlanceModifier.width(8.dp))
            Box(GlanceModifier.size(32.dp).background(colors.secondaryContainer).cornerRadius(16.dp)
                .clickable(actionRunCallback<FlipTripAction>()), contentAlignment = Alignment.Center) {
                Text("⇄", style = TextStyle(color = colors.onSecondaryContainer, fontSize = 15.sp))
            }
        }
        TrainRows(board, size, njTransit)
    }
}

// The trains under the header, spread evenly down the widget in text that fills it; tapping one opens NJ Transit
@Composable
private fun ColumnScope.TrainRows(board: TrainBoard, size: Float, njTransit: Action) {
    val colors = GlanceTheme.colors
    val tap = GlanceModifier.fillMaxWidth().clickable(njTransit)
    Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
        Spacer(GlanceModifier.defaultWeight())

        // The train that just left, faded
        if (board.last.isNotBlank()) {
            Text(board.last, modifier = tap, maxLines = 1, style = TextStyle(color = colors.outline,
                fontSize = size.sp))
            Spacer(GlanceModifier.defaultWeight())
        }

        // Each of the next trains, cancelled ones in the warning colour
        for (line in board.lines) {
            val ink = if (line.contains("Cancelled")) colors.error else colors.onSurface
            Text(line, modifier = tap, maxLines = 1, style = TextStyle(color = ink, fontWeight = FontWeight.Medium,
                fontSize = size.sp))
            Spacer(GlanceModifier.defaultWeight())
        }

        // Say why there are no trains
        if (board.lines.isEmpty()) {
            Text(board.note.ifBlank { "No trains found right now." },
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = headerText(size).sp))
            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

// ⇄ on the trains widget: flip the saved trip and redraw
class FlipTripAction : ActionCallback {
    // Swap the trip's stations, then update every trains widget
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val settings = AppGraph.get(context).trainSettings
        val trip = settings.trip() ?: return
        settings.setTrip(trip.second, trip.first)
        refresh(context, TrainsWidget())
    }
}

// The Lists at a glance widget
class ListsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact  // redraw at the widget's real size, so the text can fill it

    // Draw the rows, loading them again whenever the widget is refreshed (like after Paid on a notification)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = loadLists(context)
        val start = refreshesOf(context, id)
        val opens = listOf(LIST_PACKAGES, LIST_BILLS, LIST_DUE).associateWith { openDatebook(context, ROUTE_LISTS, it) }
        provideContent {
            val refreshes = currentState(REFRESHES) ?: 0
            val rows by produceState(first, refreshes) {
                // The first rows are already loaded; load again after each refresh
                if (refreshes != start) {
                    value = loadLists(context)
                }
            }
            GlanceTheme {
                ListsContent(rows, opens, opens.getValue(LIST_PACKAGES))
            }
        }
    }
}

// The widget's rows from the packages on the way, open bills and open assignments
private suspend fun loadLists(context: Context): List<GlanceRow> {
    val dao = AppGraph.get(context).dao
    val received = PackageStore(context).received()
    val packages = dao.watchPackages().first().filter { it.id !in received }
    return glanceRows(packages, dao.openFollowUps(KIND_DUE), dao.openFollowUps(KIND_ASSIGNMENT), LocalDate.now())
}

// The Lists widget's face: a title, then one row per list with its icon, two lines and a chip, spread down the widget
// in text that fills it; tapping a row opens that list
@Composable
private fun ListsContent(rows: List<GlanceRow>, opens: Map<String, Action>, openLists: Action) {
    val colors = GlanceTheme.colors
    val main = listsText(LocalSize.current.height.value)
    val sub = maxOf(12f, main * 0.78f)
    Column(card().clickable(openLists)) {
        Text("Datebook lists", style = TextStyle(color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium,
            fontSize = headerText(main).sp))

        // Say so when every list is empty
        if (rows.isEmpty()) {
            Text("Nothing on your lists.", style = TextStyle(color = colors.onSurface, fontSize = main.sp))
        }
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            Spacer(GlanceModifier.defaultWeight())

            // One row per list, with even space around them
            for (row in rows) {
                Row(GlanceModifier.fillMaxWidth().clickable(opens.getValue(row.list)),
                    verticalAlignment = Alignment.CenterVertically) {
                    // One icon per list, like the Lists tab's segments
                    val icon = when (row.list) {
                        LIST_PACKAGES -> "📦"
                        LIST_BILLS -> "🧾"
                        else -> "📘"
                    }
                    Box(GlanceModifier.size((main * 2.1f).dp).background(colors.secondaryContainer).cornerRadius(10.dp),
                        contentAlignment = Alignment.Center) {
                        Text(icon, style = TextStyle(fontSize = main.sp))
                    }
                    Spacer(GlanceModifier.width(10.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(row.main, maxLines = 1, style = TextStyle(color = colors.onSurface,
                            fontWeight = FontWeight.Medium, fontSize = main.sp))
                        Text(row.sub, maxLines = 1, style = TextStyle(color = colors.onSurfaceVariant,
                            fontSize = sub.sp))
                    }
                    Text(row.chip, modifier = GlanceModifier.background(colors.secondaryContainer).cornerRadius(10.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = TextStyle(color = colors.onSecondaryContainer, fontSize = maxOf(11f, sub * 0.9f).sp))
                }
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}

// Lets Android place the Next trains widget
class TrainsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrainsWidget()
}

// Lets Android place the Lists at a glance widget
class ListsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListsWidget()
}

// Redraw every Datebook widget on the home screen (nothing happens when none are placed)
suspend fun updateWidgets(context: Context) {
    refresh(context, TrainsWidget())
    refresh(context, ListsWidget())
}

// Every 15 minutes: check notification access (alerting once if it's off) and refresh the widgets
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Check, then redraw
    override suspend fun doWork(): Result {
        checkNotificationAccess(applicationContext, AppGraph.get(applicationContext).dao)
        updateWidgets(applicationContext)
        return Result.success()
    }
}

// Start the 15-minute widget and access check if it isn't running yet
fun scheduleWidgetRefresh(context: Context) {
    val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}

// Redraws the trains widget when a train has left
class TrainTickWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Redraw (which books the next one)
    override suspend fun doWork(): Result {
        refresh(applicationContext, TrainsWidget())
        return Result.success()
    }
}

// Book the trains widget's next redraw for a time (at least a minute away), replacing any booked before
fun bookTrainTick(context: Context, atMillis: Long) {
    val delay = maxOf(atMillis - System.currentTimeMillis(), MIN_TICK_MILLIS)
    val request = OneTimeWorkRequestBuilder<TrainTickWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
    WorkManager.getInstance(context).enqueueUniqueWork(TRAIN_TICK_WORK, ExistingWorkPolicy.REPLACE, request)
}
