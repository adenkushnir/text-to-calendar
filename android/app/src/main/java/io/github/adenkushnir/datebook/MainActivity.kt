// Datebook's main screen: opens the home dashboard in the app's theme
package io.github.adenkushnir.datebook
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme

// The activity Android starts when you tap the Datebook icon
class MainActivity : ComponentActivity() {
    // Draw edge to edge and show the home dashboard
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DatebookTheme {
                HomeScreen()
            }
        }
    }
}
