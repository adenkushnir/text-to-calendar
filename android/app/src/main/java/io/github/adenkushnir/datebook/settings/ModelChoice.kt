// Remembers which Claude model Datebook uses: Opus 5.5 (the default) or the much cheaper Haiku 5.5
package io.github.adenkushnir.datebook.settings
import android.content.Context
import io.github.adenkushnir.datebook.pipeline.DEFAULT_MODEL
const val HAIKU_MODEL = "claude-haiku-5-5"

// One model you can pick in Settings, with what it's good for and roughly what it costs
data class ModelOption(val id: String, val name: String, val note: String)
val MODEL_OPTIONS = listOf(
    ModelOption(DEFAULT_MODEL, "Opus 5.5", "Best with casual texts · about \$3–8 a month"),
    ModelOption(HAIKU_MODEL, "Haiku 5.5", "Much cheaper · well under \$1 a month · a bit less accurate"),
)

// Saves the chosen model; anything unknown falls back to the default
class ModelChoice(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-settings", Context.MODE_PRIVATE)

    // Return the chosen model's id
    fun get(): String {
        val saved = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        return if (MODEL_OPTIONS.any { it.id == saved }) saved else DEFAULT_MODEL  // ignore unknown models
    }

    // Choose a model
    fun set(model: String) {
        prefs.edit().putString("model", model).apply()
    }
}
