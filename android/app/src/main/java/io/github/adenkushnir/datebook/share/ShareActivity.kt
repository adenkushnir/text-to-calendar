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
import androidx.compose.ui.BiasAlignment
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
import io.github.adenkushnir.datebook.pipeline.SHARED_PDF_APP
import io.github.adenkushnir.datebook.pipeline.SHARED_TEXT_APP
import io.github.adenkushnir.datebook.pipeline.ShareResult
import io.github.adenkushnir.datebook.prompts.addSuggestion
import io.github.adenkushnir.datebook.prompts.ignoreSuggestion
import io.github.adenkushnir.datebook.scenery.MotionSettings
import io.github.adenkushnir.datebook.scenery.Scene
import io.github.adenkushnir.datebook.scenery.SHARE_SHEET_EFFECTS
import io.github.adenkushnir.datebook.scenery.SceneBackdrop
import io.github.adenkushnir.datebook.scenery.movingSwitch
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
    var ticked by mutableStateOf(emptySet<Long>())
    var queue by mutableStateOf<AddQueue?>(null)
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
            val found = ask(item)
            ticked = found.suggestions.map { it.id }.toSet()  // a schedule starts with every date ticked
            result = found
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

        // Read the PDF off the main thread, saying so if it can't be read or is too big
        val pdf = try {
            item.pdfUri?.let { withContext(Dispatchers.IO) { loadPdfBase64(context, it) } } ?: ""
        } catch (error: Exception) {  // a missing file, no permission, or over 20 MB
            return ShareResult(emptyList(), "Couldn't read that PDF (it has to be under 20 MB).")
        }

        // The notes say whether a picture or a PDF was shared
        val app = when {
            pdf.isNotBlank() -> SHARED_PDF_APP
            image.isNotBlank() -> SHARED_IMAGE_APP
            else -> SHARED_TEXT_APP
        }
        val incoming = IncomingText("you", item.text, LocalDateTime.now(), app = app, image = image, pdf = pdf)
        return AppGraph.get(context).pipeline.share(incoming)
    }
}

private val SHEET_FRAMING = BiasAlignment(0f, 0.5f)  // between the middle and the bottom: open water, little sand
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
        movingSwitch.value = MotionSettings(this).isOn()
        enableEdgeToEdge()
        setContent {
            DatebookTheme {
                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val light = rememberSceneLight()
                val haze = rememberHazeState()
                ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet,
                    containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                    dragHandle = null) {
                    // The beach scene fills the sheet behind its handle and contents, showing the water near the
                    // picture's bottom, with stronger waves and rising bubbles so the motion shows through the wash
                    Box {
                        SceneBackdrop(Scene.BEACH, light, Modifier.matchParentSize().hazeSource(haze),
                            alignment = SHEET_FRAMING, effects = SHARE_SHEET_EFFECTS)
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

    // Start adding a schedule: unticked plans are ignored, then the first ticked one opens in the calendar app
    private fun startAdding() {
        val plans = model.result?.suggestions.orEmpty()
        val unticked = plans.map { it.id }.filter { it !in model.ticked }
        model.ignored += unticked
        lifecycleScope.launch {
            // Ignore each unticked plan
            for (id in unticked) {
                ignoreSuggestion(applicationContext, id)
            }
        }
        model.queue = AddQueue(plans.map { it.id }.filter { it in model.ticked })
        addNext()
    }

    // Open the schedule's next plan in the calendar app and move on to the one after it
    private fun addNext() {
        val queue = model.queue ?: return
        val id = queue.ids.getOrNull(queue.next) ?: return  // nothing left
        model.added += id
        model.queue = queue.copy(next = queue.next + 1)
        lifecycleScope.launch { addSuggestion(this@ShareActivity, id) }
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
            ticked = model.ticked,
            queue = model.queue,
            onTick = { id, on -> model.ticked = if (on) model.ticked + id else model.ticked - id },
            onAddTicked = { startAdding() },
            onAddNext = { addNext() },
            onSkip = { model.queue = model.queue?.let { it.copy(next = it.next + 1) } },
            onOpenDatebook = {
                startActivity(Intent(this@ShareActivity, MainActivity::class.java))
                finish()
            },
            onClose = { finish() })
    }
}
