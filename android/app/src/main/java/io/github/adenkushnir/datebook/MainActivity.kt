// Datebook's main screen: setup until it's finished, then Home, a suggestion's card and Settings; opens the
// suggestion a tapped prompt names, and shows banners for required settings that are off
package io.github.adenkushnir.datebook
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.ExistingWorkPolicy
import io.github.adenkushnir.datebook.calendar.phoneCalendars
import io.github.adenkushnir.datebook.flights.scheduleFlightChecks
import io.github.adenkushnir.datebook.flights.statusLine
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.leaveby.scheduleLeaveBy
import io.github.adenkushnir.datebook.pipeline.eventTimes
import io.github.adenkushnir.datebook.places.mapsIntent
import io.github.adenkushnir.datebook.capture.WATCHABLE_APPS
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.pipeline.IncomingText
import io.github.adenkushnir.datebook.pipeline.maskKey
import io.github.adenkushnir.datebook.pipeline.summary
import io.github.adenkushnir.datebook.prompts.EXTRA_SUGGESTION_ID
import io.github.adenkushnir.datebook.prompts.addSuggestion
import io.github.adenkushnir.datebook.prompts.ignoreSuggestion
import io.github.adenkushnir.datebook.settings.AboutPage
import io.github.adenkushnir.datebook.settings.AppsPage
import io.github.adenkushnir.datebook.settings.CalendarsPage
import io.github.adenkushnir.datebook.settings.FlightsPage
import io.github.adenkushnir.datebook.settings.ClaudePage
import io.github.adenkushnir.datebook.settings.GITHUB_URL
import io.github.adenkushnir.datebook.settings.NotificationsPage
import io.github.adenkushnir.datebook.settings.PlacesPage
import io.github.adenkushnir.datebook.settings.PrivacyPage
import io.github.adenkushnir.datebook.settings.SettingsItem
import io.github.adenkushnir.datebook.settings.SettingsMenu
import io.github.adenkushnir.datebook.summary.scheduleSummary
import io.github.adenkushnir.datebook.setup.SetupActions
import io.github.adenkushnir.datebook.setup.SetupScreen
import io.github.adenkushnir.datebook.setup.SetupStatus
import io.github.adenkushnir.datebook.setup.checkClaudeKey
import io.github.adenkushnir.datebook.setup.markSetupFinished
import io.github.adenkushnir.datebook.setup.readSetupStatus
import io.github.adenkushnir.datebook.setup.setupFinished
import io.github.adenkushnir.datebook.setup.showSamplePrompt
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDateTime
import kotlinx.coroutines.launch
const val ROUTE_SETUP = "setup"
const val ROUTE_HOME = "home"
const val ROUTE_SUGGESTION = "suggestion/{id}"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_SETTINGS_APPS = "settings/apps"
const val ROUTE_SETTINGS_CLAUDE = "settings/claude"
const val ROUTE_SETTINGS_CALENDARS = "settings/calendars"
const val ROUTE_SETTINGS_PLACES = "settings/places"
const val ROUTE_SETTINGS_FLIGHTS = "settings/flights"
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION)
const val ROUTE_SETTINGS_NOTIFICATIONS = "settings/notifications"
const val ROUTE_SETTINGS_PRIVACY = "settings/privacy"
const val ROUTE_SETTINGS_ABOUT = "settings/about"

// The activity Android starts when you tap the Datebook icon or a prompt
class MainActivity : ComponentActivity() {
    private var promptSuggestion by mutableStateOf<Long?>(null)

