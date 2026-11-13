// Reads a chat from the text Android's accessibility service reports for the screen (never pixels): the chat's name,
// its message bubbles and which ones are yours, never the box you're typing in; and keeps track of which bubbles are
// new since the chat was opened, so only new messages are ever used
package io.github.adenkushnir.datebook.automatic
private val TIME_ONLY = Regex("""^\d{1,2}:\d{2}\s?([AaPp]\.?[Mm]\.?)?$""")
private val STATUS_WORDS = setOf("delivered", "read", "sent", "sending", "sending…", "seen", "failed", "today",
    "yesterday", "now", "edited", "typing…", "text message", "rcs message", "chat message")
private const val MAX_SEEN = 300

// One piece of the screen as the accessibility service describes it: its text, its view id, whether you can type in
// it, where it is (left, top, right, bottom in pixels), and what's inside it
data class ScreenNode(
    val text: String = "",
    val viewId: String = "",
    val editable: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val children: List<ScreenNode> = emptyList(),
)

// One message bubble: its text and whether you sent it (it sits on the right)
data class Bubble(val text: String, val mine: Boolean)

// What a chat screen shows: the chat's name and its message bubbles, top to bottom
data class ChatScreen(val title: String, val bubbles: List<Bubble>)

// Every piece of the screen that has text, in reading order
private fun withText(node: ScreenNode): List<ScreenNode> {
    val own = if (node.text.isNotBlank()) listOf(node) else emptyList()  // only pieces that show text
    return own + node.children.flatMap { withText(it) }
}

// Return true if a piece of text is a time, a date heading or a status like "Delivered" rather than a message
fun isNoise(text: String): Boolean {
    val clean = text.trim()
    return clean.length < 2 || TIME_ONLY.matches(clean) || clean.lowercase() in STATUS_WORDS
}

// Read a chat from the screen: the name from the view whose id says "title" (or the top-most text near the top), the
// bubbles between it and the typing box, yours on the right; null when it doesn't look like a chat
fun readChat(root: ScreenNode, screenWidth: Int, screenHeight: Int): ChatScreen? {
    val texts = withText(root)
    val typingTop = allNodes(root).filter { it.editable }.minOfOrNull { it.top } ?: return null  // no typing box
    val titleNode = texts.firstOrNull { !it.editable && it.viewId.contains("title", ignoreCase = true) }
        ?: texts.filter { !it.editable && it.bottom <= screenHeight * 0.2 }.minByOrNull { it.top } ?: return null
    val bubbles = texts.filter { node ->
        !node.editable && node !== titleNode && node.top >= titleNode.bottom && node.bottom <= typingTop &&
            !isNoise(node.text)
    }.map { node ->
        val mine = node.left > screenWidth * 0.2 && (node.left + node.right) / 2 > screenWidth / 2  // right side
        Bubble(node.text.trim(), mine)
    }
    return ChatScreen(titleNode.text.trim(), bubbles)
}

// Every piece of the screen, with or without text
private fun allNodes(node: ScreenNode): List<ScreenNode> {
    return listOf(node) + node.children.flatMap { allNodes(it) }
}

// Keeps track of the open chat: when a chat opens, everything showing is marked as already seen; after that, only
// bubbles that weren't seen before count as new
class ChatTracker {
    private var openChat: String? = null
    private val seen = LinkedHashSet<String>()

    // The chat now open (app and name), or null when none is
    val current: String?
        get() = openChat

    // Look at the chat on screen and return its new bubbles (none the first time a chat is seen)
    fun update(app: String, screen: ChatScreen): List<Bubble> {
        val chat = "$app|${screen.title}"
        val keys = screen.bubbles.map { "${it.mine}|${it.text}" }

        // A newly opened chat: remember what's showing and report nothing
        if (chat != openChat) {
            openChat = chat
            seen.clear()
            seen.addAll(keys)
            return emptyList()
        }
        val fresh = screen.bubbles.filter { "${it.mine}|${it.text}" !in seen }
        seen.addAll(keys)

        // Forget the oldest bubbles once there are many
        while (seen.size > MAX_SEEN) {
            seen.remove(seen.first())
        }
        return fresh
    }

    // The chat was left (another app or screen came up)
    fun leave() {
        openChat = null
        seen.clear()
    }
}
