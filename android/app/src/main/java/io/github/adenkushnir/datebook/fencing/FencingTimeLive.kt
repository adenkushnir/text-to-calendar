// Signing in to FencingTimeLive with your own account (kept encrypted on the phone), since it now shows tournaments
// only to signed-in users: load the login page for its security token, then post the email and password; the
// session cookie is kept for the next pages
package io.github.adenkushnir.datebook.fencing
import android.content.Context
import android.util.Log
import io.github.adenkushnir.datebook.pipeline.ApiKeyStore
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import com.fasterxml.jackson.databind.ObjectMapper
const val FTL_SITE = "https://www.fencingtimelive.com"
private const val TIMEOUT_MILLIS = 20_000
private val CSRF = Regex("<meta name=\"csrf_token\" content=\"([^\"]+)\"")
private val MAPPER = ObjectMapper()
private val YEAR = Regex("\\b20\\d\\d\\b")
private val BRACKETS = Regex("\\(.*?\\)")
private const val NEXT_7_DAYS = 1  // FencingTimeLive's date choice that covers what's on now and the next week
private const val LAST_10_DAYS = -1  // and the one for the last 10 days
private const val RECENT_DAYS = 10L  // events that ended this recently still get their Results page
private const val STOP_WORDS = "the of and men's mens foil"

// The words a meet is searched by on FencingTimeLive: its name without years and brackets ("November NAC")
fun ftlSearchWords(meet: String): String {
    return meet.replace(BRACKETS, " ").replace(YEAR, " ").replace(Regex("\\s+"), " ").trim()
}

// The id of the FencingTimeLive tournament (from its search results) that is this meet: it starts on the
// tournament's first day (or a day either side) and shares the most name words; null when none does
fun pickFtlTournament(results: String, tournament: Tournament): String? {
    val stop = STOP_WORDS.split(" ").toSet()
    val words = { text: String -> text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in stop }
        .toSet() }
    val wanted = words(ftlSearchWords(tournament.meet))
    val found = runCatching { MAPPER.readTree(results) }.getOrNull() ?: return null
    return found.mapNotNull { item ->
        val start = runCatching { LocalDate.parse(item.path("start").asText("").take(10)) }.getOrNull()
            ?: return@mapNotNull null
        val shared = words(item.path("name").asText("")).intersect(wanted).size
        val close = !start.isBefore(tournament.firstDay.minusDays(1)) && !start.isAfter(tournament.firstDay.plusDays(1))
        if (close && shared > 0) Pair(item.path("id").asText(""), shared) else null
    }.filter { it.first.isNotBlank() }.maxByOrNull { it.second }?.first
}

// Look up the FencingTimeLive tournament for each USA event on now, in the next week or in the last 10 days that
// doesn't have one yet,
// signed in with your account; returns the tournaments with the ids found (unchanged without an account)
fun withFtlIds(context: Context, tournaments: List<Tournament>, today: LocalDate): List<Tournament> {
    val account = FtlAccount(context)
    val email = account.email() ?: return tournaments
    val password = account.password() ?: return tournaments
    val wanted = tournaments.filter { it.ftlId.isBlank() && isUsaEvent(it) &&
        !it.lastDay.isBefore(today.minusDays(RECENT_DAYS)) && !it.firstDay.isAfter(today.plusDays(7)) }
    Log.i("Datebook", "ftl: ${wanted.size} of ${tournaments.size} events to look up")

    // Nothing to look up
    if (wanted.isEmpty()) {
        return tournaments
    }
    val session = FtlSession()

    val signedIn = runCatching { session.signIn(email, password) }
    Log.i("Datebook", "ftl: look-up signed in ${signedIn.getOrNull()} ${signedIn.exceptionOrNull()?.message.orEmpty()}")

    // Without signing in there's nothing to read
    if (signedIn.getOrNull() != true) {
        return tournaments
    }
    val ids = mutableMapOf<String, String>()

    // Search once per meet name (in the next 7 days, then the last 10), and match each event of that meet
    for ((meet, events) in wanted.groupBy { ftlSearchWords(it.meet) }) {
        // Each date range FencingTimeLive offers that could hold it
        for (range in listOf(NEXT_7_DAYS, LAST_10_DAYS)) {
            val query = "/tournaments/search/data?filter=All&usa=Loc&country=USA&region=0&local=All&state=CA" +
                "&date=$range&search=${URLEncoder.encode(meet, "UTF-8")}&today=$today"
            val (code, results) = runCatching { session.request(query) }.getOrDefault(Pair(0, ""))
            Log.i("Datebook", "ftl: search '$meet' ($range) answered $code")

            // Match each event at this meet not matched yet
            for (event in events.filter { tournamentKey(it) !in ids }) {
                pickFtlTournament(results, event)?.let { ids[tournamentKey(event)] = it }
            }
        }
    }
    return tournaments.map { event -> ids[tournamentKey(event)]?.let { event.copy(ftlId = it) } ?: event }
}

// Your FencingTimeLive account, encrypted like the NJ Transit one
class FtlAccount(context: Context) {
    private val user = ApiKeyStore(context, "ftl-user")
    private val password = ApiKeyStore(context, "ftl-password")

    // The saved email, or null without an account
    fun email(): String? {
        return user.load()?.takeIf { it.isNotBlank() }
    }

    // The saved password, or null without an account
    fun password(): String? {
        return password.load()?.takeIf { it.isNotBlank() }
    }

    // Save the account
    fun save(email: String, secret: String) {
        user.save(email.trim())
        password.save(secret)
    }

    // Forget the account
    fun clear() {
        user.clear()
        password.clear()
    }
}

// A FencingTimeLive session: sign in once, then read pages as a signed-in user
class FtlSession {
    private val cookies = CookieManager()

    // Read a page with the session's cookies (and an optional form body); returns the status and the text
    fun request(path: String, form: Map<String, String>? = null, csrf: String? = null): Pair<Int, String> {
        val url = URL(if (path.startsWith("http")) path else FTL_SITE + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Datebook")
        val known = cookies.cookieStore.get(url.toURI())
        connection.setRequestProperty("Cookie", known.joinToString("; ") { "${it.name}=${it.value}" })
        csrf?.let { connection.setRequestProperty("x-csrf-token", it) }

        // Send the form when there is one
        if (form != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        connection.headerFields["Set-Cookie"].orEmpty().forEach { header ->
            runCatching { java.net.HttpCookie.parse(header).forEach { cookies.cookieStore.add(url.toURI(), it) } }
        }
        val text = runCatching {
            (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        connection.disconnect()
        return Pair(code, text)
    }

    // Sign in with an email and password; returns true once signed in (this waits on the network)
    fun signIn(email: String, password: String): Boolean {
        val (_, page) = request("/account/login")
        val token = CSRF.find(page)?.groupValues?.get(1) ?: return false
        val (code, reply) = request("/login", mapOf("username" to email, "password" to password), token)
        Log.i("Datebook", "ftl: sign-in answered $code")
        return code in 200..399 && !reply.contains("Invalid", ignoreCase = true)
    }
}
