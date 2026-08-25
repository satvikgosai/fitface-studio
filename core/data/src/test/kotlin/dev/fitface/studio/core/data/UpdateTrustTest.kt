package dev.fitface.studio.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where an update may come from.
 *
 * `isTrustedDownloadHost`, the equivalent for face packages, has no test. This one does,
 * because the suffix check is the kind that is subtly wrong: matching
 * `endsWith("githubusercontent.com")` without the leading dot also accepts
 * `githubusercontent.com.example.net`, which is a host an attacker can register.
 */
class UpdateTrustTest {

    @Test
    fun theFeedAndTheRedirectTargetAreBothAllowed() {
        assertTrue(isTrustedUpdateHost("api.github.com"))
        assertTrue(isTrustedUpdateHost("github.com"))
        // Where a release asset actually lands after the 302.
        assertTrue(isTrustedUpdateHost("release-assets.githubusercontent.com"))
        assertTrue(isTrustedUpdateHost("objects.githubusercontent.com"))
        assertTrue(isTrustedUpdateHost("githubusercontent.com"))
    }

    @Test
    fun aHostThatMerelyEndsWithTheNameIsRefused() {
        assertFalse(isTrustedUpdateHost("githubusercontent.com.example.net"))
        assertFalse(isTrustedUpdateHost("notgithub.com"))
        assertFalse(isTrustedUpdateHost("github.com.evil.tld"))
        assertFalse(isTrustedUpdateHost("evil-github.com"))
    }

    @Test
    fun anUnrelatedHostIsRefused() {
        assertFalse(isTrustedUpdateHost("example.com"))
        assertFalse(isTrustedUpdateHost(""))
        assertFalse(isTrustedUpdateHost("samsungapps.com"))
    }
}
