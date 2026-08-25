package dev.fitface.studio.core.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The companion app does not have one package name, and assuming it did is what made
 * issue #2: a phone with the watch paired, connected and holding a live accessory session
 * was told its companion app was not installed, because the app knew only the id that
 * that particular model is refused.
 */
class CompanionResolutionTest {

    private val watchmanager = "com.samsung.android.app.watchmanager"
    private val watchmanager2 = "com.samsung.android.app.watchmanager2"
    private val stub = "com.samsung.android.app.watchmanagerstub"

    /**
     * The regression. `watchmanager2` is what the store serves an SM-A107M or SM-A115M —
     * `watchmanager` is refused for those models — so on the reporter's phone this is the
     * only companion id present.
     */
    @Test
    fun theEntryLevelDevicesCompanionIdResolves() {
        assertEquals(watchmanager2, CompanionResolution.preferred(setOf(watchmanager2)))
    }

    @Test
    fun theMainstreamDevicesCompanionIdResolves() {
        assertEquals(watchmanager, CompanionResolution.preferred(setOf(watchmanager)))
    }

    /** Both ids on one phone is not a shape worth having an opinion about beyond order. */
    @Test
    fun bothPresentPrefersTheFirst() {
        assertEquals(
            watchmanager,
            CompanionResolution.preferred(setOf(watchmanager2, watchmanager)),
        )
    }

    /**
     * The firmware preload counts. It is what a reader taps when the full app is refused
     * for their model, so reporting "no companion app" while it sits on their home screen
     * would be the same failure in a smaller font.
     */
    @Test
    fun theFirmwarePreloadCountsAsACompanion() {
        assertEquals(stub, CompanionResolution.preferred(setOf(stub)))
    }

    /**
     * A real app outranks the preload, which cannot pair a watch on its own — it exists to
     * fetch the app that can.
     */
    @Test
    fun aRealCompanionOutranksThePreload() {
        assertEquals(watchmanager2, CompanionResolution.preferred(setOf(stub, watchmanager2)))
    }

    @Test
    fun noCompanionResolvesToNothingRatherThanToAGuess() {
        assertNull(CompanionResolution.preferred(emptySet()))
        assertNull(CompanionResolution.preferred(setOf("com.samsung.wearable.fit3plugin")))
    }

    /**
     * The plugin is not a companion app. It is the app that owns the accessory channel,
     * and conflating the two is what let a missing companion app read as a missing
     * channel.
     */
    @Test
    fun thePluginIsNotACompanionCandidate() {
        assertTrue(
            CompanionResolution.COMPANION_PACKAGES.none { it.contains("plugin") },
        )
    }
}
