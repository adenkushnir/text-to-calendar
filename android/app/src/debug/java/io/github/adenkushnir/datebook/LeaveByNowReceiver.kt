// Debug builds only: lets an adb command show the leave-by alert for the next event with a place right away, instead
// of waiting for its time; what happened is logged under the tag Datebook
package io.github.adenkushnir.datebook
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.leaveby.leaveByCandidates
import io.github.adenkushnir.datebook.leaveby.showLeaveBy
import io.github.adenkushnir.datebook.leaveby.tripText
import io.github.adenkushnir.datebook.places.travelMinutes
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Receives the adb broadcast and shows the next event's leave-by alert in the background
class LeaveByNowReceiver : BroadcastReceiver() {
    // Find the next event with a place in the coming 4 hours, work out its travel time and show its alert
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // Always tell Android the work is done, even if showing it fails
            try {
                val graph = AppGraph.get(appContext)
                val now = System.currentTimeMillis()
                val events = calendarEvents(appContext, now, now + 4 * 3_600_000, graph.calendarSwitches.switchedOff())
                val event = leaveByCandidates(events, now).firstOrNull()

                // Say so when no event in the next 4 hours has a place
                if (event == null) {
                    Log.i("Datebook", "leave-by now: no event with a place in the next 4 hours")
                    return@launch
                }
                val mode = LeaveByStore(appContext).mode(eventKey(event.title, event.begin))
                    ?: graph.travelSettings.mode()
                val minutes = travelMinutes(appContext, graph.mapsKeyStore.load(), graph.travelSettings.home(),
                    event.location, mode, Instant.ofEpochMilli(event.begin))

                // Say so when there's no travel time
                if (minutes == null) {
                    Log.i("Datebook", "leave-by now: no travel time for ${event.title}")
                    return@launch
                }
                showLeaveBy(appContext, event, minutes, mode)
                Log.i("Datebook", "leave-by now: ${event.title} · ${tripText(minutes, mode)}")
            } finally {
                pending.finish()
            }
        }
    }
}
