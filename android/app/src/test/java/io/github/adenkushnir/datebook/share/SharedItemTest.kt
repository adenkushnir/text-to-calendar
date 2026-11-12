// Tests for reading long-pressed and shared things, and for shrinking shared pictures
package io.github.adenkushnir.datebook.share
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks each way of sharing gives the right words and picture
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedItemTest {
    // Long-pressed text comes through as it is
    @Test
    fun longPress() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "dinner fri 7pm")
        assertEquals(SharedItem("dinner fri 7pm"), sharedItemFrom(intent))
    }

    // Shared text keeps a subject line first, like an email's
    @Test
    fun sharedText() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Team party")
            .putExtra(Intent.EXTRA_TEXT, "Saturday at 6 at Sam's")
        assertEquals(SharedItem("Team party\nSaturday at 6 at Sam's"), sharedItemFrom(intent))
    }

    // A shared picture brings its address, with or without words
    @Test
    fun sharedPicture() {
        val uri = Uri.parse("content://media/external/images/1")
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(SharedItem("", uri), sharedItemFrom(intent))
    }

    // Shares with nothing in them, and other actions, are ignored
    @Test
    fun nothingShared() {
        assertNull(sharedItemFrom(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, " ")))
        assertNull(sharedItemFrom(Intent(Intent.ACTION_VIEW)))
    }

    // Big pictures shrink to 1568 pixels on their longest side, keeping their shape; small ones stay the same
    @Test
    fun shrinking() {
        assertEquals(Pair(1568, 724), shrunkSize(4000, 1847))
        assertEquals(Pair(724, 1568), shrunkSize(1847, 4000))
        assertEquals(Pair(800, 600), shrunkSize(800, 600))
    }
}
