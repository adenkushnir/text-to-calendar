// Where the sun times are worked out for: the phone's last known location, else the home address, else a guess from
// the time zone; never asks for a permission, and the place it finds stays on the phone
package io.github.adenkushnir.datebook.scenery
import android.content.Context
import android.location.Geocoder
import android.util.Log
import io.github.adenkushnir.datebook.places.lastLocation
import java.time.Instant
import java.time.ZoneId
const val ZONE_GUESS_LATITUDE = 40.0  // a middle latitude, used only when nothing better is known

// A rough place from the time zone alone: its standard offset gives the longitude (15 degrees an hour), and the
// latitude is a middle one, so sun times can be off by up to about half an hour
fun zoneGuess(zone: ZoneId, now: Instant = Instant.now()): Pair<Double, Double> {
    val hours = zone.rules.getStandardOffset(now).totalSeconds / 3600.0
    return Pair(ZONE_GUESS_LATITUDE, hours * 15)
}

// Finds and remembers the place the sun times are for
class SunPlace(private val context: Context) {
    private val prefs = context.getSharedPreferences("datebook-scenery", Context.MODE_PRIVATE)

    // The latitude and longitude to use now; this may look up the home address, so call it off the main thread
    fun find(home: String, zone: ZoneId): Pair<Double, Double> {
        val known = lastLocation(context)

        // A fresh location is best; remember it for when there isn't one
        if (known != null) {
            remember("last", known)
            return known
        }
        return recall("last") ?: homePlace(home) ?: zoneGuess(zone)
    }

    // The home address as a place, found once with Android's geocoder and then remembered (null without a home
    // address, a geocoder or an answer)
    private fun homePlace(home: String): Pair<Double, Double>? {
        // Nothing to find without a home address or a geocoder
        if (home.isBlank() || !Geocoder.isPresent()) {
            return null
        }

        // Use the remembered place while the address hasn't changed
        if (prefs.getString("home-address", null) == home) {
            return recall("home")
        }

        // The geocoder needs the internet; any failure just means no home place yet
        return try {
            @Suppress("DEPRECATION")
            val found = Geocoder(context).getFromLocationName(home, 1)?.firstOrNull() ?: return null
            val place = Pair(found.latitude, found.longitude)
            remember("home", place)
            prefs.edit().putString("home-address", home).apply()
            place
        } catch (error: Exception) {  // no internet, or the geocoder couldn't read the address
            Log.w("Datebook", "home place lookup failed: ${error.message}")
            null
        }
    }

    // Save a place under a name
    private fun remember(name: String, place: Pair<Double, Double>) {
        prefs.edit().putFloat("$name-lat", place.first.toFloat()).putFloat("$name-lng", place.second.toFloat()).apply()
    }

    // A place saved under a name, or null if there isn't one
    private fun recall(name: String): Pair<Double, Double>? {
        // Nothing saved yet
        if (!prefs.contains("$name-lat")) {
            return null
        }
        return Pair(prefs.getFloat("$name-lat", 0f).toDouble(), prefs.getFloat("$name-lng", 0f).toDouble())
    }
}
