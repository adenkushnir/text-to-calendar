// Datebook's main screen: opens the home dashboard in the app's theme, with test tools in debug builds
package io.github.adenkushnir.datebook
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme

// The activity Android starts when you tap the Datebook icon
class MainActivity : ComponentActivity() {
    // Draw edge to edge and show the home dashboard with the waiting suggestions
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DatebookTheme {
                val graph = remember { AppGraph.get(applicationContext) }
                val waitingFlow = remember { graph.dao.watchSuggestions(Suggestion.STATUS_WAITING) }
                val waiting by waitingFlow.collectAsState(initial = emptyList())
                LaunchedEffect(Unit) {
                    graph.pipeline.cleanUp()
                }
                val debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val tools: (@Composable () -> Unit)? = { TestToolsCard(graph) }
                HomeScreen(waiting = waiting, testTools = if (debugBuild) tools else null)  // debug builds only
            }
        }
    }
}
