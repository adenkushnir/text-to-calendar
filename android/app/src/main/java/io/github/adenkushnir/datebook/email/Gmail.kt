// Reads your Gmail for plans, read-only, after you sign in: new emails in Primary and Updates (never Promotions or
// Social) are fetched in full and run through the same steps as texts; the sign-in lapses about every 7 days while
// the Google project is in testing mode, and Datebook then reminds you to sign in again
package io.github.adenkushnir.datebook.email
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import io.github.adenkushnir.datebook.MainActivity
import io.github.adenkushnir.datebook.R
import io.github.adenkushnir.datebook.pipeline.IncomingText
import io.github.adenkushnir.datebook.prompts.canNotify
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.TimeUnit
const val GMAIL_APP = "com.google.android.gm"
const val GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
const val EXTRA_OPEN_ROUTE = "io.github.adenkushnir.datebook.OPEN_ROUTE"
const val ROUTE_SETTINGS_GMAIL = "settings/gmail"
private const val API = "https://gmail.googleapis.com/gmail/v1/users/me/"
private const val GMAIL_CHANNEL = "gmail"
private const val SIGN_IN_AGAIN_ID = 9_100
private const val MAX_BODY = 6000
private const val MAX_NEW = 25
private const val MAX_SEEN = 500
private const val TIMEOUT_MILLIS = 20_000
private const val AUTH_SECONDS = 30L
private val MAPPER = ObjectMapper()
private val TAGS = Regex("<[^>]+>")
private val HIDDEN_BLOCKS = Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>")
private val LINE_BREAKS = Regex("(?i)<(br|/p|/div|/tr|/li|/h[1-6])[^>]*>")
private val NUMBERED_ENTITY = Regex("&#(\\d+);")
private val SPACES = Regex("[ \\t\\u00a0]+")
private val BLANK_LINES = Regex("\\n\\s*\\n\\s*\\n+")
private val NAME_AND_ADDRESS = Regex("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>\\s*$")

// One email: its id, who it's from (as the From line reads), subject, plain-text body, and when it arrived
data class Email(val id: String, val from: String, val subject: String, val body: String, val receivedMillis: Long)

// What the last check found: the new emails, or why there were none ("not signed in", "sign-in lapsed")
data class GmailCheck(val emails: List<Email>, val note: String = "")

// Remembers whether you're signed in to Gmail, as which address, since when emails are read, whether the sign-in
// lapsed, and which emails were already handled (only their ids, never their text)
class GmailSettings(context: Context) {
    private val prefs = context.getSharedPreferences("datebook-gmail", Context.MODE_PRIVATE)

    // Return true while you're signed in
    fun signedIn(): Boolean {
        return prefs.getBoolean("signed_in", false)
    }

    // The Gmail address you signed in with ("" until it's known)
    fun address(): String {
        return prefs.getString("address", "").orEmpty()
    }

    // Read emails that arrive from now on (old mail is never read)
    fun signIn(nowSeconds: Long) {
        prefs.edit().putBoolean("signed_in", true).putLong("since", nowSeconds).putBoolean("lapsed", false).apply()
    }

    // Remember the address you signed in with
    fun setAddress(address: String) {
        prefs.edit().putString("address", address).apply()
    }

    // Stop reading Gmail and forget everything about it
    fun signOut() {
        prefs.edit().clear().apply()
    }

    // The time (in seconds) emails are read from
    fun since(): Long {
        return prefs.getLong("since", 0)
    }

    // Move the time emails are read from forward
    fun setSince(seconds: Long) {
        prefs.edit().putLong("since", seconds).apply()
    }

    // Return true if the sign-in lapsed and you were reminded
    fun lapsed(): Boolean {
        return prefs.getBoolean("lapsed", false)
    }

    // Remember whether the sign-in lapsed
    fun setLapsed(lapsed: Boolean) {
        prefs.edit().putBoolean("lapsed", lapsed).apply()
    }

    // Return true if an email was already handled
    fun seen(id: String): Boolean {
        return id in prefs.getStringSet("seen", emptySet()).orEmpty()
    }

    // Remember that an email was handled, keeping only the most recent ids
    fun markSeen(id: String) {
        val seen = (prefs.getStringSet("seen", emptySet()).orEmpty() + id).toList().takeLast(MAX_SEEN).toSet()
        prefs.edit().putStringSet("seen", seen).apply()
    }
}

// The Gmail search for new mail since a time: the inbox without Promotions and Social
fun searchQuery(sinceSeconds: Long): String {
    return "in:inbox -category:promotions -category:social after:$sinceSeconds"
}

// The sender's name from a From line: "OpenTable <info@opentable.com>" gives "OpenTable", a bare address stays as it is
fun senderName(from: String): String {
    val match = NAME_AND_ADDRESS.find(from) ?: return from.trim()
    return match.groupValues[1].trim().ifBlank { match.groupValues[2].trim() }
}

// The ids of the emails in a Gmail list reply
fun parseIds(json: String): List<String> {
    return MAPPER.readTree(json).path("messages").mapNotNull { it.path("id").asText(null) }
}

// One email from a Gmail "full" reply: its From and Subject lines, its text, and when it arrived; null when it can't
// be read
fun parseEmail(json: String): Email? {
    val root = MAPPER.readTree(json)
    val id = root.path("id").asText("")

    // An email without an id can't be tracked
    if (id.isBlank()) {
        return null
    }
    val headers = root.path("payload").path("headers")
    val header = { name: String -> headers.firstOrNull { it.path("name").asText().equals(name, true) }
        ?.path("value")?.asText("").orEmpty() }
    return Email(id, header("From"), header("Subject"), bodyText(root.path("payload")),
        root.path("internalDate").asText("0").toLongOrNull() ?: 0)
}

