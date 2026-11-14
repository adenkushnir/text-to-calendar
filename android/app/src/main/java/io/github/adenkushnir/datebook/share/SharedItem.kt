// Reads what was long-pressed or shared to Datebook (text, a picture or a PDF), and shrinks shared pictures before
// they are sent to Claude
package io.github.adenkushnir.datebook.share
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.roundToInt
const val MAX_IMAGE_SIDE = 1568
private const val JPEG_QUALITY = 85
const val MAX_PDF_BYTES = 20 * 1024 * 1024  // bigger PDFs are refused

// What was shared: its words (maybe empty for a picture or PDF) and, for a picture or a PDF, where to read it from
data class SharedItem(val text: String, val imageUri: Uri? = null, val pdfUri: Uri? = null)

// Read the long-pressed text, or the shared text and picture, from the intent; null when there's nothing to use
fun sharedItemFrom(intent: Intent): SharedItem? {
    // Pick where the words and picture are kept for each way of sharing
    val item = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT ->
            SharedItem(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty())
        Intent.ACTION_SEND -> {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val text = listOf(subject, body).filter { it.isNotBlank() }.joinToString("\n")  // a subject goes first
            val isImage = intent.type?.startsWith("image/") == true
            val isPdf = intent.type == "application/pdf"
            val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            SharedItem(text.trim(), if (isImage) stream else null, if (isPdf) stream else null)  // only these are read
        }
        else -> null
    }

    // Ignore shares with no words, picture or PDF
    if (item == null || (item.text.isBlank() && item.imageUri == null && item.pdfUri == null)) {
        return null
    }
    return item
}

// The size to shrink a picture to so its longest side is at most maxSide, keeping its shape (small ones stay as is)
fun shrunkSize(width: Int, height: Int, maxSide: Int = MAX_IMAGE_SIDE): Pair<Int, Int> {
    val longest = maxOf(width, height)

    // Leave pictures that are small enough
    if (longest <= maxSide) {
        return Pair(width, height)
    }
    val scale = maxSide.toDouble() / longest
    return Pair(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()))
}

// Read a shared picture, shrink it, and return it as base64 JPEG for Claude (smaller pictures cost less to send)
fun loadImageBase64(context: Context, uri: Uri): String {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (width, height) = shrunkSize(info.size.width, info.size.height)
        decoder.setTargetSize(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // so it can be compressed
    }
    val bytes = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
    bitmap.recycle()
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
}

// Read a shared PDF as base64 for Claude; throws when it can't be read or is too big
fun loadPdfBase64(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("PDF can't be opened")

    // Refuse PDFs too big to send
    if (bytes.size > MAX_PDF_BYTES) {
        error("PDF is too big")
    }
    return Base64.getEncoder().encodeToString(bytes)
}
