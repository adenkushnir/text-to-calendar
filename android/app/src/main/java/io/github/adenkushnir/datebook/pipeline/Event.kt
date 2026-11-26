// One event Claude found in a text, with the same fields as the Python version's event dicts
package io.github.adenkushnir.datebook.pipeline
const val KIND_EVENT = "event"

// An event's details: start and end are YYYY-MM-DD or YYYY-MM-DDTHH:MM, written in timezone (empty means the phone's);
// for a text that moves or cancels a calendar event, change is "moved" or "cancelled", calendarRef is the event's
// number in the list Claude was shown, and calendarEventId is that event's id on the phone; flight is a flight number
// like UA1234, packageCarrier and trackingNumber describe a delivery, confirmation is a booking or order code,
// placeLat and placeLng are the place's coordinates from the address lookup (null when unknown), and kind says
// whether it's a plan ("event"), a loose plan without a set date ("loose"), something due ("due", with its
// amount) or an assignment ("assignment"), trip names the trip a travel booking belongs to, bring is what the text
// says to bring ("cleats, $20"), outdoor marks a plan that happens outside, and link is a page for it from the message
// (pay a bill, view an order, manage a booking), and packageStatus says whether a package shipped, is arriving, is
// out for delivery or was delivered
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
    val boarding: String = "",
    val packageCarrier: String = "",
    val trackingNumber: String = "",
    val confirmation: String = "",
    val kind: String = KIND_EVENT,
    val amount: String = "",
    val trip: String = "",
    val bring: String = "",
    val outdoor: Boolean = false,
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    val link: String = "",
    val packageStatus: String = "",
)
