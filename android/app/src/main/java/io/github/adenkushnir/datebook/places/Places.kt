// Google Maps for Datebook: finds a place's full address with Places API (New), sending only the place's name and
// roughly where you are, and builds Open in Maps links; also the travel settings (default mode and home address)
package io.github.adenkushnir.datebook.places
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
const val TRAVEL_DRIVE = "DRIVE"
const val TRAVEL_TRANSIT = "TRANSIT"
const val TRAVEL_WALK = "WALK"
const val TRAVEL_BIKE = "BICYCLE"
private const val SEARCH_URL = "https://places.googleapis.com/v1/places:searchText"
private const val SEARCH_FIELDS = "places.displayName,places.formattedAddress,places.location"
private const val NEARBY_METERS = 50000.0
private const val TIMEOUT_MILLIS = 10000
private val MAPPER = ObjectMapper()

// One way of getting somewhere: its id for Google's Routes API, the name shown in Datebook, and Google Maps' name
data class TravelMode(val id: String, val label: String, val mapsName: String)
val TRAVEL_MODES = listOf(
    TravelMode(TRAVEL_DRIVE, "🚗 Drive", "driving"),
    TravelMode(TRAVEL_TRANSIT, "🚌 Transit", "transit"),
    TravelMode(TRAVEL_WALK, "🚶 Walk", "walking"),
    TravelMode(TRAVEL_BIKE, "🚲 Bike", "bicycling"),
)

// Return the travel mode with an id, or driving for anything unknown
fun travelMode(id: String): TravelMode {
    return TRAVEL_MODES.firstOrNull { it.id == id } ?: TRAVEL_MODES.first()
}

// Remembers the default travel mode and the home address travel times fall back to
class TravelSettings(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-travel", Context.MODE_PRIVATE)

    // Return the default travel mode's id (driving to start)
    fun mode(): String {
        return travelMode(prefs.getString("mode", TRAVEL_DRIVE).orEmpty()).id
    }

    // Choose the default travel mode
    fun setMode(mode: String) {
        prefs.edit().putString("mode", travelMode(mode).id).apply()
    }

    // Return the home address (empty until you set it)
    fun home(): String {
        return prefs.getString("home", "").orEmpty()
    }

    // Save the home address
    fun setHome(address: String) {
        prefs.edit().putString("home", address.trim()).apply()
    }
}

// The place as it goes in the calendar: "Luigi's, 123 Main St, East Hanover, NJ", or just the address when it already
// starts with the name
fun placeLine(name: String, address: String): String {
    // Use whichever parts there are
    return when {
        address.isBlank() -> name
        name.isBlank() || address.startsWith(name) -> address
        else -> "$name, $address"
    }
}

// The search for one place: its name and one result, centred on where you are (within 50 km), or "near" your home
// address when your location isn't known
fun searchBody(place: String, near: Pair<Double, Double>?, home: String): Map<String, Any> {
    val query = if (near == null && home.isNotBlank()) "$place near $home" else place  // fall back to home
    val body = mutableMapOf<String, Any>("textQuery" to query, "pageSize" to 1)

    // Prefer places near where you are
    if (near != null) {
        val center = mapOf("latitude" to near.first, "longitude" to near.second)
        body["locationBias"] = mapOf("circle" to mapOf("center" to center, "radius" to NEARBY_METERS))
    }
    return body
}

// A place Google found: the line for the calendar and its coordinates (null when Google gave none)
data class FoundPlace(val line: String, val lat: Double? = null, val lng: Double? = null)

// Read the first place from Google's reply as a calendar location with its coordinates, or null when nothing matched
fun parsePlace(json: String): FoundPlace? {
    val first = MAPPER.readTree(json).path("places").path(0)

    // An empty reply means no match
    if (first.isMissingNode) {
        return null
    }
    val line = placeLine(first.path("displayName").path("text").asText(""), first.path("formattedAddress").asText(""))
    val location = first.path("location")
    val lat = if (location.has("latitude")) location.path("latitude").asDouble() else null  // only when given
    val lng = if (location.has("longitude")) location.path("longitude").asDouble() else null
    return if (line.isBlank()) null else FoundPlace(line, lat, lng)
}

// Look up a place's full address and coordinates; null without a key, without a match, or on any failure (the place
// then stays as the text wrote it); this waits on the network, so call it off the main thread
fun findPlace(context: Context, key: String?, home: String, place: String): FoundPlace? {
    // Nothing to look up without a key or a place
    if (key.isNullOrBlank() || place.isBlank()) {
        return null
    }

    // Any network or reply problem just leaves the place as written
    return try {
        val reply = postJson(context, SEARCH_URL, key, SEARCH_FIELDS, searchBody(place, lastLocation(context), home))
        parsePlace(reply)
    } catch (error: Exception) {  // no internet, a rejected key, or an unreadable reply
        Log.w("Datebook", "place lookup failed: ${error.message}")
        null
    }
}

// Send a JSON request to a Google Maps web service with the key, the fields wanted and this app's identity (so a key
// restricted to Datebook works), and return the reply's text
fun postJson(context: Context, url: String, key: String, fields: String, body: Map<String, Any>): String {
    val (packageName, cert) = appIdentity(context)
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.connectTimeout = TIMEOUT_MILLIS
    connection.readTimeout = TIMEOUT_MILLIS
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("X-Goog-Api-Key", key)
    connection.setRequestProperty("X-Goog-FieldMask", fields)
    connection.setRequestProperty("X-Android-Package", packageName)
    connection.setRequestProperty("X-Android-Cert", cert)

    // Always close the connection, even when the request fails
    try {
        connection.outputStream.use { it.write(MAPPER.writeValueAsBytes(body)) }
        val ok = connection.responseCode in 200..299
        val stream = if (ok) connection.inputStream else connection.errorStream  // the error explains a refusal
        val reply = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(ok) { "Google Maps error ${connection.responseCode}: ${reply.take(200)}" }
        return reply
    } finally {
        connection.disconnect()
    }
}

// This app's package name and its signing certificate's SHA-1 as hex without colons, which Google checks against the
// key's Android restriction
fun appIdentity(context: Context): Pair<String, String> {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: ByteArray(0)
    val sha1 = MessageDigest.getInstance("SHA-1").digest(cert)
    return Pair(context.packageName, sha1.joinToString("") { "%02X".format(it) })
}

// Return true if Datebook may read the phone's location
fun hasLocationAccess(context: Context): Boolean {
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return coarse == PackageManager.PERMISSION_GRANTED
}

// Where the phone last was (latitude, longitude), from the freshest location Android has, or null without permission
fun lastLocation(context: Context): Pair<Double, Double>? {
    // Nothing is known without permission
    if (!hasLocationAccess(context)) {
        return null
    }
    val manager = context.getSystemService(LocationManager::class.java)

    // Permission can be taken away at any moment; treat that as no location
    return try {
        val known = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
        known.maxByOrNull { it.time }?.let { Pair(it.latitude, it.longitude) }
    } catch (error: SecurityException) {  // permission was turned off
        null
    }
}

// The intent that starts directions to a place in Google Maps (or any maps app) with a travel mode
fun mapsIntent(place: String, mode: String): Intent {
    val link = "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(place)}" +
        "&travelmode=${travelMode(mode).mapsName}"
    return Intent(Intent.ACTION_VIEW, Uri.parse(link))
}
