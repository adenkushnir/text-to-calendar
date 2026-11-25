// Datebook's link to Nestor, the home speaker and wall screen app, on the home Wi-Fi only: finds Nestor by mDNS,
// pairs with its 6-digit code, keeps Nestor's snapshot of Datebook up to date, collects the actions the wall or Hey
// John ask for (add, ignore, done, new plan), and passes on the alerts Nestor speaks; away from home nothing is sent
// and nothing is queued
package io.github.adenkushnir.datebook.nestor
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.calendar.calendarChanges
import io.github.adenkushnir.datebook.calendar.calendarEvents
import io.github.adenkushnir.datebook.calendar.phoneCalendars
import io.github.adenkushnir.datebook.data.FollowUp
import io.github.adenkushnir.datebook.data.KIND_ASSIGNMENT
import io.github.adenkushnir.datebook.data.KIND_DUE
import io.github.adenkushnir.datebook.data.KIND_REMINDER
import io.github.adenkushnir.datebook.data.Suggestion
import io.github.adenkushnir.datebook.data.finishFollowUp
import io.github.adenkushnir.datebook.data.nextRepeat
import io.github.adenkushnir.datebook.deliberate.effectsFor
import io.github.adenkushnir.datebook.fencing.FencingSettings
import io.github.adenkushnir.datebook.fencing.fencingTiles
import io.github.adenkushnir.datebook.followups.FOLLOW_UP_IDS
import io.github.adenkushnir.datebook.followups.bookNextFollowUp
import io.github.adenkushnir.datebook.deliberate.ACT_NEW_PLAN
import io.github.adenkushnir.datebook.deliberate.Change
import io.github.adenkushnir.datebook.deliberate.DeliberateRunner
import io.github.adenkushnir.datebook.journeys.JourneyStore
import io.github.adenkushnir.datebook.leaveby.LeaveByStore
import io.github.adenkushnir.datebook.leaveby.NETWORK_NONE
import io.github.adenkushnir.datebook.leaveby.eventKey
import io.github.adenkushnir.datebook.leaveby.flightFor
import io.github.adenkushnir.datebook.leaveby.leavePlan
import io.github.adenkushnir.datebook.lists.PackageStore
import io.github.adenkushnir.datebook.lists.tripsOf
import io.github.adenkushnir.datebook.pipeline.ApiKeyStore
import io.github.adenkushnir.datebook.prompts.ignoreSuggestion
import io.github.adenkushnir.datebook.widgets.updateWidgets
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
const val SERVICE_TYPE = "_nestor._tcp"
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val TAG = "Datebook"
private const val WAIT_SECONDS = 50
private const val CONNECT_MILLIS = 5_000
private const val READ_MILLIS = 20_000
private const val POLL_READ_MILLIS = (WAIT_SECONDS + 20) * 1000
private const val RETRY_MILLIS = 15_000L
private const val REFRESH_MILLIS = 10 * 60_000L  // the snapshot is sent again every 10 minutes, as times move on
private const val DEBOUNCE_MILLIS = 2_000L
private const val PROBE_MILLIS = 60_000L
private const val PROBE_TIMEOUT_MILLIS = 3_000
private val MAPPER = ObjectMapper()

// Where Nestor is (null until it's found on this Wi-Fi), whether Datebook is paired, when Nestor last answered (0
// for never), and the last problem in words ("" for none)
data class NestorStatus(val address: String? = null, val paired: Boolean = false, val lastContact: Long = 0,
    val problem: String = "")

// Remembers the pairing token (encrypted) and whether the link is switched on
class NestorSettings(context: Context) {
    private val token = ApiKeyStore(context, "nestor-token")
    private val prefs = context.getSharedPreferences("datebook-nestor", Context.MODE_PRIVATE)

    // The token Nestor gave when pairing, or null when not paired
    fun token(): String? {
        return token.load()?.takeIf { it.isNotBlank() }
    }

    // Save the token from pairing
    fun setToken(value: String) {
        token.save(value)
    }

    // Forget the token (unpaired)
    fun clearToken() {
        token.clear()
    }

    // Return true if Datebook talks to Nestor while paired (on to start)
    fun isOn(): Boolean {
        return prefs.getBoolean("on", true)
    }

    // Switch the link on or off
    fun setOn(on: Boolean) {
        prefs.edit().putBoolean("on", on).apply()
    }

