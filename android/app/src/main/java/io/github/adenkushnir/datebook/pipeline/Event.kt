// One event Claude found in a text, with the same fields as the Python version's event dicts
package io.github.adenkushnir.datebook.pipeline

// An event's details: start and end are YYYY-MM-DD or YYYY-MM-DDTHH:MM, written in timezone (empty means the phone's)
data class Event(
    val title: String,
    val start: String,
    val end: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    val repeat: String = "",
    val timezone: String = "",
    val phoneTimezone: String = "",
)
