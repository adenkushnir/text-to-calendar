// Travel times from Google's Routes API: from where the phone is (or the home address) to a place, by a travel mode,
// sending only the two places and the mode
package io.github.adenkushnir.datebook.places
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
private const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
private const val ROUTE_FIELDS = "routes.duration"
private const val LOCATION_WAIT_SECONDS = 15L
private val MAPPER = ObjectMapper()

// The route request: from your location (or home when it isn't known) to the place, by the mode; driving uses live
// traffic and transit aims to arrive on time; null when there is nowhere to start from
fun routeBody(from: Pair<Double, Double>?, home: String, to: String, mode: String, arriveAt: Instant):
    Map<String, Any>? {
    // Pick where the trip starts
    val origin = when {
        from != null -> mapOf("location" to mapOf("latLng" to mapOf("latitude" to from.first,
            "longitude" to from.second)))
        home.isNotBlank() -> mapOf("address" to home)
        else -> return null
    }
    val body = mutableMapOf("origin" to origin, "destination" to mapOf("address" to to),
        "travelMode" to travelMode(mode).id)

    // Add what each mode allows: live traffic for driving, an arrival time for transit
    if (travelMode(mode).id == TRAVEL_DRIVE) {
        body["routingPreference"] = "TRAFFIC_AWARE"
    } else if (travelMode(mode).id == TRAVEL_TRANSIT) {
        body["arrivalTime"] = arriveAt.toString()
    }
    return body
}

// Read the trip's length in whole minutes (rounded up) from Google's reply, or null when there is no route
fun parseMinutes(json: String): Int? {
    val duration = MAPPER.readTree(json).path("routes").path(0).path("duration").asText("")

    // No route, or a reply without a duration
    if (!duration.endsWith("s")) {
        return null
    }
    val seconds = duration.removeSuffix("s").toDoubleOrNull() ?: return null
    return ceil(seconds / 60).toInt()
}

// Ask Google how many minutes it takes to get to a place by a mode, arriving at a time; null without a key, without a
// starting point, without a route, or on any failure; this waits on the network, so call it off the main thread
fun travelMinutes(context: Context, key: String?, home: String, place: String, mode: String, arriveAt: Instant): Int? {
    // Nothing to ask without a key or a place
    if (key.isNullOrBlank() || place.isBlank()) {
        return null
    }
    val body = routeBody(currentLocation(context), home, place, mode, arriveAt) ?: return null  // nowhere to start

    // Any network or reply problem means no travel time
    return try {
        parseMinutes(postJson(context, ROUTES_URL, key, ROUTE_FIELDS, body))
    } catch (error: Exception) {  // no internet, a rejected key, or an unreadable reply
        Log.w("Datebook", "travel time failed: ${error.message}")
        null
    }
}

// Return true if Datebook may read the location while it isn't open ("Allow all the time"), which leave-by alerts need
fun hasBackgroundLocation(context: Context): Boolean {
    val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    return hasLocationAccess(context) && background == PackageManager.PERMISSION_GRANTED
}

// Where the phone is now, waiting up to 15 seconds for a fresh fix (Android 11 and later), else where it last was; null
// without permission; this waits, so call it off the main thread
fun currentLocation(context: Context): Pair<Double, Double>? {
    // Older Android versions and phones without permission use what's already known
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !hasLocationAccess(context)) {
        return lastLocation(context)
    }
    val manager = context.getSystemService(LocationManager::class.java)
    val done = CountDownLatch(1)
    var found: Pair<Double, Double>? = null

    // Ask the network (or GPS) for a fresh fix; permission can be taken away at any moment
    try {
        val provider = if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
            else LocationManager.GPS_PROVIDER
        manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
            found = location?.let { Pair(it.latitude, it.longitude) }
            done.countDown()
        }
        done.await(LOCATION_WAIT_SECONDS, TimeUnit.SECONDS)
    } catch (error: Exception) {  // permission turned off, or the provider is unavailable
        Log.w("Datebook", "current location failed: ${error.message}")
    }
    return found ?: lastLocation(context)
}