    // Where Nestor was last found ("192.168.1.17:8765"), or null before the first time
    fun lastAddress(): String? {
        return prefs.getString("address", null)
    }

    // Remember where Nestor was found
    fun setLastAddress(address: String) {
        prefs.edit().putString("address", address).apply()
    }
}

// Return true if Datebook may reach devices on the Wi-Fi (Android 17 asks; older versions always allow it)
fun hasLocalNetwork(context: Context): Boolean {
    return Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
}

// The link: one per app, started by the notification listener (which runs all the time) and by Settings → Nestor
object NestorLink {
    private val state = MutableStateFlow(NestorStatus())
    val status: StateFlow<NestorStatus> = state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    private val refresh = MutableStateFlow(0)

    // Start looking for Nestor and, once found and paired, keep it up to date (does nothing when already running)
    @Synchronized
    fun start(context: Context) {
        val appContext = context.applicationContext
        state.value = state.value.copy(paired = NestorSettings(appContext).token() != null)

        // One link at a time
        if (running?.isActive == true) {
            return
        }
        running = scope.launch {
            // Follow Nestor as it's found (by mDNS, or at its last address) and lost, with a session while it's there
            val found = merge(discover(appContext), checkLastAddress(appContext)).distinctUntilChanged()
            found.collectLatest { address ->
                Log.i(TAG, "nestor: ${if (address == null) "not found" else "found at $address"}")
                state.value = state.value.copy(address = address)

                // Remember it, so it's found straight away next time
                if (address != null) {
                    NestorSettings(appContext).setLastAddress(address)
                }

                // A session needs Nestor, the permission, a token and the switch on
                if (address != null) {
                    session(appContext, address)
                }
            }
        }
    }

    // Stop the link (when the listener stops)
    @Synchronized
    fun stop() {
        running?.cancel()
        running = null
        state.value = state.value.copy(address = null)
    }

    // Start again from scratch, after pairing, unpairing or switching it on or off
    fun restart(context: Context) {
        stop()
        start(context)
    }

    // Send the snapshot again soon (after a change Datebook's watched data doesn't show, like a package marked
    // received)
    fun refreshSoon() {
        refresh.value += 1
    }

    // Nestor's address as it's found and lost on the Wi-Fi (null when it isn't there); only home addresses count
    private fun discover(context: Context): Flow<String?> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        trySend(null)

        // Without the local network permission nothing can be found
        if (!hasLocalNetwork(context)) {
            state.value = state.value.copy(problem = "Allow Datebook to find devices on your Wi-Fi.")
            awaitClose {}
            return@callbackFlow
        }
        val listener = object : NsdManager.DiscoveryListener {
            // Nestor appeared: look up its address
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(TAG, "nestor: service seen, looking up its address")
                resolve(nsd, info) { address -> trySend(address) }
            }

            // Nestor went away (another network, or it stopped)
            override fun onServiceLost(info: NsdServiceInfo) {
                trySend(null)
            }

            // Nothing to do when the search starts or stops
            override fun onDiscoveryStarted(serviceType: String) {
            }

            // Nothing to do when the search stops
            override fun onDiscoveryStopped(serviceType: String) {
            }

