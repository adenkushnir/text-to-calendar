// The see-through screen behind long-press "Add to calendar" and Share → Datebook: it asks Claude about what you
// picked and shows the plans in a bottom sheet over the app you were in
package io.github.adenkushnir.datebook.share
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.pipeline.IncomingText
import io.github.adenkushnir.datebook.pipeline.SHARED_IMAGE_APP
import io.github.adenkushnir.datebook.pipeline.SHARED_TEXT_APP
import io.github.adenkushnir.datebook.pipeline.ShareResult
import io.github.adenkushnir.datebook.prompts.addSuggestion
import io.github.adenkushnir.datebook.prompts.ignoreSuggestion
import io.github.adenkushnir.datebook.scenery.Scene
import io.github.adenkushnir.datebook.scenery.SceneBackdrop
import io.github.adenkushnir.datebook.scenery.rememberSceneLight
import io.github.adenkushnir.datebook.ui.Glass
import io.github.adenkushnir.datebook.ui.LocalGlass
import io.github.adenkushnir.datebook.ui.glassTint
import io.github.adenkushnir.datebook.ui.theme.DatebookTheme
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Keeps the sheet's state when the screen turns, so Claude is only asked once per share
class ShareModel(application: Application) : AndroidViewModel(application) {
    var result by mutableStateOf<ShareResult?>(null)
        private set
    var added by mutableStateOf(emptySet<Long>())
    var ignored by mutableStateOf(emptySet<Long>())
    private var started = false

    // Ask Claude about the shared item the first time (or again after a failure)
    fun start(item: SharedItem, again: Boolean = false) {
        // Only ask once unless trying again
        if (started && !again) {
            return
        }
        started = true
        result = null
        viewModelScope.launch {
            result = ask(item)
        }
    }

    // Turn the shared item into a text for the pipeline, reading and shrinking any picture first
    private suspend fun ask(item: SharedItem): ShareResult {
        val context = getApplication<Application>()

        // Read the picture off the main thread, saying so if it can't be read
        val image = try {
            item.imageUri?.let { withContext(Dispatchers.IO) { loadImageBase64(context, it) } } ?: ""
        } catch (error: Exception) {  // a missing file, no permission, or not really a picture
            return ShareResult(emptyList(), "Couldn't read that picture.")
        }
        val app = if (image.isNotBlank()) SHARED_IMAGE_APP else SHARED_TEXT_APP  // pictures get "Image shared" notes
        val incoming = IncomingText("you", item.text, LocalDateTime.now(), app = app, image = image)
        return AppGraph.get(context).pipeline.share(incoming)
    }
}

private const val SHEET_WASH = 0.55f  // the glass-coloured wash over the beach that keeps the sheet readable

// Shows the bottom sheet over the app you shared from, and closes when the sheet does
class ShareActivity : ComponentActivity() {
    private val model: ShareModel by viewModels()

    // Read what was shared, start asking Claude, and show the sheet
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = sharedItemFrom(intent)

        // Close straight away if nothing usable was shared
        if (item == null) {
            finish()
            return
        }
        model.start(item)
        enableEdgeToEdge()
        setContent {
            DatebookTheme {
                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val light = rememberSceneLight()
                val haze = rememberHazeState()
                ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet,
                    containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                    dragHandle = null) {
                    // The beach scene fills the sheet behind its handle and contents, showing the picture's bottom
                    Box {
                        SceneBackdrop(Scene.BEACH, light, Modifier.matchParentSize().hazeSource(haze),
                            alignment = Alignment.BottomCenter)
                        Box(Modifier.matchParentSize().background(glassTint(light).copy(alpha = SHEET_WASH)))
                        CompositionLocalProvider(LocalGlass provides Glass(haze, glassTint(light))) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                BottomSheetDefaults.DragHandle()
                                SheetContents(item)
                            }
                        }
                    }
                }
            }
        }
    }

    // The plans in the sheet, with what each button does
    @Composable
    private fun SheetContents(item: SharedItem) {
        ShareSheet(model.result, model.added, model.ignored,
            onAdd = { id ->
                model.added += id
                lifecycleScope.launch { addSuggestion(this@ShareActivity, id) }
            },
            onIgnore = { id ->
                model.ignored += id
                lifecycleScope.launch { ignoreSuggestion(applicationContext, id) }
            },
            onRetry = { model.start(item, again = true) },
            onOpenDatebook = {
                startActivity(Intent(this@ShareActivity, MainActivity::class.java))
                finish()
            },
            onClose = { finish() })
    }
}
