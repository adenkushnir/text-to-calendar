// Datebook's main screen: moves between Home, a suggestion's card and Settings; opens the suggestion a tapped prompt
// names, asks for notification permission so prompts can appear, and shows test tools in debug builds
package io.github.adenkushnir.datebook
import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.prompts.EXTRA_SUGGESTION_ID
import io.github.adenkushnir.datebook.prompts.addSuggestion
import io.github.adenkushnir.datebook.prompts.canNotify
import io.github.adenkushnir.datebook.prompts.ignoreSuggestion
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import kotlinx.coroutines.launch
const val ROUTE_HOME = "home"
const val ROUTE_SUGGESTION = "suggestion/{id}"
const val ROUTE_SETTINGS = "settings"

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
                DatebookApp()
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
        val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            graph.pipeline.cleanUp()

            // Ask once for permission to show prompts (Android 13 and later)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canNotify(applicationContext)) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        NavHost(nav, startDestination = ROUTE_HOME) {
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
                    testTools = if (debugBuild) ({ TestToolsCard(graph) }) else null,  // debug builds only
                )
            }
            composable(ROUTE_SUGGESTION, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: -1
                val suggestion by produceState<Suggestion?>(null, id) { value = graph.dao.suggestion(id) }
                SuggestionScreen(
                    suggestion = suggestion,
                    onBack = { nav.popBackStack() },
                    onAdd = { add(id); nav.popBackStack() },
                    onIgnore = { ignore(id); nav.popBackStack() },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsPlaceholder(onBack = { nav.popBackStack() })
            }
        }
    }
}