            // Searching failed to start
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "nestor: discovery failed ($errorCode)")
            }

            // Stopping failed; nothing to do
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    // Nestor at its last address, checked now and every minute while it isn't found (mDNS answers can take minutes
    // to reach the phone); only a home-network address that answers like Nestor counts
    private fun checkLastAddress(context: Context): Flow<String> = flow {
        // Check whenever Nestor isn't found
        while (true) {
            val last = NestorSettings(context).lastAddress()

            // Only when it isn't found and there's an address to try
            if (state.value.address == null && last != null && isNestorAt(last)) {
                emit(last)
            }
            delay(PROBE_MILLIS)
        }
    }

    // Return true if Nestor answers at an address on the home network
    private suspend fun isNestorAt(address: String): Boolean = withContext(Dispatchers.IO) {
        val host = address.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val home = runCatching { isHomeAddress(InetAddress.getByName(host)) }.getOrDefault(false)

        // Never try an address outside the home network
        if (!home) {
            return@withContext false
        }
        runCatching {
            val connection = URL("http://$address/health").openConnection() as HttpURLConnection
            connection.connectTimeout = PROBE_TIMEOUT_MILLIS
            connection.readTimeout = PROBE_TIMEOUT_MILLIS
            val reply = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            reply.contains("\"speaker\"") && reply.contains("\"ok\"")
        }.getOrDefault(false)
    }

    // Look up a found service's address and port, keeping only home-network addresses
    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, found: (String) -> Unit) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            // Use the address when it's on the home network
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host: InetAddress = resolved.host ?: return

                // Never talk to anything outside the home network
                if (!isHomeAddress(host)) {
                    Log.w(TAG, "nestor: ignored an address outside the home network")
                } else {
                    val name = host.hostAddress.orEmpty()
                    found(if (name.contains(":")) "[$name]:${resolved.port}" else "$name:${resolved.port}")
                }
            }

            // A failed look-up waits for the next announcement
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "nestor: resolve failed ($errorCode)")
            }
        })
    }

    // While Nestor is there and Datebook is paired and on: keep the snapshot up to date and collect actions
    private suspend fun session(context: Context, address: String) {
        val settings = NestorSettings(context)
        val token = settings.token()

        val step = if (token == null) "waits for pairing" else if (settings.isOn()) "starts" else "is off"
        Log.i(TAG, "nestor: session $step")

        // Without a token or with the switch off there's nothing to send
        if (token == null || !settings.isOn()) {
            state.value = state.value.copy(problem = if (token == null) "" else "Switched off.")
            return
        }
        state.value = state.value.copy(problem = "")
        kotlinx.coroutines.coroutineScope {
            launch { sendSnapshots(context, address, token) }
            collectActions(context, address, token)
        }
    }

    // Send the snapshot whenever Datebook's data or the calendar changes (2 seconds after the last change), and every
    // 10 minutes
    @OptIn(FlowPreview::class)
    private suspend fun sendSnapshots(context: Context, address: String, token: String) {
        val graph = AppGraph.get(context)
        val ticks = flow {
            // A tick now and every 10 minutes
            while (true) {
                emit(Unit)
                delay(REFRESH_MILLIS)
            }
        }
        val changes = merge(ticks, refresh, calendarChanges(context),
            graph.dao.watchSuggestions(Suggestion.STATUS_WAITING), graph.dao.watchOpenFollowUps(KIND_DUE),
            graph.dao.watchOpenFollowUps(KIND_ASSIGNMENT),
            graph.dao.watchReminders(), graph.dao.watchNotes(), graph.dao.watchPackages(), graph.dao.watchTripPlans())
        changes.debounce(DEBOUNCE_MILLIS).collect {
            val body = MAPPER.writeValueAsString(snapshotOf(snapshotParts(context), ZoneId.systemDefault()))
            call(address, "PUT", "/datebook/state", token, body)
        }
    }

    // Gather everything the snapshot needs from Datebook and the phone's calendar
    private suspend fun snapshotParts(context: Context): SnapshotParts {
        val graph = AppGraph.get(context)
        val dao = graph.dao
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val received = PackageStore(context).received()
        val (from, to) = snapshotWindow(now, zone)
        val switchedOff = graph.calendarSwitches.switchedOff()
        val names = runCatching { phoneCalendars(context).associate { it.id to it.name } }.getOrDefault(emptyMap())
        val events = runCatching { calendarEvents(context, from, to, switchedOff) }.getOrDefault(emptyList())
        val rules = graph.deliberateDao.rules()
        val leaveBy = LeaveByStore(context)
        val journeys = JourneyStore(context)
        val fencing = FencingSettings(context)
        val stations = graph.trainSettings.stations().associate { it.code to it.name }
        val trip = graph.trainSettings.trip()?.let {
            mapOf("from" to it.first, "to" to it.second, "from_name" to (stations[it.first] ?: it.first),
                "to_name" to (stations[it.second] ?: it.second))
        }
        return SnapshotParts(
            waiting = dao.suggestionsWithStatus(Suggestion.STATUS_WAITING),
            packages = dao.packagesNow().filter { it.id !in received },
            bills = dao.openFollowUps(KIND_DUE),
            assignments = dao.openFollowUps(KIND_ASSIGNMENT),
            trips = tripsOf(dao.allSuggestions().filter { it.trip.isNotBlank() && it.status != "ignored" },
                LocalDate.now(zone)),
            notes = dao.allOpenFollowUps().filter { it.kind == "note" },
            reminders = dao.openFollowUps(KIND_REMINDER),
            trip = trip,
            fencing = if (fencing.isOn()) fencingTiles(fencing.tournaments(), now, zone) else emptyList(),
            events = events.map { event ->
                val key = eventKey(event.title, event.begin)
                val effects = effectsFor(rules, event.title, event.location)
                val booked = leaveBy.bookedFor(key) != null
                val plan = if (booked) leavePlan(context, event, effects, NETWORK_NONE) else null
                SnapshotEvent(event, names[event.calendarId].orEmpty(), plan?.let { PlannedLeave(it.leaveAt,
                    it.travel, it.early) }, plan?.train, flightFor(context, event.title, event.begin).second,
                    journeys.journey(key)?.first)
            },
        )
    }

    // Wait for actions from the wall or Hey John (each wait lasts up to 50 seconds, which also tells Nestor the phone
    // is home) and carry each out
    private suspend fun collectActions(context: Context, address: String, token: String) {
        // Keep waiting while the session lasts
        while (currentCoroutineContext().isActive) {
            val reply = call(address, "GET", "/datebook/actions?wait=$WAIT_SECONDS", token, null, POLL_READ_MILLIS)

            // Nothing back: wait a little before asking again (unpaired calls stop the session)
            if (reply == null) {
                delay(RETRY_MILLIS)
                continue
            }
            val actions = runCatching { MAPPER.readTree(reply).path("actions") }.getOrNull() ?: continue

            // Carry out each action on the link's own scope, so a restarting session can't cut one off halfway
            for (action in actions) {
                scope.launch {
                    runCatching {
                        perform(context, action.path("action").asText(""), action.path("item").asLong(0),
                            action.path("text").asText(""))
                    }.onFailure { Log.w(TAG, "nestor: action failed: ${it.message}") }
                }
            }
        }
    }

    // Send one request to Nestor with the token; returns the reply's body, or null on any failure (a rejected token
    // unpairs, since Nestor no longer knows this phone)
    private suspend fun call(address: String, method: String, path: String, token: String?, body: String?,
        readMillis: Int = READ_MILLIS): String? = withContext(Dispatchers.IO) {
        try {
            val connection = URL("http://$address$path").openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_MILLIS
            connection.readTimeout = readMillis
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

            // Send the body as JSON when there is one
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            // A rejected token means Nestor forgot this phone
            if (code == 401 && token != null) {
                state.value = state.value.copy(paired = false, problem = "Nestor doesn't know this phone any more. " +
                    "Pair again.")
                return@withContext null
            }
            state.value = state.value.copy(lastContact = System.currentTimeMillis())
            if (code < 400) text else null
        } catch (error: Exception) {  // Nestor stopped, the Wi-Fi dropped, or a timeout
            Log.w(TAG, "nestor: $method $path failed: ${error.message}")
            null
        }
    }

    // Pair with the code Nestor printed; returns the problem in words, or null once paired
    suspend fun pair(context: Context, code: String): String? {
        val address = state.value.address ?: return "Nestor isn't found on this Wi-Fi yet."
        val body = MAPPER.writeValueAsString(mapOf("code" to code.trim(), "device" to Build.MODEL))
        val reply = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("http://$address/pair").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_MILLIS
                connection.readTimeout = READ_MILLIS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code2 = connection.responseCode
                val text = (if (code2 < 400) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                Pair(code2, text)
            }.getOrNull()
        } ?: return "Couldn't reach Nestor.".also { Log.w(TAG, "nestor: pairing couldn't reach $address") }
        val json = runCatching { MAPPER.readTree(reply.second) }.getOrNull()
        val token = json?.path("token")?.asText("").orEmpty()

        Log.i(TAG, "nestor: pairing answered ${reply.first}")

        // A wrong or old code gets Nestor's reason back
        if (reply.first >= 400 || token.isBlank()) {
            return json?.path("error")?.asText("")?.ifBlank { null } ?: "Nestor didn't accept the code."
        }
        NestorSettings(context).setToken(token)
        restart(context)
        return null
    }

    // Unpair: tell Nestor (when it's there) and forget the token
    suspend fun unpair(context: Context) {
        val settings = NestorSettings(context)
        val address = state.value.address
        val token = settings.token()

        // Tell Nestor to forget this phone, when it can be reached
        if (address != null && token != null) {
            call(address, "DELETE", "/pair", token, null)
        }
        settings.clearToken()
        state.value = state.value.copy(paired = false, problem = "")
        restart(context)
    }

    // Pass a Datebook alert to Nestor to speak, when Nestor is found, paired and on (nothing is queued away from home)
    fun alert(context: Context, channel: String, id: Int, text: String) {
        val address = state.value.address ?: return
        val settings = NestorSettings(context)
        val token = settings.token() ?: return

        // Only while switched on, and only with words to say
        if (!settings.isOn() || text.isBlank()) {
            return
        }
        val body = MAPPER.writeValueAsString(mapOf("text" to text, "source" to "datebook",
            "expires" to alertExpires(channel, System.currentTimeMillis(), ZoneId.systemDefault()),
            "key" to alertKey(channel, id, text)))
        scope.launch { call(address, "POST", "/alert", token, body) }
    }
}

