// Sunrise and sunset for a day and place, worked out on the phone with NOAA's standard formulas (no internet needed)
package io.github.adenkushnir.datebook.scenery
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// When the sun rises and sets, in minutes after local midnight (6:43 AM is 403.0)
data class SunTimes(val rise: Double, val set: Double)

// Work out sunrise and sunset for a date at a latitude and longitude, in a time zone (summer time included); in polar
// night the sun never rises, so both come out at noon, and in polar day sunrise is midnight and sunset the next midnight
fun sunTimes(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): SunTimes {
    val angle = 2 * PI / 365 * (date.dayOfYear - 1)
    val equation = 229.18 * (0.000075 + 0.001868 * cos(angle) - 0.032077 * sin(angle) - 0.014615 * cos(2 * angle) -
        0.040849 * sin(2 * angle))
    val tilt = 0.006918 - 0.399912 * cos(angle) + 0.070257 * sin(angle) - 0.006758 * cos(2 * angle) +
        0.000907 * sin(2 * angle) - 0.002697 * cos(3 * angle) + 0.00148 * sin(3 * angle)
    val place = Math.toRadians(latitude)
    val cosHour = cos(Math.toRadians(90.833)) / (cos(place) * cos(tilt)) - tan(place) * tan(tilt)
    val hourAngle = Math.toDegrees(acos(cosHour.coerceIn(-1.0, 1.0)))  // clamped for polar day and night
    val offset = zone.rules.getOffset(date.atTime(12, 0).atZone(zone).toInstant()).totalSeconds / 60.0
    return SunTimes(
        rise = 720 - 4 * (longitude + hourAngle) - equation + offset,
        set = 720 - 4 * (longitude - hourAngle) - equation + offset,
    )
}