    // Draw edge to edge and show the app, opening the suggestion a prompt asked for
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        promptSuggestion = suggestionIn(intent)
        setContent {
            DatebookTheme {
                // The theme's background and text colours for every screen, so text is readable in light and dark
                Surface(color = MaterialTheme.colorScheme.background) {
                    DatebookApp()
                }
            }
        }
    }

    // A tapped prompt can reach the activity while it is already open
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        promptSuggestion = suggestionIn(intent) ?: promptSuggestion
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
        val scope = rememberCoroutineScope()
        val waitingFlow = remember { graph.dao.watchSuggestions(Suggestion.STATUS_WAITING) }
        val addedFlow = remember { graph.dao.watchSuggestions(Suggestion.STATUS_ADDED) }
        val waiting by waitingFlow.collectAsState(initial = emptyList())
        val added by addedFlow.collectAsState(initial = emptyList())
        var status by remember { mutableStateOf(readSetupStatus(applicationContext, graph)) }
        val refresh = { status = readSetupStatus(applicationContext, graph) }
        val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh()
        }
        val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }
        LifecycleResumeEffect(Unit) {
            refresh()  // re-check every step when you come back from Android's settings
            onPauseOrDispose {}
        }
        LaunchedEffect(Unit) {
            graph.pipeline.cleanUp()
            scheduleSummary(applicationContext)  // book the morning summary if it isn't already
            scheduleLeaveBy(applicationContext)  // start planning leave-by alerts if it isn't already
            scheduleFlightChecks(applicationContext)  // start the flight checks if they aren't running
        }
        val actions = SetupActions(
            openAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            askNotifications = { askNotifications(askPermission::launch) },
            checkAndSaveKey = { key -> checkAndSaveKey(graph, key, refresh) },
            openBattery = { startActivity(appDetails()) },
            askCalendar = { askPermission.launch(Manifest.permission.READ_CALENDAR) },
            saveMapsKey = { key -> graph.mapsKeyStore.save(key); refresh() },
        )
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
                val now = remember(added) { System.currentTimeMillis() }
                HomeScreen(
                    waiting = waiting,
                    ahead = upcoming(added, now),
                    nowMillis = now,
                    onOpen = { nav.navigate("suggestion/$it") },
                    onAdd = add,
                    onIgnore = ignore,
                    onSettings = { nav.navigate(ROUTE_SETTINGS) },
                    fixes = fixesFor(status, actions, openKeySetup = { nav.navigate(ROUTE_SETUP) }),
                )
            }
            composable(ROUTE_SUGGESTION, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: -1
                val suggestion by produceState<Suggestion?>(null, id) { value = graph.dao.suggestion(id) }
                var travelMode by remember(suggestion) { mutableStateOf(suggestion?.let { modeFor(graph, it) }) }
                SuggestionScreen(
                    suggestion = suggestion,
                    onBack = { nav.popBackStack() },
                    onAdd = { add(id); nav.popBackStack() },
                    onIgnore = { ignore(id); nav.popBackStack() },
                    onOpenMaps = { place -> openMaps(place, travelMode ?: graph.travelSettings.mode()) },
                    onTrack = { url -> openLink(url) },
                    flightStatus = suggestion?.let { flightStatusFor(graph, it) },
                    travelMode = travelMode,
                    onTravelMode = { mode ->
                        val plan = suggestion ?: return@SuggestionScreen
                        LeaveByStore(applicationContext).setMode(eventKey(plan.title, planBegin(plan)), mode)
                        travelMode = mode
                    },
                )
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
                    checkAndSaveKey = { key -> checkAndSaveKey(graph, key, refresh) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(ROUTE_SETTINGS_CALENDARS) {
                val calendars = remember(status.calendar) { phoneCalendars(applicationContext) }  // again once allowed
                val switches = graph.calendarSwitches
                CalendarsPage(status.calendar, calendars, switches::isOn, switches::set, actions.askCalendar,
                    onBack = { nav.popBackStack() })
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
                    onBack = { nav.popBackStack() },
                )
            }
            composable(ROUTE_SETTINGS_NOTIFICATIONS) {
                val summary = graph.summarySettings
                NotificationsPage(status.access, status.notifications, actions.openAccess,
                    openNotifications = { startActivity(appNotificationSettings()) },
                    summaryOn = summary.isOn(), summaryHour = summary.hour(), summaryMinute = summary.minute(),
                    setSummary = { on, hour, minute ->
                        summary.set(on, hour, minute)
                        scheduleSummary(applicationContext, ExistingWorkPolicy.REPLACE)  // re-book at the new time
                    },
                    onBack = { nav.popBackStack() })
            }
            composable(ROUTE_SETTINGS_PRIVACY) {
                PrivacyPage(clearHistory = { graph.pipeline.clearHistory() }, onBack = { nav.popBackStack() })
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
            refresh()
        }
        return problem
    }

    // The Settings menu's rows, each opening its page (Smart features and Automatic mode come in later steps)
    private fun settingsItems(open: (String) -> Unit): List<SettingsItem> {
        return listOf(
            SettingsItem("▣", "Apps to watch", "Which apps Datebook reads texts from") { open(ROUTE_SETTINGS_APPS) },
            SettingsItem("AI", "Claude & API key", "Your key and which model to use") { open(ROUTE_SETTINGS_CLAUDE) },
            SettingsItem("13", "Calendars", "Which calendars to check for clashes") { open(ROUTE_SETTINGS_CALENDARS) },
            SettingsItem("📍", "Places & travel", "Maps key, how you get around, home") { open(ROUTE_SETTINGS_PLACES) },
            SettingsItem("✈", "Flights & trains", "Live flight times and NJ Transit") { open(ROUTE_SETTINGS_FLIGHTS) },
            SettingsItem("🔔", "Notifications & alerts", "Reading texts and showing prompts") {
                open(ROUTE_SETTINGS_NOTIFICATIONS)
            },
            SettingsItem("🔒", "Privacy & history", "What's kept, and clearing history") {
                open(ROUTE_SETTINGS_PRIVACY)
            },
            SettingsItem("i", "About Datebook", "Version, GitHub and privacy") { open(ROUTE_SETTINGS_ABOUT) },
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
        return fixes
    }
}
