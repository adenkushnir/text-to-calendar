// Debug builds only: sign in to FencingTimeLive with the saved account and log how the signed-in tournament list is
// built (page and script names, tournament links), never the password:
// adb shell am broadcast -a io.github.adenkushnir.datebook.FTL_PROBE
//     -n io.github.adenkushnir.datebook/.FtlProbeReceiver
package io.github.adenkushnir.datebook
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.adenkushnir.datebook.fencing.FtlAccount
import io.github.adenkushnir.datebook.fencing.FtlSession
import io.github.adenkushnir.datebook.fencing.lookUpFtlSoon
private const val TAG = "Datebook"
private val SCRIPTS = Regex("src=\"(/js/[^\"]+)\"")
private val SCHEDULES = Regex("eventSchedule/([0-9A-F]{16,})")
private val ADDRESSES = Regex("[\"'](/[a-zA-Z]+/[a-zA-Z/]*(?:data|search|list|json)[a-zA-Z/]*)[\"'?]",
    RegexOption.IGNORE_CASE)
private val TAGS = Regex("<[^>]+>")

// Starts the probe as a background job (it makes several requests)
class FtlProbeReceiver : BroadcastReceiver() {
    // Hand the probe to WorkManager, then the look-up of the saved tournaments' Results pages
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<FtlProbeWorker>().build())
        lookUpFtlSoon(context.applicationContext)
    }
}

// Signs in and logs the signed-in front page's shape
class FtlProbeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Sign in, read the front page and its scripts, and log what they hold
    override suspend fun doWork(): Result {
        val account = FtlAccount(applicationContext)
        val email = account.email()
        val password = account.password()

        // Nothing to try without a saved account
        if (email == null || password == null) {
            Log.i(TAG, "ftl probe: no account saved")
            return Result.success()
        }
        val session = FtlSession()

        // Everything here is logged rather than thrown, so the log shows how far it got
        try {
            Log.i(TAG, "ftl probe: signed in ${session.signIn(email, password)}")
            val (code, home) = session.request("/")
            Log.i(TAG, "ftl probe: front page $code, ${home.length} chars")
            val scripts = SCRIPTS.findAll(home).map { it.groupValues[1] }.toList()
            Log.i(TAG, "ftl probe: scripts $scripts")
            val text = home.replace(TAGS, " ").replace(Regex("\\s+"), " ")
            Log.i(TAG, "ftl probe: text starts ${text.take(600)}")

            // Each tournament link with the words around it
            for (match in SCHEDULES.findAll(home).take(8)) {
                val around = home.substring((match.range.first - 300).coerceAtLeast(0), match.range.first)
                    .replace(TAGS, " ").replace(Regex("\\s+"), " ").takeLast(120)
                Log.i(TAG, "ftl probe: schedule ${match.groupValues[1]} after: $around")
            }

            val today = java.time.LocalDate.now().toString()

            // A few searches, to see what the tournament data looks like
            for (date in listOf(-2, -1, 1, 2, 3, 4, 5)) {
                val query = "/tournaments/search/data?filter=All&usa=Loc&country=USA&region=0&local=All&state=CA" +
                    "&date=$date&search=NAC&today=$today"
                val (status, data) = session.request(query)
                val names = Regex("\"name\":\"([^\"]+)\",\"location\":\"[^\"]*\",\"dates\":\"([^\"]+)\"")
                    .findAll(data).map { "${it.groupValues[1]} (${it.groupValues[2]})" }.toList()
                Log.i(TAG, "ftl probe: date=$date -> $status, ${names.size} found: ${names.take(6)}")
            }
            val dateChoices = Regex("onChangeDateRange\\((-?\\d+)\\)[^>]*>([^<]+)<").findAll(home)
                .map { "${it.groupValues[1]}=${it.groupValues[2].trim()}" }.toList()
            Log.i(TAG, "ftl probe: date choices $dateChoices")

            // The addresses each page script asks for
            for (script in scripts.filter { !it.contains("mdb") && !it.contains("bootstrap") && !it.contains("base") }) {
                val (_, js) = session.request(script)
                Log.i(TAG, "ftl probe: $script asks for ${ADDRESSES.findAll(js).map { it.groupValues[1] }.toSet()}")
            }
        } catch (error: Exception) {  // network or page problems
            Log.w(TAG, "ftl probe failed: ${error.message}")
        }
        return Result.success()
    }
}