// An email's text: its plain-text part if it has one, else its HTML part turned into text
fun bodyText(payload: JsonNode): String {
    val plain = partText(payload, "text/plain")
    return if (plain.isNotBlank()) plain.trim() else htmlToText(partText(payload, "text/html"))
}

// The decoded text of the first part of a type, looking inside nested parts
private fun partText(part: JsonNode, mimeType: String): String {
    // This part is the one wanted
    if (part.path("mimeType").asText() == mimeType) {
        val data = part.path("body").path("data").asText("")
        return if (data.isBlank()) "" else String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8)
    }

    // Look inside each nested part
    for (child in part.path("parts")) {
        val text = partText(child, mimeType)

        // Stop at the first part that has it
        if (text.isNotBlank()) {
            return text
        }
    }
    return ""
}

// Turn an HTML email into plain text: no styles, scripts or tags, line breaks kept, common entities decoded
fun htmlToText(html: String): String {
    val text = html.replace(HIDDEN_BLOCKS, " ").replace(LINE_BREAKS, "\n").replace(TAGS, " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "'")
        .replace(NUMBERED_ENTITY) { it.groupValues[1].toIntOrNull()?.let { code -> code.toChar().toString() } ?: "" }
    return text.lines().joinToString("\n") { it.replace(SPACES, " ").trim() }.replace(BLANK_LINES, "\n\n").trim()
}

// What goes to Claude for an email: its subject, then its text, cut to a sensible length
fun emailText(email: Email): String {
    return "Subject: ${email.subject}\n\n${email.body}".take(MAX_BODY)
}

// An email as a text for the pipeline: from the sender's name, at the time it arrived, from Gmail
fun incomingFrom(email: Email, zone: ZoneId): IncomingText {
    val receivedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(email.receivedMillis), zone)
    return IncomingText(senderName(email.from), emailText(email), receivedAt, GMAIL_APP, exactTime = true)
}

// The quote shown on a prompt: an email's subject, or the whole text for everything else
fun promptQuote(incoming: IncomingText): String {
    return if (incoming.app == GMAIL_APP) incoming.text.lineSequence().first().removePrefix("Subject: ")
        else incoming.text
}

// What Datebook asks Google for: read-only access to Gmail
fun gmailRequest(): AuthorizationRequest {
    return AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GMAIL_SCOPE))).build()
}

// Ask Google for a Gmail access token without showing anything (call it off the main thread); the result needs a
// sign-in screen when the sign-in lapsed
fun authorizeQuietly(context: Context): AuthorizationResult {
    val task = Identity.getAuthorizationClient(context).authorize(gmailRequest())
    return Tasks.await(task, AUTH_SECONDS, TimeUnit.SECONDS)
}

// Read a Gmail API address with an access token and return the reply (call it off the main thread)
private fun gmailGet(url: String, token: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MILLIS
    connection.readTimeout = TIMEOUT_MILLIS
    connection.setRequestProperty("Authorization", "Bearer $token")

    // Read the reply, failing on anything but success
    try {
        val code = connection.responseCode

        // Anything but success is a problem with the sign-in or with Gmail
        if (code != 200) {
            throw IOException("Gmail answered $code")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

// The address of the signed-in Gmail account (call it off the main thread)
fun gmailAddress(token: String): String {
    return MAPPER.readTree(gmailGet(API + "profile", token)).path("emailAddress").asText("")
}

// Check Gmail for new emails since the last check (call it off the main thread): nothing when you're not signed in,
// and a reminder to sign in again when the sign-in lapsed
fun newEmails(context: Context): GmailCheck {
    val settings = GmailSettings(context)

    // Gmail is only read after you sign in
    if (!settings.signedIn()) {
        return GmailCheck(emptyList(), "not signed in")
    }
    val result = authorizeQuietly(context)
    val token = result.accessToken

    // A lapsed sign-in needs you: remind once, until it's fixed
    if (result.hasResolution() || token == null) {
        // Only the first time it's noticed
        if (!settings.lapsed()) {
            settings.setLapsed(true)
            showSignInAgain(context)
        }
        return GmailCheck(emptyList(), "sign-in lapsed")
    }
    settings.setLapsed(false)
    NotificationManagerCompat.from(context).cancel(SIGN_IN_AGAIN_ID)
    val query = URLEncoder.encode(searchQuery(settings.since()), "UTF-8")
    val ids = parseIds(gmailGet("${API}messages?maxResults=$MAX_NEW&q=$query", token)).filter { !settings.seen(it) }
    val emails = ids.mapNotNull { parseEmail(gmailGet("${API}messages/$it?format=full", token)) }
    return GmailCheck(emails.sortedBy { it.receivedMillis })
}

// A quiet notification saying the Gmail sign-in lapsed; tapping it opens Settings → Gmail to sign in again
fun showSignInAgain(context: Context) {
    val channel = NotificationChannel(GMAIL_CHANNEL, "Gmail sign-in", NotificationManager.IMPORTANCE_LOW)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_ROUTE, ROUTE_SETTINGS_GMAIL)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val tap = PendingIntent.getActivity(context, SIGN_IN_AGAIN_ID, open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, GMAIL_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_datebook)
        .setColor(ContextCompat.getColor(context, R.color.brand_aqua))
        .setContentTitle("Sign in to Gmail again")
        .setContentText("Google ends the sign-in every week or so. Tap to sign in; new emails are checked after.")
        .setContentIntent(tap)
        .setAutoCancel(true)
        .build()

    // Without notification permission the Gmail page still says it
    if (canNotify(context)) {
        NotificationManagerCompat.from(context).notify(SIGN_IN_AGAIN_ID, notification)
    }
}
