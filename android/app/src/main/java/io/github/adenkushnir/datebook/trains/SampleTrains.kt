// Made-up NJ Transit trains for Home's train board until the developer account is approved: a sample list of main
// stations (so a trip can be picked in Settings) and a day of departures every 22 or 31 minutes, some late and some
// cancelled, so every kind of row shows
package io.github.adenkushnir.datebook.trains
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong
const val SAMPLE_TRAINS = true  // turn off once NJ Transit approves the account and real trains are tested
private const val FIRST_TRAIN_MINUTES = 5 * 60  // the first sample train leaves around 5 AM
private const val LAST_TRAIN_MINUTES = 24 * 60

// Main stations on the Northeast Corridor and nearby, with their NJ Transit codes, for picking a trip before the
// real list can be loaded
val SAMPLE_STATIONS = listOf(
    Station("NY", "New York Penn Station", 40.7506, -73.9935),
    Station("SE", "Secaucus Upper Lvl", 40.7612, -74.0758),
    Station("HB", "Hoboken", 40.7349, -74.0275),
    Station("NP", "Newark Penn Station", 40.7347, -74.1644),
    Station("NA", "Newark Airport", 40.7044, -74.1909),
    Station("EZ", "Elizabeth", 40.6672, -74.2154),
    Station("RH", "Rahway", 40.6061, -74.2767),
    Station("MP", "Metropark", 40.5684, -74.3297),
    Station("ED", "Edison", 40.5194, -74.4108),
    Station("NB", "New Brunswick", 40.4966, -74.4455),
    Station("PJ", "Princeton Junction", 40.3168, -74.6235),
    Station("TR", "Trenton", 40.2177, -74.7547),
)

// Sample trains from one station to another for yesterday, today and tomorrow in New Jersey time: each station's
// trains leave at its own minutes, the ride takes longer the farther apart they are, and every 7th train is 4
// minutes late and every 11th cancelled
fun sampleDepartures(from: Station, to: Station, nowMillis: Long): List<Train> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(NJ_ZONE).toLocalDate()
    val rideMinutes = (distanceKm(from.lat, from.lng, to.lat, to.lng) * 1.4 + 4).roundToLong()
    val offset = abs(from.code.hashCode()) % 10  // so the two ways of a trip leave at different minutes
    val trains = mutableListOf<Train>()

    // The same timetable on each of the three days
    for (day in listOf(today.minusDays(1), today, today.plusDays(1))) {
        var minutes = FIRST_TRAIN_MINUTES + offset
        var index = 0

        // A train every 31 or 22 minutes until midnight
        while (minutes < LAST_TRAIN_MINUTES) {
            val departs = day.atStartOfDay(NJ_ZONE).plusMinutes(minutes.toLong()).toInstant()
            val arrives = departs.plusSeconds(rideMinutes * 60)
            trains.add(Train(id = "${3800 + index}", line = "Sample", destination = to.name, departs = departs,
                track = if (index % 3 == 0) "1" else "2", secondsLate = if (index % 7 == 3) 240 else 0,
                status = if (index % 11 == 6) "Cancelled" else "",
                stops = listOf(TrainStop(to.code, to.name, arrives))))
            minutes += if (index % 2 == 0) 31 else 22
            index++
        }
    }
    return trains
}

// The stations a trip can be picked from: NJ Transit's list once loaded, else the sample list while trains are made up
fun pickableStations(settings: TrainSettings): List<Station> {
    return settings.stations().ifEmpty { if (SAMPLE_TRAINS) SAMPLE_STATIONS else emptyList() }
}

// When a train really leaves: its time plus how late it is
private fun leaves(train: Train): Long {
    return (train.departs?.toEpochMilli() ?: 0) + train.secondsLate * 1000L
}

// The train that left in words, like "4:42 PM · Track 1 · Departed"
fun departedLine(train: Train): String {
    return boardLine(train).substringBeforeLast(" · ") + " · Departed"
}

// The train board with sample trains: the last train that left and the next 3, between two stations from the list
// (a note instead when a station isn't in it)
fun sampleBoard(stations: List<Station>, from: String, to: String, nowMillis: Long): TrainBoard {
    val start = stations.firstOrNull { it.code == from }
    val end = stations.firstOrNull { it.code == to }

    // A trip saved from another list can't be matched; ask for it again
    if (start == null || end == null) {
        return TrainBoard(start?.name ?: from, end?.name ?: to, emptyList(), "Pick your trip again in Settings.",
            sample = true)
    }
    val trains = sampleDepartures(start, end, nowMillis)
    val last = trains.lastOrNull { leaves(it) <= nowMillis && !isCancelled(it) }  // a cancelled train never left
    val next = trains.filter { leaves(it) > nowMillis }.take(3)
    return TrainBoard(start.name, end.name, next.map { boardLine(it) }, last = last?.let { departedLine(it) } ?: "",
        sample = true, nextDeparture = next.firstOrNull()?.let { leaves(it) } ?: 0)
}
