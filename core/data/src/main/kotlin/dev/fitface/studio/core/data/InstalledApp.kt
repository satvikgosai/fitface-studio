package dev.fitface.studio.core.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** What identifies an installed or downloaded build, for the checks the updater makes. */
internal data class AppIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    /**
     * SHA-256 of each signing certificate. Empty when they could not be read, which is
     * different from "read and found to be different" — see [signingVerdict].
     */
    val signerDigests: Set<String>,
) {
    /** `0.1.1 (17)`, as the About dialog and the diagnostics header show it. */
    val label: String get() = "$versionName ($versionCode)"
}

/** Whether a downloaded build can install over the running one. */
internal enum class SigningVerdict { COMPATIBLE, INCOMPATIBLE, UNKNOWN }

/**
 * Compares two builds' signing certificates.
 *
 * Android identifies an installed app by application ID **and** signing certificate, so
 * an APK signed with another key cannot update one already installed — the package
 * manager refuses it, and the only way through is an uninstall, which takes every saved
 * project with it. This is not a corner case here: CI signs with a keystore restored from
 * a secret, and a local build uses the one AGP generated on that machine, so the release
 * APK and a development build routinely disagree.
 *
 * [UNKNOWN] when either side could not be read, and callers must **not** treat that as a
 * refusal. The package manager is the real gate; a failed read is this code's problem,
 * not the update's, and turning it into a block would make the feature unusable on any
 * device whose `PackageManager` behaves unexpectedly. Overlap rather than equality
 * because a key rotation leaves an app with a certificate history.
 */
internal fun signingVerdict(installed: Set<String>, candidate: Set<String>): SigningVerdict = when {
    installed.isEmpty() || candidate.isEmpty() -> SigningVerdict.UNKNOWN
    installed.intersect(candidate).isNotEmpty() -> SigningVerdict.COMPATIBLE
    else -> SigningVerdict.INCOMPATIBLE
}

/** The running build. Null only if the package manager cannot describe this app. */
internal fun Context.installedIdentity(): AppIdentity? = runCatching {
    packageManager.getPackageInfo(packageName, signingFlags()).toIdentity()
}.getOrNull()

/** A downloaded APK, read without installing it. Null when the file is not a package. */
internal fun Context.archiveIdentity(file: File): AppIdentity? = runCatching {
    packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())?.toIdentity()
}.getOrNull()

/**
 * `versionName (versionCode)` for the running build, or `unknown`.
 *
 * The one way this app reads its own version — there is no `BuildConfig` generated
 * anywhere, because the `buildConfig` feature is off.
 */
internal fun Context.installedVersionLabel(): String =
    installedIdentity()?.label ?: "unknown"

@Suppress("DEPRECATION")
private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    PackageManager.GET_SIGNING_CERTIFICATES
} else {
    PackageManager.GET_SIGNATURES
}

private fun PackageInfo.toIdentity(): AppIdentity {
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
    return AppIdentity(
        packageName = packageName,
        versionName = versionName ?: "unknown",
        versionCode = code,
        signerDigests = signerDigests(),
    )
}

/**
 * Every certificate this build is signed by, plus its rotation history, hashed.
 *
 * The history is included because a rotated key leaves the installed app holding the old
 * certificate and the new APK holding the new one; comparing only the current signers
 * would call that a mismatch.
 */
private fun PackageInfo.signerDigests(): Set<String> = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = signingInfo ?: return emptySet()
        val certificates = if (info.hasMultipleSigners()) {
            info.apkContentsSigners
        } else {
            info.signingCertificateHistory
        }
        certificates.orEmpty().map { it.toByteArray().sha256Hex() }.toSet()
    } else {
        @Suppress("DEPRECATION")
        signatures.orEmpty().map { it.toByteArray().sha256Hex() }.toSet()
    }
}.getOrDefault(emptySet())

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
