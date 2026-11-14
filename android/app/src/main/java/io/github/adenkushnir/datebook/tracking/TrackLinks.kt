// Track links for plans: a carrier's tracking page for a package, and a status page for a flight (live flight times
// come in part B); no tracking service is used, only links
package io.github.adenkushnir.datebook.tracking
import android.net.Uri
const val CARRIER_UPS = "ups"
const val CARRIER_FEDEX = "fedex"
const val CARRIER_USPS = "usps"
const val CARRIER_AMAZON = "amazon"
const val CARRIER_DHL = "dhl"
private val UPS_NUMBER = Regex("1Z[0-9A-Z]{16}", RegexOption.IGNORE_CASE)
private val AMAZON_NUMBER = Regex("TBA[0-9]{12}", RegexOption.IGNORE_CASE)
private val USPS_NUMBER = Regex("9[0-9]{19,21}")
private val FEDEX_NUMBER = Regex("[0-9]{12}|[0-9]{15}")

// A Track button: what it says and the page it opens
data class TrackLink(val label: String, val url: String)

// The carrier a tracking number belongs to, from the carrier Claude gave or, if that's empty, the number's shape
// (UPS 1Z…, Amazon TBA…, USPS 20–22 digits starting with 9, FedEx 12 or 15 digits); empty when unknown
fun carrierFor(carrier: String, number: String): String {
    val clean = number.replace(" ", "")

    // Pick the carrier from what's known
    return when {
        carrier.isNotBlank() -> carrier.lowercase()
        UPS_NUMBER.matches(clean) -> CARRIER_UPS
        AMAZON_NUMBER.matches(clean) -> CARRIER_AMAZON
        USPS_NUMBER.matches(clean) -> CARRIER_USPS
        FEDEX_NUMBER.matches(clean) -> CARRIER_FEDEX
        else -> ""
    }
}

// The carrier's tracking page for a package, or null when the carrier is unknown
fun packageLink(carrier: String, number: String): TrackLink? {
    val clean = Uri.encode(number.replace(" ", ""))

    // Each carrier has its own tracking page
    val url = when (carrierFor(carrier, number)) {
        CARRIER_UPS -> "https://www.ups.com/track?tracknum=$clean"
        CARRIER_FEDEX -> "https://www.fedex.com/fedextrack/?trknbr=$clean"
        CARRIER_USPS -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$clean"
        CARRIER_AMAZON -> "https://track.amazon.com/tracking/$clean"
        CARRIER_DHL -> "https://www.dhl.com/us-en/home/tracking/tracking-express.html?submit=1&tracking-id=$clean"
        else -> return null
    }
    return TrackLink("Track package", url)
}

// A flight's live status page (a web search, which shows the airline's status card), until part B shows it in the app
fun flightLink(flight: String): TrackLink {
    return TrackLink("Flight status", "https://www.google.com/search?q=${Uri.encode("$flight flight status")}")
}

// The Track button for a plan: its package's tracking page, or its flight's status page; null for other plans
fun trackLinkFor(flight: String, carrier: String, number: String): TrackLink? {
    // Packages win over flights; plans with neither get no button
    return when {
        number.isNotBlank() -> packageLink(carrier, number)
        flight.isNotBlank() -> flightLink(flight)
        else -> null
    }
}
