// Automatic mode: an accessibility service, off until you turn it on in Android's settings, that reads new messages
// and the texts you send in the apps switched on in Apps to watch, while one of them is open; it never reads other
// apps, the chat history that was already showing, or what you're typing, and never takes screenshots
package io.github.adenkushnir.datebook.automatic
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.adenkushnir.datebook.AppGraph
import io.github.adenkushnir.datebook.capture.WATCHABLE_APPS
import io.github.adenkushnir.datebook.pipeline.IncomingText
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
private const val TAG = "Datebook"
private const val READ_DELAY_MILLIS = 700L
private const val LEAVE_CHECK_MILLIS = 4000L
private const val MAX_DEPTH = 40
private const val MAX_NODES = 600

// Remembers which conversations are group chats, learned from their notifications (the screen doesn't say)
class GroupChats(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-group-chats", Context.MODE_PRIVATE)

    // Remember that a conversation in an app is a group chat
    fun remember(app: String, name: String) {
        val groups = prefs.getStringSet("groups", emptySet()).orEmpty() + "$app|$name"
        prefs.edit().putStringSet("groups", groups).apply()
    }

    // Return true if a conversation in an app is known to be a group chat
    fun isGroup(app: String, name: String): Boolean {
        return "$app|$name" in prefs.getStringSet("groups", emptySet()).orEmpty()
    }
}

// Return true if automatic mode is turned on in Android's accessibility settings
fun isAutomaticOn(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    val me = ComponentName(context, AutomaticModeService::class.java).flattenToString()
    return enabled?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
}

// Turn a new bubble into a text for the pipeline: one-to-one chats are named after the person, group chats after the
// group (whose sender the screen doesn't show), and your own texts are marked as yours
fun incomingFrom(app: String, chatName: String, bubble: Bubble, isGroup: Boolean, now: LocalDateTime): IncomingText {
    val group = if (isGroup) chatName else ""  // groups use the group's name
    val sender = if (isGroup) (if (bubble.mine) "you" else "") else chatName
    return IncomingText(sender, bubble.text, now, app, group, fromMe = bubble.mine, onScreen = true)
}

// The accessibility service: reads the open chat a moment after it changes, sends new bubbles through the pipeline,
// and clears the quiet notifications when you leave the chat
class AutomaticModeService : AccessibilityService() {
    private val tracker = ChatTracker()
    private val handler = Handler(Looper.getMainLooper())
    private var readPending = false
    private var leaveCheckOn = false

    // Listen only to the apps switched on in Apps to watch
    override fun onServiceConnected() {
        val graph = AppGraph.get(this)
        val info = serviceInfo
        info.packageNames = WATCHABLE_APPS.map { it.packageName }.filter { graph.appSwitches.isOn(it) }.toTypedArray()
        serviceInfo = info
    }

    // Read the chat a moment after one of the chosen apps changes on screen
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val app = event.packageName?.toString() ?: return

        // Ignore apps that aren't switched on (the list can change while the service runs)
        if (!AppGraph.get(this).appSwitches.isOn(app)) {
            return
        }

        // Read at most once per short pause while the screen keeps changing
        if (!readPending) {
            readPending = true
            handler.postDelayed({
                readPending = false
                readScreen(app)
            }, READ_DELAY_MILLIS)
        }
    }

    // Nothing to stop: the service only reacts to events
    override fun onInterrupt() {
    }

    // Read the chat on screen, if the app in front is still the chosen one, and handle its new bubbles
    private fun readScreen(app: String) {
        val root = rootInActiveWindow

        // Only read while that app is in front
        if (root == null || root.packageName?.toString() != app) {
            leaveChat()
            return
        }
        val metrics = resources.displayMetrics
        val chat = readChat(toScreenNode(root, 0, intArrayOf(0)), metrics.widthPixels, metrics.heightPixels)

        // A screen without a typing box isn't a chat (like the conversation list)
        if (chat == null) {
            leaveChat()
            return
        }
        val before = tracker.current
        val fresh = tracker.update(app, chat)

        // Switching straight to another chat clears the old chat's notifications
        if (before != null && before != tracker.current) {
            AppGraph.get(this).notifier.cancelQuiet()
        }
        val isGroup = AppGraph.get(this).groupChats.isGroup(app, chat.title)
        Log.d(TAG, "[$app] automatic: '${chat.title}' ${chat.bubbles.size} bubbles " +
            "(${chat.bubbles.count { it.mine }} yours), group: $isGroup, new: ${fresh.size}")

        // Send each new bubble through the same steps as a notification
        for (bubble in fresh) {
            val incoming = incomingFrom(app, chat.title, bubble, isGroup, LocalDateTime.now())
            CoroutineScope(Dispatchers.IO).launch {
                val outcome = AppGraph.get(applicationContext).pipeline.receive(incoming) ?: return@launch
                val who = if (bubble.mine) "you" else incoming.sender.ifBlank { incoming.group }
                outcome.lines.forEach { Log.i(TAG, "[$app] automatic, $who -> $it") }  // one log line per result
            }
        }
        startLeaveCheck()
    }

    // While a chat is open, check every few seconds which app is in front (its name only) to notice you leaving
    private fun startLeaveCheck() {
        // Only one check runs at a time
        if (leaveCheckOn) {
            return
        }
        leaveCheckOn = true
        handler.postDelayed(object : Runnable {
            // See whether the chat's app is still in front, and check again later if it is
            override fun run() {
                val open = tracker.current ?: return  // already left
                val app = open.substringBefore("|")

                // Leave when another app is in front
                if (rootInActiveWindow?.packageName?.toString() != app) {
                    leaveChat()
                    return
                }
                handler.postDelayed(this, LEAVE_CHECK_MILLIS)
            }
        }, LEAVE_CHECK_MILLIS)
    }

    // Forget the chat and clear its quiet notifications (its plans keep waiting on Home)
    private fun leaveChat() {
        leaveCheckOn = false
        handler.removeCallbacksAndMessages(null)
        readPending = false

        // Only clear when a chat was open
        if (tracker.current != null) {
            tracker.leave()
            AppGraph.get(this).notifier.cancelQuiet()
        }
    }

    // Copy the screen's accessibility description into plain ScreenNodes, with a limit on depth and size
    private fun toScreenNode(node: AccessibilityNodeInfo, depth: Int, count: IntArray): ScreenNode {
        count[0] += 1
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val children = mutableListOf<ScreenNode>()

        // Copy each child until the limits are reached
        for (i in 0 until node.childCount) {
            // Stop going deeper or wider once the limits are reached
            if (depth >= MAX_DEPTH || count[0] >= MAX_NODES) {
                break
            }
            val child = node.getChild(i) ?: continue
            children.add(toScreenNode(child, depth + 1, count))
        }
        return ScreenNode(node.text?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), node.isEditable,
            bounds.left, bounds.top, bounds.right, bounds.bottom, children)
    }
}
