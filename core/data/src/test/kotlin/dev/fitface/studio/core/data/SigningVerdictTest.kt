package dev.fitface.studio.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The check that turns an unreadable package-manager refusal into a sentence.
 *
 * The middle case is the point of the whole feature: CI signs the release APK with a
 * keystore restored from a secret and a local build uses AGP's own generated one, so a
 * development build and the published one routinely disagree. Left to the package
 * manager, that surfaces as `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the next thing
 * anyone tries is uninstalling — which deletes every saved project.
 *
 * The third case is the one that is easy to get backwards. An unreadable certificate must
 * **not** block: the package manager is the real gate, and refusing on our own failed read
 * would make the feature unusable for no gain in safety.
 */
class SigningVerdictTest {

    @Test
    fun theSameKeyInstallsOverItself() {
        assertEquals(SigningVerdict.COMPATIBLE, signingVerdict(setOf("aa"), setOf("aa")))
    }

    /** A rotated key leaves a history, so overlap is enough. */
    @Test
    fun anOverlappingHistoryIsStillTheSameApp() {
        assertEquals(
            SigningVerdict.COMPATIBLE,
            signingVerdict(installed = setOf("old", "new"), candidate = setOf("new")),
        )
    }

    @Test
    fun aDifferentKeyIsRefusedBeforeThePackageManagerIsAsked() {
        assertEquals(
            SigningVerdict.INCOMPATIBLE,
            signingVerdict(installed = setOf("ci-key"), candidate = setOf("local-key")),
        )
    }

    @Test
    fun anUnreadableCertificateDoesNotBlockTheInstall() {
        assertEquals(SigningVerdict.UNKNOWN, signingVerdict(emptySet(), setOf("aa")))
        assertEquals(SigningVerdict.UNKNOWN, signingVerdict(setOf("aa"), emptySet()))
        assertEquals(SigningVerdict.UNKNOWN, signingVerdict(emptySet(), emptySet()))
    }
}
