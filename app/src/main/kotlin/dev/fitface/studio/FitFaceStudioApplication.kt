package dev.fitface.studio

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.fitface.studio.core.data.DiagnosticsReporter
import dev.fitface.studio.core.model.DiagnosticsLevel
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.diagnosticSummary
import javax.inject.Inject

@HiltAndroidApp
class FitFaceStudioApplication : Application() {
    @Inject lateinit var diagnostics: DiagnosticsLog

    @Inject lateinit var reporter: DiagnosticsReporter

    override fun onCreate() {
        super.onCreate()
        mirrorToLogcat()
        captureCrashes()
    }

    /**
     * Everything recorded for a report still reaches logcat, which is the better tool
     * when the phone is on your own desk. The entry is already scrubbed; the untouched
     * [Throwable] goes with it so a local trace stays complete.
     */
    private fun mirrorToLogcat() {
        diagnostics.mirror = { entry, error ->
            val line = entry.detail?.let { "${entry.message} | $it" } ?: entry.message
            when (entry.level) {
                DiagnosticsLevel.INFO -> Log.i(entry.tag, line)
                DiagnosticsLevel.WARN -> Log.w(entry.tag, line, error)
                DiagnosticsLevel.ERROR -> Log.e(entry.tag, line, error)
            }
        }
    }

    /**
     * A crash on someone else's phone is otherwise invisible: there is no Play console
     * behind a sideloaded APK, and the process is gone before anything can be shown.
     *
     * The buffer is written to app-private storage and offered on the next launch. The
     * previous handler is always called, so the process still dies the way Android
     * expects — swallowing it would leave a wedged app rather than a crashed one.
     */
    private fun captureCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                diagnostics.record(
                    level = DiagnosticsLevel.ERROR,
                    tag = "Crash",
                    message = "Uncaught on ${thread.name}",
                    error = error,
                )
                reporter.crashFile().writeText(
                    buildString {
                        appendLine("crash=${error.diagnosticSummary()}")
                        val entries = diagnostics.snapshot()
                        val origin = entries.firstOrNull()?.atEpochMillis ?: 0L
                        entries.forEach { appendLine(it.render(origin)) }
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
