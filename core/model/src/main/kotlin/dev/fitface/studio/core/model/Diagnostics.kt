package dev.fitface.studio.core.model

/** How loud one [DiagnosticsLog] entry is. */
enum class DiagnosticsLevel(val marker: Char) {
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/**
 * One recorded moment.
 *
 * [detail] is the machine-readable half — a [WatchFaceException.technicalDetail], a
 * store result code — and is very often the half that actually explains the failure.
 * [message] is only what a person was told, which by design says less.
 */
data class DiagnosticsEntry(
    val atEpochMillis: Long,
    val level: DiagnosticsLevel,
    val tag: String,
    val message: String,
    val detail: String? = null,
    /** Exception type, message and the frames inside this app. Never a full trace. */
    val failure: String? = null,
) {
    /** Rendered relative to [origin], so a report carries no wall-clock timestamps. */
    fun render(origin: Long): String = buildString {
        val delta = (atEpochMillis - origin).coerceAtLeast(0)
        append('+').append(delta / 1000).append('.')
        append((delta % 1000).toString().padStart(3, '0')).append("s ")
        append(level.marker).append(' ').append(tag).append("  ").append(message)
        detail?.takeIf(String::isNotBlank)?.let { append("\n    detail: ").append(it) }
        failure?.takeIf(String::isNotBlank)?.let { append("\n    failure: ").append(it) }
    }
}

/**
 * Strips the few things that must never leave the phone, whatever a call site passed.
 *
 * The report is assembled from an explicit allowlist, so this is the second line rather
 * than the first — but the call sites it guards are real. `:core:delivery` handles
 * Bluetooth addresses and bonded-watch names, the catalogue request carries
 * `Settings.Secure.ANDROID_ID` as its `extuk` query parameter, and a picked image
 * arrives as a `content://` URI whose last segment is frequently the owner's own file
 * name. Scrubbing happens on the way *into* [DiagnosticsLog], so none of it is ever
 * held in memory waiting to be rendered.
 */
object DiagnosticsRedaction {
    private val LocalUri = Regex("""(?i)\b(?:content|file)://\S+""")
    private val ExternalPath = Regex("""(?i)(?:/storage/|/sdcard/)\S+""")
    private val MacAddress = Regex("""\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b""")
    private val UrlQuery = Regex("""(?i)(https?://[^\s?]+)\?\S*""")

    /**
     * An accessory peer handle. `Fit3DirectInstaller` folds the discovery agent's detail
     * string — which carries `id=<peerId>` — into the failure it puts on
     * `DirectInstallState`, and that failure is one of the most useful things a transfer
     * report can carry, so the handle is removed rather than the sentence.
     *
     * The leading boundary keeps this off `faceId=`, `samplerId=` and `projectId=`, which
     * are the identifiers a report is *for*.
     */
    private val PeerHandle = Regex("""\bid=\S+""")

    fun scrub(text: String): String = text
        .replace(LocalUri, "<uri>")
        .replace(ExternalPath, "<path>")
        .replace(MacAddress, "<mac>")
        .replace(UrlQuery, "$1?<redacted>")
        .replace(PeerHandle, "id=<redacted>")
}

/**
 * A bounded, in-memory account of what the app did, so a failure on someone else's
 * phone can be described without `adb`.
 *
 * This exists because the interesting failures here are not the ones that crash. A
 * container can edit cleanly, validate, transfer, be accepted by the watch and still
 * draw wrong — nothing throws, so a stack trace would be empty and only the sequence of
 * operations says what happened. The buffer is deliberately small: a report nobody can
 * read is a report nobody reads.
 */
class DiagnosticsLog(private val capacity: Int = DefaultCapacity) {
    private val lock = Any()
    private val entries = ArrayDeque<DiagnosticsEntry>()

    /**
     * Installed once at startup so everything recorded here still reaches logcat, which
     * is the better tool when the phone is on your own desk. Held as a function because
     * this module stays free of Android APIs; the entry handed over is already scrubbed,
     * and the raw [Throwable] is passed separately so logcat keeps the full trace.
     */
    var mirror: ((DiagnosticsEntry, Throwable?) -> Unit)? = null

    fun record(
        level: DiagnosticsLevel,
        tag: String,
        message: String,
        detail: String? = null,
        error: Throwable? = null,
        atEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val entry = DiagnosticsEntry(
            atEpochMillis = atEpochMillis,
            level = level,
            tag = tag,
            message = DiagnosticsRedaction.scrub(message),
            detail = detail?.takeIf(String::isNotBlank)?.let(DiagnosticsRedaction::scrub),
            failure = error?.let { DiagnosticsRedaction.scrub(it.diagnosticSummary()) },
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
        mirror?.invoke(entry, error)
    }

    fun info(tag: String, message: String, detail: String? = null) =
        record(DiagnosticsLevel.INFO, tag, message, detail)

    fun warn(tag: String, message: String, detail: String? = null, error: Throwable? = null) =
        record(DiagnosticsLevel.WARN, tag, message, detail, error)

    fun error(tag: String, message: String, detail: String? = null, error: Throwable? = null) =
        record(DiagnosticsLevel.ERROR, tag, message, detail, error)

    fun snapshot(): List<DiagnosticsEntry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    companion object {
        const val DefaultCapacity = 200
    }
}

/**
 * Type, message and this app's own frames, across the cause chain.
 *
 * Bounded on purpose: the point is a line someone can paste into an issue, not a full
 * trace, and framework frames say nothing a reader of this repository needs.
 */
fun Throwable.diagnosticSummary(): String = buildString {
    var current: Throwable? = this@diagnosticSummary
    var depth = 0
    while (current != null && depth < MaxCauseDepth) {
        if (depth > 0) append(" <- ")
        append(current::class.java.simpleName)
        current.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    this@diagnosticSummary.stackTrace
        .filter { it.className.startsWith(AppPackage) }
        .take(MaxFrames)
        .forEach { frame ->
            append("\n      at ")
            append(frame.className.substringAfterLast('.'))
            append('.').append(frame.methodName).append(':').append(frame.lineNumber)
        }
}

private const val AppPackage = "dev.fitface.studio"
private const val MaxCauseDepth = 3
private const val MaxFrames = 6

/** One titled block of the report. Every line is added explicitly by a caller. */
data class DiagnosticsSection(val title: String, val lines: List<String>)

/**
 * The pasteable report.
 *
 * Built by allowlist — there is no path that serialises an arbitrary object into it —
 * because the alternative leaks by default the first time someone adds a field. The
 * header fields line up with `.github/ISSUE_TEMPLATE/bug_report.yml` so the whole thing
 * drops into an issue without editing.
 */
data class DiagnosticsReport(
    val app: String,
    val android: String,
    val device: String,
    val locale: String,
    val sections: List<DiagnosticsSection> = emptyList(),
    val entries: List<DiagnosticsEntry> = emptyList(),
) {
    fun render(): String = buildString {
        appendLine("FitFace Studio diagnostics")
        appendLine("app=$app")
        appendLine("android=$android")
        appendLine("device=$device")
        appendLine("locale=$locale")
        sections.filter { it.lines.isNotEmpty() }.forEach { section ->
            appendLine()
            appendLine("## ${section.title}")
            section.lines.forEach { appendLine(DiagnosticsRedaction.scrub(it)) }
        }
        appendLine()
        appendLine("## log")
        if (entries.isEmpty()) {
            appendLine("(nothing recorded)")
        } else {
            val origin = entries.first().atEpochMillis
            entries.forEach { appendLine(it.render(origin)) }
        }
    }
}
