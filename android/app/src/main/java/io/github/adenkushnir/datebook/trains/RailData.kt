// NJ Transit's RailData API: signs in with your developer account (a token is kept and reused, since NJ Transit only
// allows 5 new tokens a day) and reads a station's live departure board: each train's time, track, delay, status and
// stops
package io.github.adenkushnir.datebook.trains
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
const val RAIL_DATA_URL = "https://raildata.njtransit.com/api/TrainData/"
val NJ_ZONE: ZoneId = ZoneId.of("America/New_York")
private const val TIMEOUT_MILLIS = 15000
private val MAPPER = ObjectMapper()
private val BOARD_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy hh:mm:ss a", Locale.US)

// One stop a train makes: the station's code and name, and when the train is there (null when unknown)
data class TrainStop(val code: String, val name: String, val time: Instant?)

// One train on a departure board: its number, line, destination, departure time, track, how late it is in seconds,
// its status ("On Time", "Cancelled"...), and its stops
data class Train(
    val id: String,
    val line: String,
    val destination: String,
    val departs: Instant?,
    val track: String,
    val secondsLate: Int,
    val status: String,
    val stops: List<TrainStop>,
)

// Read a RailData time, like "13-Nov-2037 05:12:00 PM" in New Jersey time (null when missing or unreadable)
fun boardTime(text: String?): Instant? {
    // Missing times stay unknown
    if (text.isNullOrBlank()) {
        return null
    }

    // Times that can't be read stay unknown
    return try {
        LocalDateTime.parse(text.trim(), BOARD_TIME).atZone(NJ_ZONE).toInstant()
    } catch (error: Exception) {  // an unexpected format
        null
    }
}

// Read a station's departure board from getTrainSchedule's reply
fun parseBoard(json: String): List<Train> {
    val items = MAPPER.readTree(json).path("ITEMS")
    return items.map { item -> parseTrain(item) }
}

// Read one train from the board
private fun parseTrain(item: JsonNode): Train {
    val stops = item.path("STOPS").map { stop ->
        val time = boardTime(stop.path("DEP_TIME").asText(null)) ?: boardTime(stop.path("TIME").asText(null))
        TrainStop(stop.path("STATION_2CHAR").asText(""), stop.path("STATIONNAME").asText(""), time)
    }
    return Train(
        id = item.path("TRAIN_ID").asText("").trim(),
        line = item.path("LINE").asText("").trim(),
        destination = item.path("DESTINATION").asText("").trim(),
        departs = boardTime(item.path("SCHED_DEP_DATE").asText(null)),
        track = item.path("TRACK").asText("").trim(),
        secondsLate = item.path("SEC_LATE").asText("0").trim().toIntOrNull() ?: 0,
        status = item.path("STATUS").asText("").trim(),
        stops = stops,
    )
}

// Return true if a train is cancelled
fun isCancelled(train: Train): Boolean {
    return train.status.contains("cancel", ignoreCase = true)
}

// When a train reaches a station (by its two-letter code), or null if it doesn't stop there after it leaves
fun arrivalAt(train: Train, code: String): Instant? {
    val stop = train.stops.firstOrNull { it.code == code } ?: return null
    val time = stop.time ?: return null
    return if (train.departs == null || time.isAfter(train.departs)) time else null  // only stops after departure
}

// Talks to RailData with your developer account; the token is saved through the given functions so it survives
// restarts
class RailDataClient(
    private val username: () -> String?,
    private val password: () -> String?,
    private val loadToken: () -> String?,
    private val saveToken: (String) -> Unit,
) {
    // Return a station's live departure board, or an empty list on any failure (logged); this waits on the network,
    // so call it off the main thread
    fun departures(station: String): List<Train> {
        // Any network or reply problem gives an empty board
        return try {
            parseBoard(call("getTrainSchedule", mapOf("station" to station)))
        } catch (error: Exception) {  // no internet, no account, or an unreadable reply
            Log.w("Datebook", "NJ Transit board failed for $station: ${error.message}")
            emptyList()
        }
    }

    // Return NJ Transit's station codes and names, or an empty list on any failure (logged)
    fun stationNames(): List<Pair<String, String>> {
        // Any network or reply problem gives no stations
        return try {
            MAPPER.readTree(call("getStationList", emptyMap())).map {
                Pair(it.path("STATION_2CHAR").asText(""), it.path("STATIONNAME").asText(""))
            }
        } catch (error: Exception) {  // no internet, no account, or an unreadable reply
            Log.w("Datebook", "NJ Transit station list failed: ${error.message}")
            emptyList()
        }
    }

    // Call a RailData method with the saved token, getting a new token once if the old one was refused
    private fun call(method: String, fields: Map<String, String>): String {
        val token = loadToken() ?: newToken()
        val reply = post(method, fields + ("token" to token))

        // Get a fresh token and try once more when the saved one has expired
        if (reply.second.contains("Invalid token", ignoreCase = true)) {
            val retry = post(method, fields + ("token" to newToken()))
            check(retry.first) { "RailData $method error: ${retry.second.take(200)}" }
            return retry.second
        }
        check(reply.first) { "RailData $method error: ${reply.second.take(200)}" }
        return reply.second
    }

    // Sign in with the developer account and save the new token
    private fun newToken(): String {
        val user = username()
        val pass = password()
        check(!user.isNullOrBlank() && !pass.isNullOrBlank()) { "no NJ Transit account saved" }
        val reply = post("getToken", mapOf("username" to user, "password" to pass))
        check(reply.first) { "NJ Transit sign-in failed: ${reply.second.take(200)}" }
        val token = MAPPER.readTree(reply.second).path("UserToken").asText("")
        check(token.isNotBlank()) { "NJ Transit sign-in gave no token" }
        saveToken(token)
        return token
    }
}

// Send a RailData request as a multipart form and return whether it worked and the reply's text
private fun post(method: String, fields: Map<String, String>): Pair<Boolean, String> {
    val boundary = "datebook-${UUID.randomUUID()}"
    val connection = URL(RAIL_DATA_URL + method).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.connectTimeout = TIMEOUT_MILLIS
    connection.readTimeout = TIMEOUT_MILLIS
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    connection.setRequestProperty("Accept", "text/plain")

    // Always close the connection, even when the request fails
    try {
        connection.outputStream.use { it.write(formBody(fields, boundary).toByteArray(Charsets.UTF_8)) }
        val ok = connection.responseCode in 200..299
        val stream = if (ok) connection.inputStream else connection.errorStream  // the error explains a refusal
        return Pair(ok, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } finally {
        connection.disconnect()
    }
}

// The form body: each field as its own part between boundary lines
fun formBody(fields: Map<String, String>, boundary: String): String {
    val parts = fields.map { (name, value) ->
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
    }
    return parts.joinToString("") + "--$boundary--\r\n"
}
