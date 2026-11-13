// Reads a chat from the text Android's accessibility service reports for the screen (never pixels): the chat's name,
// its message bubbles and which ones are yours, never the box you're typing in; and keeps track of which bubbles are
// new since the chat was opened, so only new messages are ever used
package io.github.adenkushnir.datebook.automatic
private val TIME_ONLY = Regex("""^\d{1,2}:\d{2}\s?([AaPp]\.?[Mm]\.?)?$""")
private val STATUS_WORDS = setOf("delivered", "read", "sent", "sending", "sending…", "seen", "failed", "today",
    "yesterday", "now", "just now", "last read", "edited", "typing…", "text message", "rcs message", "chat message")
private val TITLE_IDS = Regex("title|chat_name|conversation_name", RegexOption.IGNORE_CASE)
private val MESSAGE_IDS = Regex("message_text$", RegexOption.IGNORE_CASE)  // Google Messages and GroupMe
private val SEPARATOR_IDS = Regex("separator", RegexOption.IGNORE_CASE)
private const val TITLE_AREA = 0.2

// One piece of the screen as the accessibility service describes it: its text, its view id, whether you can type in
// it, where it is (left, top, right, bottom in pixels), what's inside it, and whether it scrolls (a list)
data class ScreenNode(
    val text: String = "",
    val viewId: String = "",
    val editable: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val children: List<ScreenNode> = emptyList(),
    val scrollable: Boolean = false,
)

// One message bubble: its text and whether you sent it (it sits on the right)
data class Bubble(val text: String, val mine: Boolean)

// What a chat screen shows: the chat's name and its message bubbles, top to bottom
data class ChatScreen(val title: String, val bubbles: List<Bubble>)

// A piece of the screen with text, and the pieces it sits inside (outermost first)
private class Piece(val node: ScreenNode, val parents: List<ScreenNode>)

// Every piece of the screen that has text, in reading order, with the pieces around it
private fun withText(node: ScreenNode, parents: List<ScreenNode> = emptyList()): List<Piece> {
    val own = if (node.text.isNotBlank()) listOf(Piece(node, parents)) else emptyList()  // only pieces that show text
    return own + node.children.flatMap { withText(it, parents + node) }
}

// Every piece of the screen, with or without text
private fun allNodes(node: ScreenNode): List<ScreenNode> {
    return listOf(node) + node.children.flatMap { allNodes(it) }
}

// Text with the unusual spaces apps put in times ("10:52 AM" with a narrow space) made plain
private fun plain(text: String): String {
    return text.replace(' ', ' ').replace(' ', ' ').trim()
}

// Return true if a piece of text is a time, a date heading or a status like "Delivered" rather than a message
fun isNoise(text: String): Boolean {
    val clean = plain(text)
    return clean.length < 2 || TIME_ONLY.matches(clean) || clean.lowercase() in STATUS_WORDS
}

// Read a chat from the screen; null when it doesn't look like a chat. The name is the top-most text near the top
// that isn't in a scrolling list, preferring one labelled as a title; the bubbles are the texts in the biggest
// scrolling list (only the message texts, in apps like GroupMe that label them, so sender names and polls are left
// out) between the name and the typing box, yours on the right
fun readChat(root: ScreenNode, screenWidth: Int, screenHeight: Int): ChatScreen? {
    val typingTop = allNodes(root).filter { it.editable }.minOfOrNull { it.top } ?: return null  // no typing box
    val pieces = withText(root).filter { !it.node.editable }
    val fixed = pieces.filter { piece ->
        val node = piece.node
        piece.parents.none { it.scrollable } && node.bottom <= screenHeight * TITLE_AREA && !isNoise(node.text)
    }
    val labelled = fixed.filter { piece ->
        (piece.parents.map { it.viewId } + piece.node.viewId).any { TITLE_IDS.containsMatchIn(it) }
    }
    val title = labelled.ifEmpty { fixed }.minByOrNull { it.node.top }?.node ?: return null
    val list = allNodes(root).filter { it.scrollable }.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
    val inList = if (list == null) pieces else pieces.filter { piece -> piece.parents.any { it === list } }
    val labelsMessages = list != null && allNodes(list).any { it.viewId.contains("message", ignoreCase = true) }
    val messages = if (labelsMessages) inList.filter { MESSAGE_IDS.containsMatchIn(it.node.viewId) } else inList
    val bubbles = messages.filter { piece ->
        val node = piece.node
        node !== title && node.top >= title.bottom && node.bottom <= typingTop && !isNoise(node.text) &&
            piece.parents.none { SEPARATOR_IDS.containsMatchIn(it.viewId) }
    // Top to bottom, since Google Messages lists the newest message first
    }.sortedWith(compareBy({ it.node.top }, { it.node.left })).map { piece ->
        val node = piece.node
        val mine = node.left > screenWidth * 0.2 && (node.left + node.right) / 2 > screenWidth / 2  // right side
        Bubble(plain(node.text), mine)
    }
    return ChatScreen(plain(title.text), bubbles)
}

// How many bubbles at the bottom of the screen are new: line the screen up with the bottom of what was seen before,
// using the longest run that matches, and count what comes after it; none when the bottom isn't showing (you
// scrolled up) or nothing matches
fun newAtBottom(before: List<String>, now: List<String>): Int {
    var bestRun = 0
    var bestEnd = -1

    // Try lining up the last bubble seen with each bubble on screen
    for (j in now.indices) {
        var run = 0

        // Count how far back the two match
        while (run <= j && run < before.size && now[j - run] == before[before.size - 1 - run]) {
            run += 1
        }

        // Keep the longest match (the first one when two are as long)
        if (run > bestRun) {
            bestRun = run
            bestEnd = j
        }
    }
    return if (bestRun == 0) 0 else now.size - 1 - bestEnd
}

// Keeps track of the open chat: when a chat opens, everything showing is already seen; after that, only bubbles that
// appear below the newest one seen count as new, so scrolling up to older messages never does
class ChatTracker {
    private var openChat: String? = null
    private var newest = emptyList<String>()

    // The chat now open (app and name), or null when none is
    val current: String?
        get() = openChat

    // Look at the chat on screen and return its new bubbles (none the first time a chat is seen)
    fun update(app: String, screen: ChatScreen): List<Bubble> {
        val chat = "$app|${screen.title}"
        val keys = screen.bubbles.map { "${it.mine}|${it.text}" }

        // A newly opened chat (or one still loading): what's showing is the starting point
        if (chat != openChat || newest.isEmpty()) {
            openChat = chat
            newest = keys
            return emptyList()
        }
        val count = newAtBottom(newest, keys)

        // Move the starting point down only when the newest bubble seen is still on screen
        if (newest.last() in keys) {
            newest = keys
        }
        return screen.bubbles.takeLast(count)
    }

    // The chat was left (another app or screen came up)
    fun leave() {
        openChat = null
        newest = emptyList()
    }
}
