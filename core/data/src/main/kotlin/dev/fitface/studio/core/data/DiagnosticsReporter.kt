package dev.fitface.studio.core.data

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DiagnosticsReport
import dev.fitface.studio.core.model.DiagnosticsSection
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Assembles the pasteable report.
 *
 * Every field here is named on purpose. The rule is an allowlist rather than a denylist,
 * because a denylist leaks the first time someone adds a field to a state class and
 * forgets this file exists. Specifically **not** collected, all of which the app does
 * hold somewhere:
 *
 *  * `Settings.Secure.ANDROID_ID` — a persistent device identifier, sent to the store as
 *    `extuk`. Its presence in a query string is also why no full URL is ever recorded.
 *  * Bluetooth addresses and bonded-watch names, which `:core:delivery` reads.
 *  * The `csc` sales code, `mcc`/`mnc`, and the boot-derived `systemId` — carrier and
 *    device fingerprints that diagnose nothing here.
 *  * Any picked image's URI, file name or path.
 *  * Container bytes and artwork, which `.github/ISSUE_TEMPLATE/bug_report.yml` already
 *    tells people not to attach.
 *
 * Locale *is* collected: it is a settings value rather than an identifier, and after the
 * `resultCode=1005` catalogue failure it is often the whole diagnosis.
 */
@Singleton
class DiagnosticsReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {
    /**
     * Suspending because assembling this reads the crash file and the package manager,
     * and every caller is a ViewModel whose `launch` would otherwise do that on the main
     * thread while someone is waiting to be told what went wrong.
     */
    suspend fun render(sections: List<DiagnosticsSection> = emptyList()): String =
        withContext(Dispatchers.IO) {
            DiagnosticsReport(
                app = appVersion(),
                android = "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                locale = localeLine(),
                sections = sections + listOfNotNull(previousCrashSection()),
                entries = diagnostics.snapshot(),
            ).render()
        }

    /** Whether the last run ended in a crash whose account is still waiting to be sent. */
    suspend fun hasPreviousCrash(): Boolean = withContext(Dispatchers.IO) { crashFile().isFile }

    /**
     * Dropped once the person has been shown it. Keeping it would make every later report
     * carry a crash from an unrelated session, which reads as a crash that keeps
     * happening.
     */
    suspend fun clearPreviousCrash() {
        withContext(Dispatchers.IO) { runCatching { crashFile().delete() } }
    }

    /**
     * The crash is recorded by the uncaught-exception handler and read back here, because
     * the process is gone before anything could be shown at the time.
     */
    private fun previousCrashSection(): DiagnosticsSection? = runCatching {
        crashFile()
            .takeIf(File::isFile)
            ?.readLines()
            ?.takeIf(List<String>::isNotEmpty)
            ?.let { DiagnosticsSection("previous run — crashed", it) }
    }.getOrNull()

    fun crashFile(): File = File(context.filesDir, CrashFileName)

    /**
     * Shared with the About dialog and the update check through
     * [installedVersionLabel], so the three cannot disagree about what is running. It
     * still answers `unknown` rather than throwing: a report that omits the version is
     * worth more than no report.
     */
    private fun appVersion(): String = context.installedVersionLabel()

    /**
     * Both halves, because the difference between them is the bug: the device asks for
     * one thing and the store is sent another.
     */
    private fun localeLine(): String {
        val device = Locale.getDefault()
        // Resolved exactly as the request resolves it, SIM region included. Leaving that
        // out made this line disagree with what had actually been sent, which is the one
        // thing it exists to say.
        val sent = CatalogLocale.of(device, context.simCatalogRegion())
        val declared = "${device.language}_${device.country}"
        return if (declared == sent) sent else "$declared (sent as $sent)"
    }

    companion object {
        const val CrashFileName = "last-crash.txt"
    }
}
