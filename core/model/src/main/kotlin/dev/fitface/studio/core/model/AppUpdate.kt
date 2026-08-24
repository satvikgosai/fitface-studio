package dev.fitface.studio.core.model

/**
 * A release version, as the numbers in it.
 *
 * Comparison is part by part rather than string by string, because the obvious string
 * compare gets `0.1.10` wrong — it sorts below `0.1.9`, so the one release people most
 * need would never be offered. A shorter version is zero-padded, so `0.2` and `0.2.0`
 * are the same version rather than two.
 *
 * [parse] returns null for anything that is not plain dotted digits, and that is
 * deliberate: the update check **fails closed**. A tag like `v0.2.0-rc1` has no obvious
 * place in this ordering, and guessing one risks offering a downgrade — which on Android
 * cannot be installed over the current build at all, and whose only workaround takes
 * every saved project with it. Not offering an update is a nuisance; offering the wrong
 * one is not.
 */
data class AppVersion(val raw: String, val parts: List<Int>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val width = maxOf(parts.size, other.parts.size)
        for (index in 0 until width) {
            val mine = parts.getOrElse(index) { 0 }
            val theirs = other.parts.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    override fun toString(): String = raw

    companion object {
        /**
         * Parses `1`, `0.1`, `0.1.1`, `1.2.3.4`. A leading `v` is accepted because the
         * release tags carry one and the version name does not.
         *
         * Returns null for an empty string, a negative or non-numeric segment, an empty
         * segment (`0..1`), or a segment too long to be an `Int`.
         */
        fun parse(text: String?): AppVersion? {
            val trimmed = text?.trim()?.removePrefix("v").orEmpty()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split('.').map { segment ->
                if (segment.isEmpty() || !segment.all(Char::isDigit)) return null
                segment.toIntOrNull() ?: return null
            }
            return AppVersion(trimmed, parts)
        }
    }
}

/**
 * One published release, reduced to the part that can be installed.
 *
 * [assetBytes] is the size the feed declares, and it is load-bearing rather than
 * decorative: it is checked before the request is made, again against the response's
 * own content length, again while the bytes arrive, and once more at the end. That is
 * the same four-way discipline the face-package download uses.
 */
data class AppRelease(
    val version: AppVersion,
    val tag: String,
    val assetName: String,
    val assetUrl: String,
    val assetBytes: Long,
)

/**
 * Why an update that downloaded cleanly still will not be installed.
 *
 * These are all decided **before** the package manager is asked, because its own errors
 * for them are unreadable — `INSTALL_FAILED_UPDATE_INCOMPATIBLE` tells a reader nothing
 * about what to do, and the thing they will try next is uninstalling, which deletes
 * every saved project.
 */
enum class UpdateBlocker {
    /**
     * The downloaded APK is signed with a different key from the running one, so Android
     * will refuse it. Expected in normal use: CI signs with a keystore restored from a
     * secret, and a local build uses the one AGP generated on that machine.
     */
    SIGNATURE_DIFFERS,

    /** "Install unknown apps" is off for this app, or a device policy forbids it. */
    INSTALL_NOT_PERMITTED,

    /** Not enough room for the download plus the copy the installer stages. */
    NOT_ENOUGH_SPACE,

    /**
     * The archive's version code is not above the installed one. Caught here because the
     * release feed carries no version code — only the archive does — so this is the last
     * point at which a re-cut tag can be spotted.
     */
    NOT_NEWER,
}

/**
 * Where the update flow is. One value for the whole process.
 *
 * Sealed rather than a phase enum beside a bag of nullable fields, because each phase
 * carries different things and the single `when` that renders it should not compile
 * until every phase is handled.
 *
 * **Nothing here may hold an Android type.** The installer's own confirmation is an
 * `Intent`, so it travels on its own channel instead of riding on the state — see
 * `AppUpdater`.
 */
sealed interface AppUpdateState {
    /** Nothing asked for yet, or the dialog was closed. */
    data object Idle : AppUpdateState

    data object Checking : AppUpdateState

    data class UpToDate(val installedVersion: String) : AppUpdateState

    data class Available(
        val release: AppRelease,
        val installedVersion: String,
        /**
         * "Install unknown apps" is already off, known before the download starts so the
         * offer can say so rather than spending 36 MiB first.
         */
        val installBlockedUpFront: Boolean = false,
    ) : AppUpdateState

    data class Downloading(val release: AppRelease, val progress: DownloadProgress) : AppUpdateState

    data class ReadyToInstall(val release: AppRelease) : AppUpdateState

    /** Downloaded, then refused before the package manager was ever asked. */
    data class Blocked(val release: AppRelease, val blocker: UpdateBlocker) : AppUpdateState

    data class Installing(val release: AppRelease) : AppUpdateState

    /**
     * @param message the sentence to show.
     * @param detail the half that explains it, for the pasteable report. Never shown.
     */
    data class Failed(val message: String, val detail: String? = null) : AppUpdateState

    /**
     * Whether something is in flight. Guarding on this is what stops the menu being
     * tapped in the library and again in the editor from starting two downloads of the
     * same 36 MiB — the same guard `installCurrentBin` uses for a transfer.
     */
    val isBusy: Boolean
        get() = this is Checking || this is Downloading || this is Installing
}
