// Datebook's main screen: setup until it's finished, then the tabs (Home from your calendar, Lists, Notes and Ask),
// a suggestion's or calendar event's card and Settings; opens the suggestion a tapped prompt names, and shows banners
// for settings that are off
package io.github.adenkushnir.datebook
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.ExistingWorkPolicy
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.adenkushnir.datebook.calendar.HomeEvent
import io.github.adenkushnir.datebook.calendar.agendaOf
import io.github.adenkushnir.datebook.calendar.calendarChanges
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.calendar.fromCalendar
import io.github.adenkushnir.datebook.calendar.fromPlans
import io.github.adenkushnir.datebook.calendar.hasCalendarAccess
import io.github.adenkushnir.datebook.calendar.homeCalendarEvents
import io.github.adenkushnir.datebook.calendar.phoneCalendars
import io.github.adenkushnir.datebook.calendar.planOf
import io.github.adenkushnir.datebook.capture.WATCHABLE_APPS
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import io.github.adenkushnir.datebook.email.EXTRA_OPEN_ROUTE
import io.github.adenkushnir.datebook.email.GMAIL_APP
import io.github.adenkushnir.datebook.email.GmailSettings
import io.github.adenkushnir.datebook.email.ROUTE_SETTINGS_GMAIL
import io.github.adenkushnir.datebook.email.checkGmailNow
import io.github.adenkushnir.datebook.email.gmailAddress
import io.github.adenkushnir.datebook.email.gmailRequest
import io.github.adenkushnir.datebook.email.scheduleGmailChecks
import io.github.adenkushnir.datebook.email.stopGmailChecks
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.finishFollowUp
import io.github.adenkushnir.datebook.canvas.CanvasSettings
import io.github.adenkushnir.datebook.canvas.checkCanvas
import io.github.adenkushnir.datebook.canvas.checkCanvasSoon
import io.github.adenkushnir.datebook.canvas.downloadFeed
import io.github.adenkushnir.datebook.canvas.looksLikeFeed
import io.github.adenkushnir.datebook.canvas.resultText
import io.github.adenkushnir.datebook.canvas.scheduleCanvas
import io.github.adenkushnir.datebook.deliberate.whenText
import io.github.adenkushnir.datebook.settings.SchoolPage
import io.github.adenkushnir.datebook.settings.FencingPage
import io.github.adenkushnir.datebook.fencing.FencingSettings
import io.github.adenkushnir.datebook.fencing.emptyWeekLine
import io.github.adenkushnir.datebook.fencing.fencingTiles
import io.github.adenkushnir.datebook.fencing.scheduleFencing
import io.github.adenkushnir.datebook.fencing.fencingSearching
import io.github.adenkushnir.datebook.fencing.searchInBackground
import io.github.adenkushnir.datebook.fencing.FtlAccount
import io.github.adenkushnir.datebook.fencing.lookUpFtlSoon
import io.github.adenkushnir.datebook.settings.NestorPage
import io.github.adenkushnir.datebook.nestor.LOCAL_NETWORK_PERMISSION
import io.github.adenkushnir.datebook.nestor.NestorLink
import io.github.adenkushnir.datebook.nestor.NestorSettings
import io.github.adenkushnir.datebook.nestor.hasLocalNetwork
import io.github.adenkushnir.datebook.capture.hasNotificationAccess
import io.github.adenkushnir.datebook.deliberate.DeliberateActions
import io.github.adenkushnir.datebook.deliberate.DeliberateScreen
import io.github.adenkushnir.datebook.deliberate.VIEW_CHAT
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_LOOSE
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.flights.scheduleFlightChecks
import io.github.adenkushnir.datebook.followups.ROUTE_WEEK
import io.github.adenkushnir.datebook.followups.WeekAheadScreen
import io.github.adenkushnir.datebook.followups.WeekAheadSettings
import io.github.adenkushnir.datebook.followups.bookNextFollowUp
import io.github.adenkushnir.datebook.followups.weekDays
import io.github.adenkushnir.datebook.followups.scheduleFollowUps
import io.github.adenkushnir.datebook.calendar.canWriteCalendar
import io.github.adenkushnir.datebook.calendar.writableCalendars
import io.github.adenkushnir.datebook.calendar.writeCalendarId
import io.github.adenkushnir.datebook.flights.BOARDING_MINUTES
import io.github.adenkushnir.datebook.flights.boardingMinutes
import io.github.adenkushnir.datebook.leaveby.flightFor
import io.github.adenkushnir.datebook.leaveby.leadMinutes
import io.github.adenkushnir.datebook.flights.FLIGHT_EARLY_MINUTES
import io.github.adenkushnir.datebook.flights.isFlightTitle
import io.github.adenkushnir.datebook.flights.statusLine
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.earlyFor
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.leaveby.planLeaveBy
import io.github.adenkushnir.datebook.leaveby.scheduleLeaveBy
import io.github.adenkushnir.datebook.pipeline.IncomingText
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.pipeline.maskKey
import io.github.adenkushnir.datebook.pipeline.summary
import io.github.adenkushnir.datebook.places.TRAVEL_WALK
import io.github.adenkushnir.datebook.places.mapsIntent
import io.github.adenkushnir.datebook.scenery.MotionSettings
import io.github.adenkushnir.datebook.scenery.SceneBackdrop
import io.github.adenkushnir.datebook.scenery.rememberSceneLight
import io.github.adenkushnir.datebook.scenery.movingSwitch
import io.github.adenkushnir.datebook.scenery.sceneForRoute
import io.github.adenkushnir.datebook.prompts.EXTRA_SUGGESTION_ID
import io.github.adenkushnir.datebook.prompts.addSuggestion
import io.github.adenkushnir.datebook.prompts.ignoreSuggestion
import io.github.adenkushnir.datebook.settings.AboutPage
import io.github.adenkushnir.datebook.settings.AppsPage
import io.github.adenkushnir.datebook.settings.AutomaticPage
import io.github.adenkushnir.datebook.settings.CalendarsPage
import io.github.adenkushnir.datebook.settings.ClaudePage
import io.github.adenkushnir.datebook.settings.FlightsPage
import io.github.adenkushnir.datebook.settings.GmailPage
import io.github.adenkushnir.datebook.settings.LookPage
import io.github.adenkushnir.datebook.settings.GITHUB_URL
import io.github.adenkushnir.datebook.settings.NotificationsPage
import io.github.adenkushnir.datebook.settings.PlacesPage
import io.github.adenkushnir.datebook.settings.PrivacyPage
import io.github.adenkushnir.datebook.settings.SettingsItem
import io.github.adenkushnir.datebook.settings.SettingsMenu
import io.github.adenkushnir.datebook.setup.SetupActions
import io.github.adenkushnir.datebook.setup.SetupScreen
import io.github.adenkushnir.datebook.setup.SetupStatus
import io.github.adenkushnir.datebook.setup.checkClaudeKey
import io.github.adenkushnir.datebook.setup.markSetupFinished
import io.github.adenkushnir.datebook.setup.readSetupStatus
import io.github.adenkushnir.datebook.setup.setupFinished
import io.github.adenkushnir.datebook.setup.showSamplePrompt
import io.github.adenkushnir.datebook.summary.scheduleSummary
import io.github.adenkushnir.datebook.extras.scheduleEvening
import io.github.adenkushnir.datebook.journeys.Journey
import io.github.adenkushnir.datebook.places.TRAVEL_DRIVE
import io.github.adenkushnir.datebook.places.TRAVEL_TRANSIT
import io.github.adenkushnir.datebook.prompts.isChange
import io.github.adenkushnir.datebook.journeys.FlightDialog
import io.github.adenkushnir.datebook.journeys.JourneyStep
import io.github.adenkushnir.datebook.journeys.JourneyStore
import io.github.adenkushnir.datebook.journeys.STEP_BIKE
import io.github.adenkushnir.datebook.journeys.STEP_BOARD
import io.github.adenkushnir.datebook.journeys.journeyMode
import io.github.adenkushnir.datebook.places.TRAVEL_BIKE
import io.github.adenkushnir.datebook.journeys.STEP_DRIVE
import io.github.adenkushnir.datebook.journeys.STEP_FLIGHT
import io.github.adenkushnir.datebook.journeys.STEP_WALK
import io.github.adenkushnir.datebook.journeys.TrainPickerDialog
import io.github.adenkushnir.datebook.journeys.journeyChoices
import io.github.adenkushnir.datebook.journeys.planJourney
import io.github.adenkushnir.datebook.journeys.withFlightSteps
import io.github.adenkushnir.datebook.journeys.withLiveTrain
import io.github.adenkushnir.datebook.flights.checkInLink
import io.github.adenkushnir.datebook.tracking.trackLinkFor
import io.github.adenkushnir.datebook.trains.ON_THE_DAY
import io.github.adenkushnir.datebook.trains.TrainBoard
import io.github.adenkushnir.datebook.trains.TrainChoices
import io.github.adenkushnir.datebook.trains.nextTrains
import io.github.adenkushnir.datebook.widgets.EXTRA_OPEN_LIST
import io.github.adenkushnir.datebook.widgets.scheduleWidgetRefresh
import io.github.adenkushnir.datebook.widgets.updateWidgets
import io.github.adenkushnir.datebook.alerts.activeProblems
import io.github.adenkushnir.datebook.alerts.alertRoute
import io.github.adenkushnir.datebook.alerts.resetClaudeProblems
import io.github.adenkushnir.datebook.pipeline.ALERT_TITLES
import io.github.adenkushnir.datebook.pipeline.PROBLEM_NOTIFICATIONS
import androidx.lifecycle.lifecycleScope
import io.github.adenkushnir.datebook.today.todayOf
import io.github.adenkushnir.datebook.weather.Forecast
import io.github.adenkushnir.datebook.weather.homeForecast
import io.github.adenkushnir.datebook.trains.refreshStations
import io.github.adenkushnir.datebook.trains.scheduleTrains
import io.github.adenkushnir.datebook.trains.trainChoices
import io.github.adenkushnir.datebook.trains.wantsTrain
import io.github.adenkushnir.datebook.data.noteTitle
import io.github.adenkushnir.datebook.notes.NotesActions
import io.github.adenkushnir.datebook.notes.NotesScreen
import io.github.adenkushnir.datebook.lists.LIST_PACKAGES
import io.github.adenkushnir.datebook.lists.ListsActions
import io.github.adenkushnir.datebook.lists.ListsContent
import io.github.adenkushnir.datebook.lists.ListsScreen
import io.github.adenkushnir.datebook.lists.PackageStore
import io.github.adenkushnir.datebook.lists.tripsOf
import io.github.adenkushnir.datebook.ui.DatebookIcons
import io.github.adenkushnir.datebook.ui.TabBar
import io.github.adenkushnir.datebook.ui.TabItem
import io.github.adenkushnir.datebook.ui.Glass
import io.github.adenkushnir.datebook.ui.LocalGlass
import io.github.adenkushnir.datebook.ui.TILE_AQUA
import io.github.adenkushnir.datebook.ui.TILE_BLUE
import io.github.adenkushnir.datebook.ui.TILE_LEAF
import io.github.adenkushnir.datebook.ui.TILE_WARN
import io.github.adenkushnir.datebook.ui.glassTint
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
const val ROUTE_SETUP = "setup"
const val ROUTE_HOME = "home"
const val ROUTE_LISTS = "lists"
const val ROUTE_NOTES = "notes"
const val ROUTE_DELIBERATE = "deliberate"
const val ROUTE_SUGGESTION = "suggestion/{id}"
const val ROUTE_EVENT = "event/{eventId}/{begin}"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_SETTINGS_APPS = "settings/apps"
const val ROUTE_SETTINGS_CLAUDE = "settings/claude"
const val ROUTE_SETTINGS_CALENDARS = "settings/calendars"
const val ROUTE_SETTINGS_PLACES = "settings/places"
const val ROUTE_SETTINGS_FLIGHTS = "settings/flights"
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION)
private val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
const val ROUTE_SETTINGS_NOTIFICATIONS = "settings/notifications"
const val ROUTE_SETTINGS_PRIVACY = "settings/privacy"
const val ROUTE_SETTINGS_ABOUT = "settings/about"
const val ROUTE_SETTINGS_AUTOMATIC = "settings/automatic"
const val ROUTE_SETTINGS_LOOK = "settings/look"
const val ROUTE_SETTINGS_SCHOOL = "settings/school"
const val ROUTE_SETTINGS_FENCING = "settings/fencing"
const val ROUTE_SETTINGS_NESTOR = "settings/nestor"
private const val TICK_MILLIS = 30_000L  // how often Home moves "now" on
private val MAIN_TABS = listOf(TabItem(ROUTE_HOME, DatebookIcons.Home, "Home"),
    TabItem(ROUTE_LISTS, DatebookIcons.List, "Lists"), TabItem(ROUTE_NOTES, DatebookIcons.Note, "Notes"),
    TabItem(ROUTE_DELIBERATE, DatebookIcons.Sparkle, "Deliberate"))