// Make John's journey a plan, from {"title", "start" (YYYY-MM-DDTHH:MM), "place", "mode"}: saved straight to the
// calendar like a confirmed Deliberate plan (nothing happens when the details can't be read)
private suspend fun addJourney(context: Context, text: String) {
    val fields = runCatching { MAPPER.readTree(text) }.getOrNull() ?: return
    val start = fields.path("start").asText("")

    // A plan needs a title and a day
    if (fields.path("title").asText("").isBlank() || start.length < 10) {
        return
    }
    val change = Change(ACT_NEW_PLAN, title = fields.path("title").asText(""), date = start.take(10),
        time = start.drop(11).take(5), place = fields.path("place").asText(""),
        travel = fields.path("mode").asText("").uppercase())
    DeliberateRunner(context).apply(listOf(change))
}

// Carry out one action from Nestor: add (show the Add to calendar? prompt again), ignore, done (a bill, assignment or
// reminder), new_plan (read like something typed, with prompts for its plans) or add_journey (John's journey, which you
// said to make a plan: saved straight to your calendar with its travel mode)
suspend fun perform(context: Context, action: String, item: Long, text: String) {
    val graph = AppGraph.get(context)

    // Each action has its own way
    when (action) {
        "add" -> graph.dao.suggestion(item)?.takeIf { it.status == Suggestion.STATUS_WAITING }
            ?.let { graph.notifier.show(it, it.message.ifBlank { it.title }) }
        "ignore" -> ignoreSuggestion(context, item)
        "done" -> markDone(context, item)
        "new_plan" -> if (text.isNotBlank()) graph.pipeline.typed(text, announce = true)
        "add_journey" -> addJourney(context, text)
        else -> Log.w(TAG, "nestor: unknown action $action")
    }
}

