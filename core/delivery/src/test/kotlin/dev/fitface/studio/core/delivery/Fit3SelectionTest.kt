package dev.fitface.studio.core.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which bonded watch a transfer opens RFCOMM against.
 *
 * This used to end in `singleOrNull()`, so the moment a phone had bonded a second
 * matching watch — a replacement unit, someone else's — the send reported "no watch"
 * with nothing saying why, and unpairing was the only cure anyone could guess at.
 */
class Fit3SelectionTest {
    /** [name] only labels the test case: candidates carry no name, so no reason can print one. */
    @Suppress("UNUSED_PARAMETER")
    private fun candidate(
        name: String,
        isDiscoveredPeer: Boolean = false,
        connected: Boolean = false,
    ) = Fit3Candidate(isDiscoveredPeer = isDiscoveredPeer, connected = connected)

    private fun selectedIndex(vararg candidates: Fit3Candidate): Int {
        val selection = selectFit3(candidates.toList())
        assertTrue("$selection", selection is Fit3Selection.Selected)
        return (selection as Fit3Selection.Selected).index
    }

    private fun reason(vararg candidates: Fit3Candidate): String {
        val selection = selectFit3(candidates.toList())
        assertTrue("$selection", selection is Fit3Selection.Unavailable)
        return (selection as Fit3Selection.Unavailable).reason
    }

    /**
     * Unchanged behaviour: with one bonded watch nothing is asked about connectivity.
     * The RFCOMM connect that follows reports a watch that is not there far better than
     * a GATT-only guess would.
     */
    @Test
    fun oneBondedWatchIsUsedWithoutAskingWhetherItIsConnected() {
        assertEquals(0, selectedIndex(candidate("Galaxy Fit3 (1A2B)")))
    }

    @Test
    fun noBondedWatchSaysToPairOneRatherThanNamingACount() {
        val reason = reason()

        assertTrue(reason, reason.contains("No bonded Fit3"))
        assertTrue(reason, reason.contains("companion app"))
    }

    /** The regression: two bonded watches used to resolve to no device at all. */
    @Test
    fun twoBondedWatchesResolveToTheDiscoveredPeerRatherThanToNothing() {
        assertEquals(
            1,
            selectedIndex(
                candidate("Galaxy Fit3 (1A2B)"),
                candidate("Galaxy Fit3 (9Z8Y)", isDiscoveredPeer = true),
            ),
        )
    }

    /**
     * The accessory the framework is already talking to is the watch being sent to, so
     * it outranks anything the adapter merely reports a link to.
     */
    @Test
    fun theDiscoveredPeerOutranksAMerelyConnectedWatch() {
        assertEquals(
            1,
            selectedIndex(
                candidate("Galaxy Fit3 (1A2B)", connected = true),
                candidate("Galaxy Fit3 (9Z8Y)", isDiscoveredPeer = true),
            ),
        )
    }

    /**
     * The peer's accessory address can be absent or in a form no bonded device matches,
     * which is what the adapter's connected set is the fallback for.
     */
    @Test
    fun theConnectedOneWinsWhenNoCandidateMatchesTheDiscoveredPeer() {
        assertEquals(
            0,
            selectedIndex(
                candidate("Galaxy Fit3 (1A2B)", connected = true),
                candidate("Galaxy Fit3 (9Z8Y)"),
            ),
        )
    }

    /**
     * A tie is reported as a tie. Silence here is the actual complaint: the send failed
     * with "no watch" on a phone with two of them bonded. It is counted, not named: the
     * reason reaches the diagnostics report, and a Fit3's name carries an address suffix.
     */
    @Test
    fun anAmbiguousChoiceCountsTheWatchesInsteadOfClaimingThereAreNone() {
        val reason = reason(
            candidate("Galaxy Fit3 (1A2B)"),
            candidate("Galaxy Fit3 (9Z8Y)"),
        )

        assertTrue(reason, reason.contains("2 bonded Fit3 watches"))
        assertTrue("it has to say what to do next", reason.contains("companion app"))
    }

    @Test
    fun twoConnectedWatchesAreReportedRatherThanGuessedBetween() {
        val reason = reason(
            candidate("Galaxy Fit3 (1A2B)", connected = true),
            candidate("Galaxy Fit3 (9Z8Y)", connected = true),
            candidate("SM-R390"),
        )

        assertTrue(reason, reason.contains("2 bonded Fit3 watches are connected"))
    }

    /** Three bonded and one connected still resolves, rather than refusing on count. */
    @Test
    fun theConnectedOneIsPickedOutOfThreeBondedWatches() {
        assertEquals(
            2,
            selectedIndex(
                candidate("Galaxy Fit3 (1A2B)"),
                candidate("SM-R390"),
                candidate("Galaxy Fit3 (9Z8Y)", connected = true),
            ),
        )
    }

    /** Nothing here reads an address or a name, so nothing here can print either. */
    @Test
    fun aReasonNeverCarriesABluetoothIdentity() {
        val reasons = listOf(
            reason(),
            reason(candidate("Galaxy Fit3 (1A2B)"), candidate("Galaxy Fit3 (9Z8Y)")),
            reason(
                candidate("Galaxy Fit3 (1A2B)", connected = true),
                candidate("Galaxy Fit3 (9Z8Y)", connected = true),
            ),
        )

        reasons.forEach { reason ->
            assertTrue(reason, !Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").containsMatchIn(reason))
            assertTrue(reason, !reason.contains("1A2B") && !reason.contains("9Z8Y"))
        }
    }
}