private const val WEATHER_CHECK_MILLIS = 15 * 60_000L  // how often Home asks if the forecast needs refreshing
private const val RELOAD_MILLIS = 5 * 60_000L  // how often Home re-reads the calendar even without a change

// The activity Android starts when you tap the Datebook icon or a prompt
class MainActivity : ComponentActivity() {
    private var promptSuggestion by mutableStateOf<Long?>(null)
    private var openRoute by mutableStateOf<String?>(null)  // a page a notification asked to open
    private var openList by mutableStateOf<String?>(null)  // a list the Lists widget asked to open
    private var screenRoute by mutableStateOf<String?>(null)  // the open screen, so the scene behind can follow it

    // Draw edge to edge and show the app, opening the suggestion a prompt asked for
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        movingSwitch.value = MotionSettings(this).isOn()
        promptSuggestion = suggestionIn(intent)
        openRoute = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
        openList = intent?.getStringExtra(EXTRA_OPEN_LIST)
        setContent {
            DatebookTheme {
                val light = rememberSceneLight()
                val haze = rememberHazeState()
                Box(Modifier.fillMaxSize()) {
                    // The scene behind the open screen (what the glass frosts), fading to the next scene when you move
                    // between screens
                    Crossfade(sceneForRoute(screenRoute), label = "scene") { scene ->
                        SceneBackdrop(scene, light, Modifier.fillMaxSize().hazeSource(haze))
                    }
                    // The screens over the scene, on glass tinted by the light, in the theme's light text
                    CompositionLocalProvider(LocalGlass provides Glass(haze, glassTint(light))) {
                        Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
                            DatebookApp()
                        }
                    }
                }
            }
        }
    }

    // A tapped prompt can reach the activity while it is already open
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        promptSuggestion = suggestionIn(intent) ?: promptSuggestion
        openRoute = intent.getStringExtra(EXTRA_OPEN_ROUTE) ?: openRoute
        openList = intent.getStringExtra(EXTRA_OPEN_LIST) ?: openList
    }

    // Check Gmail each time Datebook comes to the front, so new emails (even ones Gmail didn't notify about) show up
    override fun onStart() {
        super.onStart()

        // Only while signed in to Gmail
        if (GmailSettings(applicationContext).signedIn()) {
            checkGmailNow(applicationContext)
        }
        checkCanvasSoon(applicationContext)  // Canvas too, when a feed link is saved
    }

    // Redraw the widgets when you leave the app, so they show what you just changed
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { updateWidgets(applicationContext) }
    }

    // Return the suggestion id a prompt's intent carries, or null
    private fun suggestionIn(intent: Intent?): Long? {
        val id = intent?.getLongExtra(EXTRA_SUGGESTION_ID, -1) ?: -1
        return if (id >= 0) id else null  // -1 means no suggestion
    }

    // The app's screens and the moves between them
    @Composable
    private fun DatebookApp() {
        val graph = remember { AppGraph.get(applicationContext) }
        val nav = rememberNavController()
        val route = nav.currentBackStackEntryAsState().value?.destination?.route  // read here, so a move recomposes
        SideEffect { screenRoute = route }  // tell the scene behind which screen is open
        val scope = rememberCoroutineScope()
        val waitingFlow = remember { graph.dao.watchSuggestions(Suggestion.STATUS_WAITING) }
        val addedFlow = remember { graph.dao.watchSuggestions(Suggestion.STATUS_ADDED) }
        val waiting by waitingFlow.collectAsState(initial = emptyList())
        val added by addedFlow.collectAsState(initial = emptyList())
        val billsFlow = remember { graph.dao.watchOpenFollowUps(KIND_DUE) }
        val looseFlow = remember { graph.dao.watchOpenFollowUps(KIND_LOOSE) }
        val bills by billsFlow.collectAsState(initial = emptyList())
        val loosePlans by looseFlow.collectAsState(initial = emptyList())
        val assignmentsFlow = remember { graph.dao.watchOpenFollowUps(KIND_ASSIGNMENT) }
        val packagesFlow = remember { graph.dao.watchPackages() }
        val tripPlansFlow = remember { graph.dao.watchTripPlans() }
        val assignments by assignmentsFlow.collectAsState(initial = emptyList())
        val packages by packagesFlow.collectAsState(initial = emptyList())
        val tripPlans by tripPlansFlow.collectAsState(initial = emptyList())
        val packageStore = remember { PackageStore(applicationContext) }
        var received by remember { mutableStateOf(packageStore.received()) }
        var listShown by rememberSaveable { mutableStateOf(LIST_PACKAGES) }
        var chatId by rememberSaveable { mutableLongStateOf(0L) }  // Deliberate's open chat (0 for a new one)
        var deliberateView by rememberSaveable { mutableStateOf(VIEW_CHAT) }
        val openTab: (String) -> Unit = { route ->
            nav.navigate(route) {
                popUpTo(ROUTE_HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        val checkingFlow = remember { graph.dao.watchRetryIds() }
        val checking by checkingFlow.collectAsState(initial = emptyList())
        var status by remember { mutableStateOf(readSetupStatus(applicationContext, graph)) }
        val refresh = { status = readSetupStatus(applicationContext, graph) }
        val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh()
        }
        val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }
        LaunchedEffect(status.calendar) {
            // Reading the calendar is allowed but not writing (Datebook before 1.3.0): ask once, which Android
            // grants without asking you again, so confirmed plans go straight onto the calendar
            if (status.calendar && !canWriteCalendar(applicationContext)) {
                askPermission.launch(Manifest.permission.WRITE_CALENDAR)
            }
        }
        LifecycleResumeEffect(Unit) {
            refresh()  // re-check every step when you come back from Android's settings
            onPauseOrDispose {}
        }
        LaunchedEffect(Unit) {
            graph.pipeline.cleanUp()
            scheduleSummary(applicationContext)  // book the morning summary if it isn't already
            scheduleEvening(applicationContext)  // book the evening weather and bring heads-ups if they aren't already
            scheduleLeaveBy(applicationContext)  // start planning leave-by alerts if it isn't already
            scheduleFlightChecks(applicationContext)  // start the flight checks if they aren't running
            scheduleTrains(applicationContext)  // start the NJ Transit train checks if they aren't running
            scheduleFollowUps(applicationContext)  // start the follow-up nudges and reminders if they aren't running
            scheduleWidgetRefresh(applicationContext)  // start the widget refresh and access check if it isn't running
            scheduleCanvas(applicationContext)  // start the 6-hour Canvas checks while a feed link is saved
            scheduleFencing(applicationContext)  // book the 7 AM fencing search if it isn't booked
            bookNextFollowUp(applicationContext)  // and book the next one due, on time
            graph.offlineDates.prepare()  // download the offline date model the first time there's internet

            // Keep checking Gmail while you're signed in
            if (GmailSettings(applicationContext).signedIn()) {
                scheduleGmailChecks(applicationContext)
            }
        }
        val actions = SetupActions(
            openAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            askNotifications = { askNotifications(askPermission::launch) },
            checkAndSaveKey = { key -> checkAndSaveKey(graph, key, refresh) },
            openBattery = { startActivity(appDetails()) },
            askCalendar = { askPermissions.launch(CALENDAR_PERMISSIONS) },
            saveMapsKey = { key -> graph.mapsKeyStore.save(key); refresh() },
        )
        LaunchedEffect(openList) {
            val list = openList

            // Show the list the Lists widget's row named
            if (list != null) {
                listShown = list
                openList = null
            }
        }
        LaunchedEffect(openRoute) {
            val asked = openRoute

            // Open the page a notification named, like Settings → Gmail
            if (asked != null) {
                nav.navigate(asked)
                openRoute = null
            }
        }
        LaunchedEffect(promptSuggestion) {
            val id = promptSuggestion

            // Open the suggestion a tapped prompt names
            if (id != null) {
                nav.navigate("suggestion/$id")
                promptSuggestion = null
            }
        }
        val add: (Long) -> Unit = { id -> scope.launch { addSuggestion(this@MainActivity, id) } }
        val ignore: (Long) -> Unit = { id -> scope.launch { ignoreSuggestion(applicationContext, id) } }
        val debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val start = if (setupFinished(applicationContext)) ROUTE_HOME else ROUTE_SETUP  // setup until it's finished
        NavHost(nav, startDestination = start) {
            composable(ROUTE_SETUP) {
                SetupScreen(
                    status = status,
                    actions = actions,
                    onSample = { scope.launch { showSamplePrompt(applicationContext) } },
                    onFinish = {
                        markSetupFinished(applicationContext)
                        nav.navigate(ROUTE_HOME) { popUpTo(ROUTE_SETUP) { inclusive = true } }
                    },
                )
            }
            composable(ROUTE_HOME) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                val changesFlow = remember {
                    calendarChanges(applicationContext).runningFold(0) { count, _ -> count + 1 }  // counts changes
                }
                val changes by changesFlow.collectAsState(initial = 0)
                val reload = now / RELOAD_MILLIS
                val events by produceState(emptyList<HomeEvent>(), added, changes, status.calendar, reload) {
                    value = withContext(Dispatchers.IO) { homeEvents(graph, added, now) }
                }
                LaunchedEffect(Unit) {
                    // Keep "now" current while Home is open, so what's on now and the countdowns move on
                    while (true) {
                        delay(TICK_MILLIS)
                        now = System.currentTimeMillis()
                    }
                }
                val zone = ZoneId.systemDefault()
                val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                val todayRemindersFlow = remember(day) {
                    graph.dao.watchRemindersBetween(day.atStartOfDay(zone).toInstant().toEpochMilli(),
                        day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                }
                val todayReminders by todayRemindersFlow.collectAsState(initial = emptyList())
                val problems by produceState(emptyList<String>(), reload, waiting) {
                    value = activeProblems(graph.dao)
                }
                val forecast by produceState<Forecast?>(null, now / WEATHER_CHECK_MILLIS) {
                    value = withContext(Dispatchers.IO) { homeForecast(applicationContext, now, zone) }
                }
                var trip by remember { mutableStateOf(graph.trainSettings.trip()) }
                var trains by remember { mutableStateOf<TrainBoard?>(null) }
                LaunchedEffect(trip) {
                    val saved = trip ?: return@LaunchedEffect  // no card until a trip is saved
                    trains = TrainBoard(saved.first, saved.second, emptyList())

                    // Refresh the board every minute while Home is showing
                    while (true) {
                        trains = withContext(Dispatchers.IO) { nextTrains(graph, saved.first, saved.second) }
                        delay(60_000)
                        val current = graph.trainSettings.trip()

                        // Follow a flip made on the widget
                        if (current != trip) {
                            trip = current
                        }
                    }
                }
                val fencingSettings = remember { FencingSettings(applicationContext) }
                val tournaments = remember(reload, now / TICK_MILLIS) {
                    if (fencingSettings.isOn()) fencingSettings.tournaments() else null  // re-read as Home ticks
                }
                Tabbed(ROUTE_HOME, openTab) {
                    HomeScreen(
                        waiting = waiting,
                        agenda = agendaOf(events, now, zone),
                        today = todayOf(events, todayReminders + assignments + bills, now, zone),
                        forecast = forecast,
                        nowMillis = now,
                        zone = zone,
                        onPaid = { id -> scope.launch { finishFollowUp(graph.dao, id) } },
                        onOpenEvent = { event -> openEvent(nav::navigate, event) },
                        onOpen = { nav.navigate("suggestion/$it") },
                        onAdd = add,
                        onIgnore = ignore,
                        onSettings = { nav.navigate(ROUTE_SETTINGS) },
                        fixes = fixesFor(status, actions, openKeySetup = { nav.navigate(ROUTE_SETUP) }) +
                            problems.filter { it != PROBLEM_NOTIFICATIONS }  // access has its own banner
                                .map { Pair(ALERT_TITLES.getValue(it), { nav.navigate(alertRoute(it)) }) },
                        trains = trains,
                        checking = checking.toSet(),
                        loosePlans = loosePlans,
                        onDropLoose = { id -> scope.launch { graph.dao.setFollowUpStatus(id, FollowUp.DROPPED) } },
                        onOpenChat = { loose -> openApp(loose.app) },
                        fencing = tournaments?.let { fencingTiles(it, now, zone) },
                        fencingEmpty = tournaments?.let { emptyWeekLine(it, day) }.orEmpty(),
                        onOpenLink = { url -> openLink(url) },
                        onFlipTrains = {
                            val saved = trip

                            // Flip the saved trip's direction
                            if (saved != null) {
                                graph.trainSettings.setTrip(saved.second, saved.first)
                                trip = Pair(saved.second, saved.first)
                            }
                        },
                    )
                }
            }
            composable(ROUTE_LISTS) {
                LaunchedEffect(Unit) {
                    received = packageStore.received()  // Deliberate may have marked or un-marked packages meanwhile
                }
                val today = LocalDate.now()
                val content = ListsContent(packages.filter { it.id !in received }, assignments,
                    tripsOf(tripPlans, today), bills, today)
                val listActions = ListsActions(
                    onTrack = { url -> openLink(url) },
                    onGotIt = { id -> packageStore.markReceived(id); received = packageStore.received() },
                    onDone = { id -> scope.launch { finishFollowUp(graph.dao, id) } },
                    onPaid = { id -> scope.launch { finishFollowUp(graph.dao, id) } },
                    onOpenPlan = { id -> nav.navigate("suggestion/$id") },
                    onPlanLink = { id, link ->
                        scope.launch {
                            graph.dao.suggestion(id)?.let { graph.dao.updateSuggestion(it.copy(link = link)) }
                        }
                    },
                    onItemLink = { id, link ->
                        scope.launch { graph.dao.followUp(id)?.let { graph.dao.updateFollowUp(it.copy(link = link)) } }
                    },
                )
                Tabbed(ROUTE_LISTS, openTab) {
                    ListsScreen(listShown, { listShown = it }, content, listActions,
                        onSettings = { nav.navigate(ROUTE_SETTINGS) })
                }
            }
            composable(ROUTE_NOTES) {
                val remindersFlow = remember { graph.dao.watchReminders() }
                val notesFlow = remember { graph.dao.watchNotes() }
                val reminders by remindersFlow.collectAsState(initial = emptyList())
                val notes by notesFlow.collectAsState(initial = emptyList())
                val notesActions = NotesActions(
                    onSave = { text -> graph.pipeline.typed(text) },
                    onDelete = { id -> scope.launch { graph.dao.setFollowUpStatus(id, FollowUp.DROPPED) } },
                    onEditNote = { note, text ->
                        scope.launch {
                            graph.dao.updateFollowUp(note.copy(title = noteTitle(text), body = text.trim()))
                        }
                    },
                )
                Tabbed(ROUTE_NOTES, openTab) {
                    NotesScreen(reminders, notes, ZoneId.systemDefault(), notesActions,
                        onSettings = { nav.navigate(ROUTE_SETTINGS) })
                }
            }
            composable(ROUTE_DELIBERATE) {
                val deliberate = graph.deliberate
                val linesFlow = remember(chatId) { graph.deliberateDao.watchLines(chatId) }
                val chatsFlow = remember { graph.deliberateDao.watchChats() }
                val changesFlow = remember { graph.deliberateDao.watchChanges() }
                val rulesFlow = remember { graph.deliberateDao.watchRules() }
                val lines by linesFlow.collectAsState(initial = emptyList())
                val chats by chatsFlow.collectAsState(initial = emptyList())
                val changeList by changesFlow.collectAsState(initial = emptyList())
                val rules by rulesFlow.collectAsState(initial = emptyList())
                LaunchedEffect(Unit) {
                    deliberate.cleanUp()  // chats and changes over 30 days old go
                }
                val deliberateActions = DeliberateActions(
                    onSend = { text -> chatId = deliberate.send(chatId, text) },
                    onPick = { id, choice -> deliberate.pick(id, choice) },
                    onConfirm = { id -> deliberate.confirm(id) },
                    onCancel = { id -> deliberate.cancel(id) },
                    onUndo = { id -> deliberate.undo(id) },
                    onRetry = { id -> deliberate.retry(id) },
                    onRuleOn = { rule, on -> deliberate.setRuleOn(rule, on) },
                    onDeleteRule = { id -> deliberate.deleteRule(id) },
                    onAddJourney = { id -> deliberate.addJourney(id) },
                    onOpenChat = { id -> chatId = id },
                    onNewChat = { chatId = 0 },
                )
                Tabbed(ROUTE_DELIBERATE, openTab) {
                    DeliberateScreen(deliberateView, { deliberateView = it }, lines, chats, changeList, rules,
                        deliberateActions, onSettings = { nav.navigate(ROUTE_SETTINGS) })
                }
            }
            composable(ROUTE_SUGGESTION, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: -1
                val suggestion by produceState<Suggestion?>(null, id) { value = graph.dao.suggestion(id) }
                val guessText by produceState<String?>(null, suggestion, checking) {
                    val retryId = suggestion?.retryId ?: 0
                    value = if (retryId == 0L) null else graph.dao.retryText(retryId)?.text  // guesses only
                }
                PlanCard(graph, suggestion, onBack = { nav.popBackStack() },
                    onAdd = { add(id); nav.popBackStack() },
                    onIgnore = { ignore(id); nav.popBackStack() },
                    guessText = guessText)
            }
            composable(ROUTE_EVENT, arguments = listOf(navArgument("eventId") { type = NavType.LongType },
                navArgument("begin") { type = NavType.LongType })) { entry ->
                val eventId = entry.arguments?.getLong("eventId") ?: -1
                val begin = entry.arguments?.getLong("begin") ?: -1
                val plan by produceState<Suggestion?>(null, eventId, begin, added) {
                    value = withContext(Dispatchers.IO) { calendarPlan(graph, eventId, begin, added) }
                }
                PlanCard(graph, plan, onBack = { nav.popBackStack() }, onAdd = {}, onIgnore = {},
                    onOpenCalendar = { plan?.let { openCalendarEvent(it) } })
            }
            composable(ROUTE_WEEK) {
                val week = remember { WeekAheadSettings(applicationContext) }
                val now = remember { System.currentTimeMillis() }
                val zone = ZoneId.systemDefault()
                val events by produceState(emptyList<HomeEvent>(), added) {
                    value = withContext(Dispatchers.IO) { homeEvents(graph, added, now) }
                }
                WeekAheadScreen(week.brief(), weekDays(events, LocalDate.now(zone), zone), bills, loosePlans, zone,
                    onBack = { nav.popBackStack() },
                    onOpenEvent = { event -> openEvent(nav::navigate, event) },
                    onOpenChat = { loose -> openApp(loose.app) })
            }
            composable(ROUTE_SETTINGS) {
                SettingsMenu(items = settingsItems(nav::navigate), onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_APPS) {
                val switches = graph.appSwitches
                AppsPage(WATCHABLE_APPS, switches::isOn, switches::set, onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_CLAUDE) {
                var model by remember { mutableStateOf(graph.modelChoice.get()) }
                ClaudePage(
                    maskedKey = graph.keyStore.load()?.let { maskKey(it) },
                    model = model,
                    onModel = { graph.modelChoice.set(it); model = it },
                    checkAndSaveKey = { key -> graph.keepGoing { checkAndSaveKey(graph, key, refresh) } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(ROUTE_SETTINGS_CALENDARS) {
                val calendars = remember(status.calendar) { phoneCalendars(applicationContext) }  // again once allowed
                val switches = graph.calendarSwitches
                val writable = remember(status.calendar) { writableCalendars(applicationContext) }
                val firstWriteTo = remember { writeCalendarId(applicationContext, switches.writeCalendar()) }
                var writeTo by remember { mutableStateOf(firstWriteTo) }
                CalendarsPage(status.calendar, calendars, switches::isOn, switches::set, actions.askCalendar,
                    onBack = { nav.popBackStack() }, writable = writable, writeTo = writeTo,
                    setWriteTo = { switches.setWriteCalendar(it); writeTo = it })
            }
            composable(ROUTE_SETTINGS_PLACES) {
                val travel = graph.travelSettings
                var mode by remember { mutableStateOf(travel.mode()) }
                PlacesPage(
                    maskedKey = graph.mapsKeyStore.load()?.let { maskKey(it) },
                    saveKey = { key -> graph.mapsKeyStore.save(key); refresh() },
                    mode = mode,
                    onMode = { travel.setMode(it); mode = it },
                    home = travel.home(),
                    saveHome = { travel.setHome(it) },
                    location = status.location,
                    askLocation = { askPermissions.launch(LOCATION_PERMISSIONS) },
                    allTheTime = status.backgroundLocation,
                    askAllTheTime = { askAllTheTime(status, askPermissions::launch, askPermission::launch) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(ROUTE_SETTINGS_FLIGHTS) {
                FlightsPage(
                    maskedKey = graph.flightsKeyStore.load()?.let { maskKey(it) },
                    saveKey = { key -> graph.flightsKeyStore.save(key) },
                    njtUser = graph.njtUserStore.load(),
                    saveNjt = { user, password ->
                        graph.njtUserStore.save(user)
                        graph.njtPasswordStore.save(password)
                    },
                    stations = graph.trainSettings.stations(),
                    trip = graph.trainSettings.trip(),
                    setTrip = { from, to -> graph.trainSettings.setTrip(from, to) },
                    loadStations = {
                        graph.keepGoing {
                            withContext(Dispatchers.IO) {
                                refreshStations(graph.trainSettings, graph.railData, System.currentTimeMillis())
                                graph.trainSettings.stations()
                            }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(ROUTE_SETTINGS_GMAIL) {
                val gmail = remember { GmailSettings(applicationContext) }
                var signedIn by remember { mutableStateOf(gmail.signedIn()) }
                var address by remember { mutableStateOf(gmail.address()) }
                var lapsed by remember { mutableStateOf(gmail.lapsed()) }
                var problem by remember { mutableStateOf("") }
                val finish: (AuthorizationResult) -> Unit = { result ->
                    scope.launch {
                        problem = finishGmailSignIn(gmail, result)
                        signedIn = gmail.signedIn()
                        address = gmail.address()
                        lapsed = gmail.lapsed()
                    }
                }
                val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                    // Google's sign-in screen came back: finish signing in, or say it didn't work
                    try {
                        val client = Identity.getAuthorizationClient(this@MainActivity)
                        finish(client.getAuthorizationResultFromIntent(it.data))
                    } catch (error: Exception) {  // cancelled, or Google refused
                        problem = "Sign-in didn't finish. Try again."
                    }
                }
                GmailPage(signedIn, address, lapsed, problem, graph.appSwitches.isOn(GMAIL_APP),
                    onSignIn = {
                        problem = ""
                        Identity.getAuthorizationClient(this@MainActivity).authorize(gmailRequest())
                            .addOnSuccessListener { result ->
                                val screen = result.pendingIntent

                                // Google shows its sign-in screen the first time, and after the sign-in lapses
                                if (result.hasResolution() && screen != null) {
                                    consent.launch(IntentSenderRequest.Builder(screen.intentSender).build())
                                } else {
                                    finish(result)
                                }
                            }
                            .addOnFailureListener { problem = "Couldn't reach Google: ${it.message}" }
                    },
                    onSignOut = {
                        gmail.signOut()
                        stopGmailChecks(applicationContext)
                        signedIn = false
                        address = ""
                        lapsed = false
                    },
                    onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_NOTIFICATIONS) {
                val summary = graph.summarySettings
                val week = remember { WeekAheadSettings(applicationContext) }
                NotificationsPage(status.access, status.notifications, actions.openAccess,
                    openNotifications = { startActivity(appNotificationSettings()) },
                    summaryOn = summary.isOn(), summaryHour = summary.hour(), summaryMinute = summary.minute(),
                    setSummary = { on, hour, minute ->
                        summary.set(on, hour, minute)
                        scheduleSummary(applicationContext, ExistingWorkPolicy.REPLACE)  // re-book at the new time
                    },
                    weekOn = week.isOn(), weekHour = week.hour(), weekMinute = week.minute(),
                    setWeekAhead = { on, hour, minute -> week.set(on, hour, minute) },
                    onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_LOOK) {
                val settings = remember { MotionSettings(applicationContext) }
                var moving by remember { mutableStateOf(settings.isOn()) }
                LookPage(moving, setMoving = { on -> settings.set(on); moving = on }, onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_FENCING) {
                val fencing = remember { FencingSettings(applicationContext) }
                var on by remember { mutableStateOf(fencing.isOn()) }
                val searchingFlow = remember { fencingSearching(applicationContext) }
                val searching by searchingFlow.collectAsState(initial = false)
                var searched by remember { mutableStateOf(fencing.lastSearch()) }
                var found by remember { mutableStateOf(fencing.lastResult()) }
                LaunchedEffect(searching) {
                    // Show the result once a search finishes
                    if (!searching) {
                        searched = fencing.lastSearch()
                        found = fencing.lastResult()
                    }
                }
                FencingPage(on, searching, if (searched > 0) "Last searched ${whenText(searched)}" else "", found,
                    setOn = { turnOn ->
                        fencing.setOn(turnOn)
                        on = turnOn
                        scheduleFencing(applicationContext)
                    },
                    onSearch = { searchInBackground(applicationContext) },
                    ftlEmail = FtlAccount(applicationContext).email(),
                    saveFtl = { email, password ->
                        FtlAccount(applicationContext).save(email, password)
                        lookUpFtlSoon(applicationContext)  // find the exact Results pages right away
                    },
                    onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_NESTOR) {
                val nestor = remember { NestorSettings(applicationContext) }
                val link by NestorLink.status.collectAsState()
                var on by remember { mutableStateOf(nestor.isOn()) }
                var canReach by remember { mutableStateOf(hasLocalNetwork(applicationContext)) }
                val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    canReach = hasLocalNetwork(applicationContext)
                    NestorLink.restart(applicationContext)
                }
                LaunchedEffect(Unit) {
                    NestorLink.start(applicationContext)  // look for Nestor while the page is open
                }
                NestorPage(link.address, link.paired,
                    if (link.lastContact > 0) "Last heard from Nestor ${whenText(link.lastContact)}" else "",
                    link.problem, on, canReach, hasNotificationAccess(applicationContext),
                    askPermission = { askLocal.launch(LOCAL_NETWORK_PERMISSION) },
                    onPair = { code -> graph.keepGoing { NestorLink.pair(applicationContext, code) } },
                    onUnpair = { graph.keepGoing { NestorLink.unpair(applicationContext) } },
                    setOn = { turnOn ->
                        nestor.setOn(turnOn)
                        on = turnOn
                        NestorLink.restart(applicationContext)
                    },
                    onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_SCHOOL) {
                val canvas = remember { CanvasSettings(applicationContext) }
                var saved by remember { mutableStateOf(canvas.link() != null) }
                var checked by remember { mutableStateOf(canvas.lastChecked()) }
                var found by remember { mutableStateOf(canvas.lastResult()) }
                val reread = {
                    saved = canvas.link() != null
                    checked = canvas.lastChecked()
                    found = canvas.lastResult()
                }
                SchoolPage(saved, if (checked > 0) "Last checked ${whenText(checked)}" else "", found,
                    onSave = { link -> graph.keepGoing { saveCanvasLink(canvas, link) }.also { reread() } },
                    onCheck = { graph.keepGoing { resultText(checkCanvas(applicationContext)) }.also { reread() } },
                    onRemove = {
                        canvas.remove()
                        scheduleCanvas(applicationContext)
                        reread()
                    },
                    onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_PRIVACY) {
                var keepTexts by remember { mutableStateOf(graph.deliberateSettings.keepTexts()) }
                PrivacyPage(clearHistory = { graph.pipeline.clearHistory() }, onBack = { nav.popBackStack() },
                    keepTexts = keepTexts,
                    setKeepTexts = { on -> graph.deliberateSettings.setKeepTexts(on); keepTexts = on },
                    deleteTexts = {
                        graph.dao.clearPlanMessages()
                        graph.dao.clearFollowUpMessages()
                    })
            }
            composable(ROUTE_SETTINGS_AUTOMATIC) {
                val apps = WATCHABLE_APPS.filter { graph.appSwitches.isOn(it.packageName) }.map { it.label }
                AutomaticPage(status.automatic, apps,
                    openSettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_ABOUT) {
                val pretend: suspend (String, String) -> List<String> = { text, sender ->
                    pretendText(graph, text, sender)
                }
                AboutPage(
                    version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                    openGitHub = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) },
                    pretend = if (debugBuild) pretend else null,  // debug builds only
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }

    // One of the tabbed screens: the screen above, the glass tab bar along the bottom (which takes the space of
    // Android's navigation bar, so the screen doesn't leave room for it twice)
    @Composable
    private fun Tabbed(route: String, openTab: (String) -> Unit, screen: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
                screen()
            }
            TabBar(MAIN_TABS, route, openTab)
        }
    }

    // A plan's card (a suggestion, or a calendar event shown as one) with its travel modes and how early to be there,
    // live trains on its day, flight status (with when to be at the airport) and buttons; changes to the travel mode,
    // early minutes or train are remembered for its leave-by alert
    @Composable
    private fun PlanCard(
        graph: AppGraph,
        plan: Suggestion?,
        onBack: () -> Unit,
        onAdd: () -> Unit,
        onIgnore: () -> Unit,
        guessText: String? = null,
        onOpenCalendar: (() -> Unit)? = null,
    ) {
        var travelMode by remember(plan) { mutableStateOf(plan?.let { modeFor(graph, it) }) }
        var trainChoices by remember { mutableStateOf<TrainChoices?>(null) }
        var trainRefresh by remember { mutableIntStateOf(0) }
        var early by remember(plan) { mutableStateOf<Int?>(null) }
        var journey by remember(plan) { mutableStateOf<Journey?>(null) }
        var picking by remember(plan) { mutableStateOf(false) }  // the train picker is open
        var choices by remember(plan) { mutableStateOf<List<Journey>?>(null) }  // null while they load
        var flightOpen by remember(plan) { mutableStateOf(false) }
        LaunchedEffect(picking) {
            val shown = plan ?: return@LaunchedEffect
            val current = journey ?: return@LaunchedEffect

            // Look for the other trains each time the picker opens
            if (picking) {
                choices = null
                choices = withContext(Dispatchers.IO) { otherTrains(graph, shown, current, early ?: 0) }
            }
        }
        LaunchedEffect(plan) {
            val shown = plan ?: return@LaunchedEffect
            early = earlyFor(applicationContext, shown.title, shown.location, planBegin(shown))
        }
        LaunchedEffect(plan, travelMode, early, trainRefresh) {
            val shown = plan ?: return@LaunchedEffect
            journey = withContext(Dispatchers.IO) { journeyFor(graph, shown, travelMode, early ?: 0) }
        }
        LaunchedEffect(plan, travelMode, trainRefresh) {
            val shown = plan ?: return@LaunchedEffect
            trainChoices = trainsBefore(graph, shown)

            // Refresh the live list every minute on the plan's day
            while (showsTrains(graph, shown) && isPlanDay(shown)) {
                trainChoices = withContext(Dispatchers.IO) { trainChoices(applicationContext, shown) }
                delay(60_000)
            }
        }
        SuggestionScreen(
            suggestion = plan,
            onBack = onBack,
            onAdd = onAdd,
            onIgnore = onIgnore,
            onOpenMaps = { place -> openMaps(place, travelMode ?: graph.travelSettings.mode()) },
            onTrack = { url -> openLink(url) },
            flightStatus = plan?.let { flightStatusFor(graph, it) },
            beThere = plan?.let { shown -> early?.let { beThereFor(shown, it) } },
            journey = journey,
            onJourneyStep = { step ->
                val shown = plan ?: return@SuggestionScreen

                // Walks and drives open Maps for that part; rides open the train picker; boarding and the flight
                // open its details
                if (step.kind == STEP_WALK || step.kind == STEP_DRIVE || step.kind == STEP_BIKE) {
                    openMapsLeg(step)
                } else if (step.kind == STEP_BOARD || step.kind == STEP_FLIGHT) {
                    flightOpen = isFlight(shown)
                } else {
                    picking = true
                }
            },
            early = early?.takeIf { plan?.location?.isNotBlank() == true },
            earlyHint = plan?.let { earlyHintFor(it) },
            onEarly = { minutes ->
                val shown = plan ?: return@SuggestionScreen
                LeaveByStore(applicationContext).setEarly(eventKey(shown.title, planBegin(shown)), minutes)
                early = minutes
                trainRefresh += 1
                lifecycleScope.launch(Dispatchers.IO) { planLeaveBy(applicationContext) }  // book the alert again
            },
            trains = trainChoices,
            guessText = guessText,
            onOpenCalendar = onOpenCalendar,
            onPickTrain = { row ->
                val shown = plan ?: return@SuggestionScreen
                val key = eventKey(shown.title, planBegin(shown))
                graph.trainStore.setChoice(key, row.pick.trainId)
                graph.trainStore.setPick(key, row.pick)
                LeaveByStore(applicationContext).setTrainNote(key, "", "")  // your own pick: no "was cancelled" note
                trainRefresh += 1
            },
            onSwitchOrigin = { code ->
                val shown = plan ?: return@SuggestionScreen
                graph.trainStore.setOrigin(eventKey(shown.title, planBegin(shown)), code)
                trainRefresh += 1
            },
            travelMode = travelMode,
            onTravelMode = { mode ->
                val shown = plan ?: return@SuggestionScreen
                LeaveByStore(applicationContext).setMode(eventKey(shown.title, planBegin(shown)), mode)
                travelMode = mode
            },
        )
        val shown = plan ?: return
        val current = journey
        val (flying, flight) = remember(shown) { flightFor(applicationContext, shown.title, planBegin(shown)) }

        // The train picker, while it's open
        if (picking && current != null) {
            val beThere = planBegin(shown) - leadMinutes(early ?: 0, flying, flight) * 60_000L
            TrainPickerDialog(choices, current, beThere, onPick = { picked ->
                pickJourney(graph, shown, picked)
                journey = picked
                picking = false
                trainRefresh += 1
            }, onDismiss = { picking = false })
        }

        // The flight's details, while they're open
        if (flightOpen) {
            val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            val at = { instant: Instant? -> instant?.atZone(ZoneId.systemDefault())?.format(clock) ?: "not known" }
            val departs = flight?.departScheduled ?: flight?.departBest
            val number = flight?.number ?: shown.flight
            val lines = listOf(
                "Terminal · gate" to listOf(flight?.terminal, flight?.gate).filter { !it.isNullOrBlank() }
                    .joinToString(" · ").ifBlank { "not posted yet" },
                "Boarding" to "about " + at(departs?.minusSeconds(boardingMinutes(flight) * 60L)),
                "Departs" to at(flight?.departBest ?: departs),
                "Lands" to at(flight?.arriveBest ?: flight?.arriveScheduled) +
                    (flight?.toCity?.ifBlank { null }?.let { " · $it" } ?: ""))
            val track = trackLinkFor(shown.flight, "", "")
            FlightDialog(shown.title, flight?.let { statusLine(it) } ?: "No live status yet", lines,
                onCheckIn = { openLink(checkInLink(number)) }, onTrack = track?.let { link -> { openLink(link.url) } },
                onDismiss = { flightOpen = false })
        }
    }

    // Home's events: the next month of your switched-on calendars, matched to the plans you added, or without calendar
    // access the plans you added that are still ahead (call it off the main thread)
    private fun homeEvents(graph: AppGraph, added: List<Suggestion>, nowMillis: Long): List<HomeEvent> {
        // Fall back to Datebook's own plans when the calendar can't be read
        if (!hasCalendarAccess(applicationContext)) {
            return fromPlans(upcoming(added, nowMillis))
        }
        val events = homeCalendarEvents(applicationContext, nowMillis, graph.calendarSwitches.switchedOff())
        return fromCalendar(events, added)
    }

    // Open a Home event's card: a calendar event by its id and start, or a Datebook plan when the calendar can't be
    // read
    private fun openEvent(navigate: (String) -> Unit, event: HomeEvent) {
        // Plans without a calendar event open as the plan itself
        if (event.eventId == 0L) {
            event.source?.let { navigate("suggestion/${it.id}") }
            return
        }
        navigate("event/${event.eventId}/${event.begin}")
    }

    // Read one calendar event (this time of it, for repeating events) as a plan for its card, with the Datebook plan
    // it came from; null when it's gone (call it off the main thread)
    private fun calendarPlan(graph: AppGraph, eventId: Long, begin: Long, added: List<Suggestion>): Suggestion? {
        val event = calendarEvents(applicationContext, begin - 1, begin + 1, emptySet())
            .firstOrNull { it.eventId == eventId && it.begin == begin } ?: return null  // deleted or moved away
        return planOf(fromCalendar(listOf(event), added).single(), ZoneId.systemDefault())
    }

    // Open a calendar event (this time of it) in your calendar app, where you can change or delete it
    private fun openCalendarEvent(plan: Suggestion) {
        val (begin, end) = eventTimes(plan.toEvent())
        val uri = android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, plan.calendarEventId)
        val intent = Intent(Intent.ACTION_VIEW, uri).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)

        // Open the calendar app, or say there isn't one
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {  // no calendar app installed
            Toast.makeText(this, "No calendar app found", Toast.LENGTH_LONG).show()
        }
    }

    // Finish signing in to Gmail: from the first sign-in only new mail is read, after a lapse the mail that arrived
    // meanwhile is read too; then the checks start; returns what went wrong ("" when nothing did)
    private suspend fun finishGmailSignIn(gmail: GmailSettings, result: AuthorizationResult): String {
        val token = result.accessToken ?: return "Google didn't allow reading Gmail. Try again."

        // A first sign-in reads mail from now on; a renewed one keeps its place
        if (!gmail.signedIn()) {
            gmail.signIn(System.currentTimeMillis() / 1000)
        }
        gmail.setLapsed(false)
        val address = withContext(Dispatchers.IO) { runCatching { gmailAddress(token) }.getOrDefault("") }
        gmail.setAddress(address)
        scheduleGmailChecks(applicationContext)
        checkGmailNow(applicationContext)
        return ""
    }

    // Save a Canvas feed link if it looks right and Canvas answers with a calendar, then check it and start the 6-hour
    // checks; returns what happened in words
    private suspend fun saveCanvasLink(canvas: CanvasSettings, link: String): String {
        // Only links that look like a Canvas feed
        if (!looksLikeFeed(link)) {
            return "That doesn't look like a Canvas Calendar Feed link (it ends in .ics)."
        }
        val problem = withContext(Dispatchers.IO) {
            runCatching { downloadFeed(link) }.exceptionOrNull()
        }

        // Keep the old link when the new one doesn't work
        if (problem != null) {
            return problem.message?.takeIf { problem is IllegalStateException } ?: "Couldn't reach Canvas. Try again."
        }
        canvas.setLink(link)
        scheduleCanvas(applicationContext)
        return "Saved. " + resultText(checkCanvas(applicationContext))
    }

    // Ask for notification permission (Android 13 and later), or open Datebook's notification settings if it was
    // turned off there, since Android then won't ask again
    private fun askNotifications(launch: (String) -> Unit) {
        // Older Android versions have no permission pop-up
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,
                packageName))
            return
        }
        launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Check a Claude key and save it only if it works; returns the problem, or null once it is saved
    private suspend fun checkAndSaveKey(graph: AppGraph, key: String, refresh: () -> Unit): String? {
        val problem = checkClaudeKey(key)

        // Save keys that work, then re-check the setup steps
        if (problem == null) {
            graph.keyStore.save(key)
            resetClaudeProblems(applicationContext, graph.dao)  // a new key starts the Claude alerts over
            refresh()
        }
        return problem
    }

    // The Settings menu's rows, each with its glass tile icon and opening its page
    private fun settingsItems(open: (String) -> Unit): List<SettingsItem> {
        return listOf(
            SettingsItem(DatebookIcons.Apps, TILE_BLUE, "Apps to watch", "Which apps Datebook reads texts from") {
                open(ROUTE_SETTINGS_APPS)
            },
            SettingsItem(DatebookIcons.Sparkle, TILE_AQUA, "Claude & API key", "Your key and which model to use") {
                open(ROUTE_SETTINGS_CLAUDE)
            },
            SettingsItem(DatebookIcons.Calendar, TILE_BLUE, "Calendars", "Which calendars to check for clashes") {
                open(ROUTE_SETTINGS_CALENDARS)
            },
            SettingsItem(DatebookIcons.Pin, TILE_LEAF, "Places & travel", "Maps key, how you get around, home") {
                open(ROUTE_SETTINGS_PLACES)
            },
            SettingsItem(DatebookIcons.Plane, TILE_BLUE, "Flights & trains", "Live flight times and NJ Transit") {
                open(ROUTE_SETTINGS_FLIGHTS)
            },
            SettingsItem(DatebookIcons.Mail, TILE_AQUA, "Gmail", "Plans from your emails") {
                open(ROUTE_SETTINGS_GMAIL)
            },
            SettingsItem(DatebookIcons.Book, TILE_LEAF, "School", "Assignments and classes from Canvas") {
                open(ROUTE_SETTINGS_SCHOOL)
            },
            SettingsItem(DatebookIcons.Sparkle, TILE_BLUE, "Fencing", "Men's foil tournaments to watch") {
                open(ROUTE_SETTINGS_FENCING)
            },
            SettingsItem(DatebookIcons.Home, TILE_AQUA, "Nestor", "Your home speaker and wall screen") {
                open(ROUTE_SETTINGS_NESTOR)
            },
            SettingsItem(DatebookIcons.Bell, TILE_WARN, "Notifications & alerts", "Reading texts and showing prompts") {
                open(ROUTE_SETTINGS_NOTIFICATIONS)
            },
            SettingsItem(DatebookIcons.Picture, TILE_LEAF, "Look", "Moving backgrounds") {
                open(ROUTE_SETTINGS_LOOK)
            },
            SettingsItem(DatebookIcons.Lock, TILE_LEAF, "Privacy & history", "What's kept, and clearing history") {
                open(ROUTE_SETTINGS_PRIVACY)
            },
            SettingsItem(DatebookIcons.Auto, TILE_AQUA, "Automatic mode",
                "Catch plans in texts you send and open chats") {
                open(ROUTE_SETTINGS_AUTOMATIC)
            },
            SettingsItem(DatebookIcons.Info, TILE_BLUE, "About Datebook", "Version, GitHub and privacy") {
                open(ROUTE_SETTINGS_ABOUT)
            },
        )
    }

    // Debug builds only: run a typed text through Datebook as if it had arrived, and return what happened
    private suspend fun pretendText(graph: AppGraph, text: String, sender: String): List<String> {
        val outcome = graph.pipeline.handle(IncomingText(sender, text, LocalDateTime.now(), "simulated"))
        return outcome.lines + outcome.events.map { "prompt: ${summary(it)}" }  // what a prompt would say
    }

    // Android's notification settings for Datebook, where prompts can be turned back on
    private fun appNotificationSettings(): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }

    // Android's page for Datebook, where Samsung's Battery → Unrestricted setting lives
    private fun appDetails(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    }

    // A flight plan's last-known status line, like "On time · departs 6:45 PM · gate C12" (null for other plans)
    private fun flightStatusFor(graph: AppGraph, plan: Suggestion): String? {
        // Only flight plans have a status
        if (plan.flight.isBlank()) {
            return null
        }
        return graph.flightStore.info(plan.flight, plan.start)?.let { statusLine(it) }
    }

    // The other ways to make a plan's trip for the train picker (see journeyChoices); off the main thread
    private fun otherTrains(graph: AppGraph, plan: Suggestion, current: Journey, early: Int): List<Journey> {
        val begin = planBegin(plan)
        val lead = leadMinutes(early, isFlight(plan), flightFor(applicationContext, plan.title, begin).second)
        return journeyChoices(applicationContext, graph.mapsKeyStore.load(), graph.travelSettings.home(), plan.location,
            plan.location.substringBefore(","), current.mode, begin - lead * 60_000L, current)
    }

    // Keep the train you picked for a plan: the journey (kept as picked, so it's refreshed leaving at the same time)
    // and, for NJ Transit, the train's number, so the day's live pick and the leave-by alert follow it
    private fun pickJourney(graph: AppGraph, plan: Suggestion, picked: Journey) {
        val key = eventKey(plan.title, planBegin(plan))
        JourneyStore(applicationContext).save(key, picked, System.currentTimeMillis(), picked = true)
        val number = picked.steps.firstOrNull { it.trainId.isNotBlank() }?.trainId

        // The train's number, when Google gave it
        if (number != null) {
            graph.trainStore.setChoice(key, number)
        }
        lifecycleScope.launch(Dispatchers.IO) { planLeaveBy(applicationContext) }
    }

    // Open Google Maps for one walk or drive of a trip, from where it starts (or where you are) to where it ends
    private fun openMapsLeg(step: JourneyStep) {
        // Maps' mode for the kind of step
        val mode = when (step.kind) {
            STEP_DRIVE -> TRAVEL_DRIVE
            STEP_BIKE -> TRAVEL_BIKE
            else -> TRAVEL_WALK
        }

        // Open the maps app, or say there isn't one
        try {
            startActivity(mapsIntent(step.toName, mode, step.fromName))
        } catch (error: ActivityNotFoundException) {  // no maps app or browser installed
            Toast.makeText(this, "No maps app found", Toast.LENGTH_LONG).show()
        }
    }

    // A plan's journey for its card: the kept one, worked out again when it was kept for another mode or (on the plan's
    // day) over 10 minutes ago, with the picked NJ Transit train's live state; null for plans without a place, changes
    // and plans already past (call it off the main thread)
    private fun journeyFor(graph: AppGraph, plan: Suggestion, mode: String?, early: Int): Journey? {
        val begin = planBegin(plan)
        val now = System.currentTimeMillis()

        // Only plans you still have to get to
        if (plan.location.isBlank() || isChange(plan) || begin <= now) {
            return null
        }
        val key = eventKey(plan.title, begin)
        val wanted = mode ?: graph.travelSettings.mode()
        val store = JourneyStore(applicationContext)
        val kept = store.journey(key)?.takeIf { it.first.mode == journeyMode(wanted) }  // another mode plans again
        val stale = kept == null || (isPlanDay(plan) && now - kept.second > 10 * 60_000)
        val (flying, flight) = flightFor(applicationContext, plan.title, begin)
        val picked = kept != null && store.picked(key)  // a picked train is kept by leaving at the same time
        val planned = if (!stale) kept!!.first else planJourney(applicationContext, graph.mapsKeyStore.load(),
            graph.travelSettings.home(), plan.location, plan.location.substringBefore(","), wanted,
            if (picked) kept!!.first.leave else null,
            if (picked) null else begin - leadMinutes(early, isFlight(plan), flight) * 60_000L)
            ?.also { store.save(key, it, now, picked) } ?: kept?.first ?: return null
        val gate = listOfNotNull(flight?.terminal?.ifBlank { null }?.let { "Terminal $it" },
            flight?.gate?.ifBlank { null }?.let { "gate $it" }).joinToString(", ")
        val journey = if (!flying) planned else withFlightSteps(planned, flight?.number ?: plan.flight,
            (flight?.departScheduled ?: flight?.departBest)?.toEpochMilli() ?: begin, boardingMinutes(flight), gate,
            (flight?.arriveBest ?: flight?.arriveScheduled)?.toEpochMilli(), flight?.toCity.orEmpty(),
            ZoneId.systemDefault())
        val pick = graph.trainStore.pick(key) ?: return journey
        return withLiveTrain(journey, pick.trainId, pick.track, pick.secondsLate / 60,
            pick.status.contains("cancel", ignoreCase = true))
    }

    // Return true if a plan is a flight ("Flight B61234 EWR → BOS", or one with a flight number)
    private fun isFlight(plan: Suggestion): Boolean {
        return plan.flight.isNotBlank() || isFlightTitle(plan.title)
    }

    // A flight's "Be at the airport by 5:35 PM" line (null for other plans)
    private fun beThereFor(plan: Suggestion, early: Int): String? {
        // Only flights
        if (!isFlight(plan)) {
            return null
        }
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val (_, flight) = flightFor(applicationContext, plan.title, planBegin(plan))
        val at = Instant.ofEpochMilli(planBegin(plan) - leadMinutes(early, true, flight) * 60_000L)
            .atZone(ZoneId.systemDefault())
        val boards = Instant.ofEpochMilli(planBegin(plan) - boardingMinutes(flight) * 60_000L)
            .atZone(ZoneId.systemDefault())
        return "Be at the airport by ${at.format(clock)} · boarding about ${boards.format(clock)}"
    }

    // Under the stepper for flights still on the default: where the minutes come from (null otherwise)
    private fun earlyHintFor(plan: Suggestion): String? {
        val chosen = LeaveByStore(applicationContext).early(eventKey(plan.title, planBegin(plan)))
        return if (!isFlight(plan)) null else (if (chosen == null) "Flights start at $FLIGHT_EARLY_MINUTES min. " else
            "") + "For flights this counts from boarding (about $BOARDING_MINUTES min before departure)."
    }

    // Return true if a plan gets NJ Transit trains: it goes by transit, has a place, and an NJ Transit account is saved
    private suspend fun showsTrains(graph: AppGraph, plan: Suggestion): Boolean {
        return !graph.njtUserStore.load().isNullOrBlank() && wantsTrain(applicationContext, plan)
    }

    // Return true if a plan is today, when its trains are on NJ Transit's live board
    private fun isPlanDay(plan: Suggestion): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(planBegin(plan)).atZone(zone).toLocalDate() == LocalDate.now(zone)
    }

    // What a plan's train section shows before the live list loads: nothing for other plans, "Trains show on the day"
    // before its day, or "Loading trains…"
    private suspend fun trainsBefore(graph: AppGraph, plan: Suggestion): TrainChoices? {
        // Pick the waiting message for the plan
        return when {
            !showsTrains(graph, plan) -> null
            !isPlanDay(plan) -> TrainChoices(null, emptyList(), null, emptyList(), ON_THE_DAY)
            else -> TrainChoices(null, emptyList(), null, emptyList(), "Loading trains…")
        }
    }

    // The travel mode picked for a plan, or the default
    private fun modeFor(graph: AppGraph, plan: Suggestion): String {
        val picked = LeaveByStore(applicationContext).mode(eventKey(plan.title, planBegin(plan)))
        return picked ?: graph.travelSettings.mode()
    }

    // When a plan starts, in milliseconds, matching the calendar event it becomes
    private fun planBegin(plan: Suggestion): Long {
        return eventTimes(plan.toEvent()).first
    }

    // Ask for location while Datebook is in use first, then "Allow all the time" (Android shows that one in Settings)
    private fun askAllTheTime(status: SetupStatus, askMany: (Array<String>) -> Unit, askOne: (String) -> Unit) {
        // Location in use has to be allowed before all the time
        if (!status.location) {
            askMany(LOCATION_PERMISSIONS)
        } else {
            askOne(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    // Open the app a loose plan came from (its chat list), telling you if it isn't installed
    private fun openApp(app: String) {
        val launch = packageManager.getLaunchIntentForPackage(app)

        // Open the app, or say it can't be opened
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "Can't open that app", Toast.LENGTH_LONG).show()
        }
    }

    // Open a web page, like a package's tracking page, telling you if nothing can open it
    private fun openLink(url: String) {
        // Open the browser, or say there isn't one
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: ActivityNotFoundException) {  // no browser installed
            Toast.makeText(this, "No app found to open the link", Toast.LENGTH_LONG).show()
        }
    }

    // Start directions to a place in Google Maps, telling you if no maps app can open it
    private fun openMaps(place: String, mode: String) {
        // Open the maps app, or say there isn't one
        try {
            startActivity(mapsIntent(place, mode))
        } catch (error: ActivityNotFoundException) {  // no maps app or browser installed
            Toast.makeText(this, "No maps app found", Toast.LENGTH_LONG).show()
        }
    }

    // A banner for each required setting that's off, each with what fixes it
    private fun fixesFor(status: SetupStatus, actions: SetupActions, openKeySetup: () -> Unit):
        List<Pair<String, () -> Unit>> {
        val fixes = mutableListOf<Pair<String, () -> Unit>>()

        // Notification access lets Datebook read texts
        if (!status.access) {
            fixes.add(Pair("Notification access is off", actions.openAccess))
        }

        // Notifications let Datebook show its prompts
        if (!status.notifications) {
            fixes.add(Pair("Datebook can't show prompts", actions.askNotifications))
        }

        // Without a key nothing reaches Claude
        if (!status.key) {
            fixes.add(Pair("No Claude API key saved", openKeySetup))
        }

        // Without calendar access, Home only shows the plans added through Datebook
        if (!status.calendar) {
            fixes.add(Pair("Allow calendar to see your whole schedule", actions.askCalendar))
        }
        return fixes
    }
}