// Mark a follow-up done from Nestor: a bill or assignment as paid or handed in (a repeating bill comes back), a
// one-off reminder as done, and a repeating reminder moved on to its next time
private suspend fun markDone(context: Context, id: Long) {
    val dao = AppGraph.get(context).dao
    val item = dao.followUp(id) ?: return
    val zone = ZoneId.systemDefault()

    // Repeating reminders move on; everything else is done
    if (item.kind == KIND_REMINDER && item.repeat.isNotBlank()) {
        val last = LocalDateTime.ofInstant(Instant.ofEpochMilli(item.nextNudge.coerceAtLeast(0)), zone)
        val next = nextRepeat(item.repeat, last, LocalDateTime.now(zone)) ?: return
        dao.updateFollowUp(item.copy(nextNudge = next.atZone(zone).toInstant().toEpochMilli()))
    } else if (item.status == FollowUp.OPEN) {
        finishFollowUp(dao, id)
    }
    NotificationManagerCompat.from(context).cancel(FOLLOW_UP_IDS + id.toInt())
    bookNextFollowUp(context)
    updateWidgets(context)
}

// Post a Datebook notification, and pass it to Nestor to speak when it's on a spoken channel (leave-by, travel
// changes, follow-ups and the evening heads-ups)
fun postNotification(context: Context, id: Int, notification: Notification) {
    NotificationManagerCompat.from(context).notify(id, notification)

    // Only some channels are spoken
    if (isSpoken(notification.channelId)) {
        val extras = notification.extras
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val words = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        val text = words?.toString().orEmpty()
        NestorLink.alert(context, notification.channelId, id, alertText(title, text, lines))
    }
}
