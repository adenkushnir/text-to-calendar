// Datebook's main screen: the home dashboard, or one suggestion's card when a prompt was tapped; asks for
// notification permission so prompts can appear, and shows test tools in debug builds
package io.github.adenkushnir.datebook
import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.prompts.EXTRA_SUGGESTION_ID
import io.github.adenkushnir.datebook.prompts.addSuggestion
import io.github.adenkushnir.datebook.prompts.canNotify
import io.github.adenkushnir.datebook.prompts.ignoreSuggestion
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import kotlinx.coroutines.launch

// The activity Android starts when you tap the Datebook icon or a prompt
class MainActivity : ComponentActivity() {
    private var openSuggestion by mutableStateOf<Long?>(null)

    // Draw edge to edge and show the home dashboard, or the suggestion a prompt asked for
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openSuggestion = suggestionIn(intent)
        setContent {
            DatebookTheme {
                DatebookApp()
            }
        }
    }

    // A tapped prompt can reach the activity while it is already open
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSuggestion = suggestionIn(intent) ?: openSuggestion
    }

    // Return the suggestion id a prompt's intent carries, or null
    private fun suggestionIn(intent: Intent?): Long? {
        val id = intent?.getLongExtra(EXTRA_SUGGESTION_ID, -1) ?: -1
        return if (id >= 0) id else null  // -1 means no suggestion
    }

    // The app's screens: home, or a suggestion's card
    @Composable
    private fun DatebookApp() {
        val graph = remember { AppGraph.get(applicationContext) }
        val scope = rememberCoroutineScope()
        val waitingFlow = remember { graph.dao.watchSuggestions(Suggestion.STATUS_WAITING) }
        val waiting by waitingFlow.collectAsState(initial = emptyList())
        val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            graph.pipeline.cleanUp()

            // Ask once for permission to show prompts (Android 13 and later)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canNotify(applicationContext)) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val add: (Long) -> Unit = { id -> scope.launch { addSuggestion(this@MainActivity, id) } }
        val ignore: (Long) -> Unit = { id -> scope.launch { ignoreSuggestion(applicationContext, id) } }
        val open = openSuggestion

        // Show the suggestion a prompt asked for, with back returning home
        if (open != null) {
            val suggestion by produceState<Suggestion?>(null, open) { value = graph.dao.suggestion(open) }
            BackHandler {
                openSuggestion = null
            }
            SuggestionScreen(
                suggestion = suggestion,
                onBack = { openSuggestion = null },
                onAdd = { add(open); openSuggestion = null },
                onIgnore = { ignore(open); openSuggestion = null },
            )
            return
        }
        val debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val tools: (@Composable () -> Unit)? = { TestToolsCard(graph) }
        HomeScreen(
            waiting = waiting,
            onOpen = { openSuggestion = it },
            onAdd = add,
            onIgnore = ignore,
            testTools = if (debugBuild) tools else null,  // debug builds only
        )
    }
}
