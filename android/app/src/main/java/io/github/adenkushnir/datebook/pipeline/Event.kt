// One event Claude found in a text, with the same fields as the Python version's event dicts
package io.github.adenkushnir.datebook.pipeline

// An event's details: start and end are YYYY-MM-DD or YYYY-MM-DDTHH:MM, written in timezone (empty means the phone's);
// for a text that moves or cancels a calendar event, change is "moved" or "cancelled", calendarRef is the event's
// number in the list Claude was shown, and calendarEventId is that event's id on the phone; flight is a flight number
// like UA1234, packageCarrier and trackingNumber describe a delivery, and placeLat and placeLng are the place's
// coordinates from the address lookup (null when unknown)
data class Event(
    val title: String,
    val start: String,
    val end: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    val repeat: String = "",
    val timezone: String = "",
    val phoneTimezone: String = "",
    val change: String = "",
    val calendarRef: Int = 0,
    val calendarEventId: Long = 0,
    val flight: String = "",
    val packageCarrier: String = "",
    val trackingNumber: String = "",
    val placeLat: Double? = null,
    val placeLng: Double? = null,
)
