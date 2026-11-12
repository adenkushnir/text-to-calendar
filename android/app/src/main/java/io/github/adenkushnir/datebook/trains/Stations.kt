// NJ Transit rail stations with their locations: codes and names from RailData, coordinates from NJ Transit's rail
// timetable file (GTFS-RAIL, downloaded on the phone, never kept in the repo), matched by name; plus finding the
// stations nearest a spot and the saved trip for the Next trains card
package io.github.adenkushnir.datebook.trains
import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
const val GTFS_RAIL_URL = "https://www.njtransit.com/rail_data.zip"
const val NEARBY_KM = 3.2  // about 2 miles
private const val REFRESH_MILLIS = 30L * 24 * 60 * 60 * 1000
private val MAPPER = ObjectMapper()

// One rail station: RailData's two-letter code, its name, and where it is
data class Station(val code: String, val name: String, val lat: Double, val lng: Double)

// A station name made comparable: lowercase letters and digits only, without words like "station"
fun nameKey(name: String): String {
    return name.lowercase().replace("station", "").replace("rail", "").replace(Regex("[^a-z0-9]"), "")
}

// Read the stops in a GTFS stops.txt (stop_name, stop_lat, stop_lon) as name key → coordinates
fun parseStops(text: String): Map<String, Pair<Double, Double>> {
    val lines = text.lines().filter { it.isNotBlank() }
    val header = lines.firstOrNull()?.let { splitCsv(it) } ?: return emptyMap()
    val name = header.indexOf("stop_name")
    val lat = header.indexOf("stop_lat")
    val lng = header.indexOf("stop_lon")

    // Without the needed columns there's nothing to read
    if (name < 0 || lat < 0 || lng < 0) {
        return emptyMap()
    }
    val stops = mutableMapOf<String, Pair<Double, Double>>()

    // Read each stop's name and coordinates
    for (line in lines.drop(1)) {
        val cells = splitCsv(line)
        val latitude = cells.getOrNull(lat)?.toDoubleOrNull() ?: continue  // skip rows without a location
        val longitude = cells.getOrNull(lng)?.toDoubleOrNull() ?: continue
        stops[nameKey(cells.getOrNull(name).orEmpty())] = Pair(latitude, longitude)
    }
    return stops
}

// Split one CSV line into cells, keeping commas inside quotes
private fun splitCsv(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false

    // Walk the line a character at a time
    for (char in line) {
        // Quotes switch quoting; commas outside quotes end a cell
        when {
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> cells.add(cell.toString().trim()).also { cell.clear() }
            else -> cell.append(char)
        }
    }
    cells.add(cell.toString().trim())
    return cells
}

// Join RailData's station codes and names with the timetable's coordinates by name; stations without a match are left
// out
fun joinStations(names: List<Pair<String, String>>, stops: Map<String, Pair<Double, Double>>): List<Station> {
    return names.mapNotNull { (code, name) ->
        val point = stops[nameKey(name)] ?: return@mapNotNull null  // no location known
        Station(code, name, point.first, point.second)
    }
}

// The distance between two points on Earth in kilometres
fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * 6371 * asin(sqrt(a))
}

// The up to 3 stations closest to a point, within about 2 miles, closest first
fun nearestStations(lat: Double, lng: Double, stations: List<Station>, count: Int = 3): List<Station> {
    val near = stations.map { Pair(it, distanceKm(lat, lng, it.lat, it.lng)) }.filter { it.second <= NEARBY_KM }
    return near.sortedBy { it.second }.take(count).map { it.first }
}

// Download NJ Transit's rail timetable file and read its stops; empty on any failure (logged)
fun downloadStops(): Map<String, Pair<Double, Double>> {
    val connection = URL(GTFS_RAIL_URL).openConnection() as HttpURLConnection

    // Any network or file problem gives no locations
    return try {
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        check(connection.responseCode in 200..299) { "GTFS-RAIL download error ${connection.responseCode}" }
        connection.inputStream.use { readStopsFromZip(it) }
    } catch (error: Exception) {  // no internet, a refused download, or an unreadable file
        Log.w("Datebook", "NJ Transit timetable download failed: ${error.message}")
        emptyMap()
    } finally {
        connection.disconnect()
    }
}

// Find stops.txt in the timetable zip and read it
fun readStopsFromZip(zip: InputStream): Map<String, Pair<Double, Double>> {
    ZipInputStream(zip).use { entries ->
        var entry = entries.nextEntry

        // Look through the zip's files for stops.txt
        while (entry != null) {
            // Read the stops file and stop looking
            if (entry.name.endsWith("stops.txt")) {
                return parseStops(entries.readBytes().toString(Charsets.UTF_8))
            }
            entry = entries.nextEntry
        }
    }
    return emptyMap()
}

// Remembers the station list (refreshed monthly) and the saved trip for the Next trains card
class TrainSettings(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-trains", Context.MODE_PRIVATE)

    // Return the saved stations, or an empty list until they've been loaded
    fun stations(): List<Station> {
        val saved = prefs.getString("stations", null) ?: return emptyList()
        return MAPPER.readTree(saved).map {
            Station(it.path("code").asText(""), it.path("name").asText(""), it.path("lat").asDouble(),
                it.path("lng").asDouble())
        }
    }

    // Save the station list and when it was loaded
    fun setStations(stations: List<Station>, nowMillis: Long) {
        val plain = stations.map { mapOf("code" to it.code, "name" to it.name, "lat" to it.lat, "lng" to it.lng) }
        prefs.edit().putString("stations", MAPPER.writeValueAsString(plain)).putLong("loaded", nowMillis).apply()
    }

    // Return true if the station list is missing or over a month old
    fun stationsDue(nowMillis: Long): Boolean {
        return stations().isEmpty() || nowMillis - prefs.getLong("loaded", 0) > REFRESH_MILLIS
    }

    // Return the saved trip's two station codes (from, to), or null until both are set
    fun trip(): Pair<String, String>? {
        val from = prefs.getString("from", null) ?: return null
        val to = prefs.getString("to", null) ?: return null
        return Pair(from, to)
    }

    // Save the trip's stations
    fun setTrip(from: String, to: String) {
        prefs.edit().putString("from", from).putString("to", to).apply()
    }
}

// Load the station list with locations if it's missing or a month old; returns how many stations are known; this
// waits on the network, so call it off the main thread
fun refreshStations(settings: TrainSettings, client: RailDataClient, nowMillis: Long): Int {
    // Keep a recent list
    if (!settings.stationsDue(nowMillis)) {
        return settings.stations().size
    }
    val stations = joinStations(client.stationNames(), downloadStops())

    // Only replace the list with a real one
    if (stations.isNotEmpty()) {
        settings.setStations(stations, nowMillis)
    }
    Log.i("Datebook", "NJ Transit stations loaded: ${stations.size}")
    return settings.stations().size
}
