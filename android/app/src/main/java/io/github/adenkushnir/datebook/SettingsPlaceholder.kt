// A stand-in Settings page until the real category menu arrives in step 5 part C
package io.github.adenkushnir.datebook
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Show the Settings title and a note that the pages are coming
@Composable
fun SettingsPlaceholder(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Settings", onBack = onBack)
        Column(Modifier.padding(16.dp)) {
            Muted("Settings arrive in the next part of this step.")
        }
    }
}
