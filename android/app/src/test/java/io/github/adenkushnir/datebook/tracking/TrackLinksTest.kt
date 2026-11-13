// Tests for Track links: which carrier a number belongs to, each carrier's page, and flight status links
package io.github.adenkushnir.datebook.tracking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Checks carrier guesses and the pages the Track button opens
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackLinksTest {
    // Claude's carrier wins; otherwise the number's shape tells UPS, Amazon, USPS and FedEx apart
    @Test
    fun carriers() {
        assertEquals(CARRIER_DHL, carrierFor("DHL", "1234567890"))
        assertEquals(CARRIER_UPS, carrierFor("", "1Z999AA10123456784"))
        assertEquals(CARRIER_AMAZON, carrierFor("", "TBA123456789012"))
        assertEquals(CARRIER_USPS, carrierFor("", "9400 1000 0000 0000 0000 00"))
        assertEquals(CARRIER_FEDEX, carrierFor("", "123456789012"))
        assertEquals("", carrierFor("", "ABC"))
    }

    // Each carrier's tracking page carries the number without spaces
    @Test
    fun packagePages() {
        assertEquals("https://www.ups.com/track?tracknum=1Z999AA10123456784",
            packageLink("", "1Z999AA10123456784")!!.url)
        assertEquals("https://tools.usps.com/go/TrackConfirmAction?tLabels=9400100000000000000000",
            packageLink("usps", "9400 1000 0000 0000 0000 00")!!.url)
        assertEquals("TRACK PACKAGE", packageLink("fedex", "123456789012")!!.label)
        assertNull(packageLink("", "ABC"))
    }

    // A flight gets a status page; a package wins over a flight; other plans get no button
    @Test
    fun planLinks() {
        assertEquals(TrackLink("FLIGHT STATUS", "https://www.google.com/search?q=UA1234%20flight%20status"),
            trackLinkFor("UA1234", "", ""))
        assertEquals("TRACK PACKAGE", trackLinkFor("UA1234", "ups", "1Z999AA10123456784")!!.label)
        assertNull(trackLinkFor("", "", ""))
    }
}
