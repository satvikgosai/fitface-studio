package dev.fitface.studio.core.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is installed advises; it does not decide.
 *
 * This used to be a gate — three package names ANDed, any one missing replaced the
 * checklist with a dead end — and it refused a phone whose watch was paired, connected
 * and holding a live accessory session, because the companion app was installed under the
 * second of the two package names it ships under. Two of the three names were also the
 * wrong question: the companion app carries no accessory code at all.
 *
 * So the rule these tests hold is that no environment can block the checklist, and the
 * only thing an incomplete environment does is say so.
 */
class EnvironmentAdvisoryTest {

    private val reporterPhone = CompanionEnvironment(
        // What the reporter's SM-A107M actually looked like: the plugin present, the
        // framework reachable, and a companion app the old check could not name.
        pluginInstalled = true,
        companionAppInstalled = true,
        accessoryAgentCount = 1,
        frameworkVerdict = FrameworkVerdict.USABLE,
        probed = true,
    )

    // A probe always settles the framework either way, so UNKNOWN is a pre-probe state
    // only — reading it as "missing" would be the overclaiming this change removed.
    private val emptyPhone = CompanionEnvironment(
        frameworkVerdict = FrameworkVerdict.MISSING,
        probed = true,
    )

    @Test
    fun aWorkingPhoneIsToldNothing() {
        assertNull(reporterPhone.advisory)
    }

    /** Before the first probe there is nothing to say, and saying it would be a guess. */
    @Test
    fun anUnprobedEnvironmentIsSilentRatherThanPessimistic() {
        assertNull(CompanionEnvironment().advisory)
    }

    /**
     * The framework is the one thing that genuinely has to be reachable, so it outranks
     * the rest: it explains every later failure, where a missing companion app explains
     * only that there is nowhere to tap.
     */
    @Test
    fun theFrameworkOutranksTheOtherAdvisories() {
        assertEquals(EnvironmentAdvisory.FRAMEWORK_MISSING, emptyPhone.advisory)
    }

    @Test
    fun noAgentAppIsWorthSayingOnceTheFrameworkIsFine() {
        val state = emptyPhone.copy(frameworkVerdict = FrameworkVerdict.USABLE)

        assertEquals(EnvironmentAdvisory.NO_ACCESSORY_APP, state.advisory)
    }

    /**
     * A missing companion app is the mildest of the three. It is also the one the old gate
     * treated as fatal.
     */
    @Test
    fun aMissingCompanionAppIsTheMildestAdvisory() {
        val state = reporterPhone.copy(companionAppInstalled = false)

        assertEquals(EnvironmentAdvisory.NO_COMPANION_APP, state.advisory)
        assertTrue(state.hasAccessoryAgent)
    }

    /**
     * An agent found by capability counts even when no name matched, which is the whole
     * point of counting them: the next id Samsung forks must not read as an empty phone.
     */
    @Test
    fun anUnnamedAgentAppStillCounts() {
        val state = emptyPhone.copy(
            accessoryAgentCount = 1,
            frameworkVerdict = FrameworkVerdict.USABLE,
        )

        assertTrue(state.hasAccessoryAgent)
        assertEquals(EnvironmentAdvisory.NO_COMPANION_APP, state.advisory)
    }

    /**
     * The regression, in the form that matters: an empty phone must still be able to work
     * the checklist. Setup completion is about permissions, peers and the channel handover
     * — things the app can actually observe — and never about what is installed.
     */
    @Test
    fun noEnvironmentCanBlockTheChecklist() {
        val state = DirectInstallState(
            environment = emptyPhone,
            helperNearbyGranted = true,
            watchfacePeerCached = true,
            otaPeerCached = true,
            pluginNearbyGranted = false,
        )

        assertTrue("discovery must remain reachable", state.setupComplete)
        assertTrue(state.isStepDone(SetupStep.PEERS_DISCOVERED))
        assertTrue(state.isStepDone(SetupStep.PLUGIN_RELEASED))
    }

    /**
     * Step 1 reports what was seen and nothing more. It is marked done by either half,
     * because the reader may have connected the watch months before opening this app and
     * neither half proves they did not.
     */
    @Test
    fun stepOneIsSatisfiedByEitherHalf() {
        assertTrue(
            DirectInstallState(environment = reporterPhone)
                .isStepDone(SetupStep.COMPANION_PRESENT),
        )
        assertTrue(
            DirectInstallState(environment = reporterPhone.copy(companionAppInstalled = false))
                .isStepDone(SetupStep.COMPANION_PRESENT),
        )
        assertTrue(
            DirectInstallState(
                environment = reporterPhone.copy(pluginInstalled = false, accessoryAgentCount = 0),
            ).isStepDone(SetupStep.COMPANION_PRESENT),
        )
        assertFalse(
            DirectInstallState(environment = emptyPhone)
                .isStepDone(SetupStep.COMPANION_PRESENT),
        )
    }

    /**
     * The companion app is not evidence about the framework. Accepting it as a provider
     * is what hid the fact that this app could not see the framework at all — the probe
     * said "available" on every phone that had a companion app, so a package-visibility
     * problem that breaks the SDK's own bind went unnoticed until a phone turned up
     * without one.
     */
    @Test
    fun aCompanionAppIsNotEvidenceAboutTheFramework() {
        val state = CompanionEnvironment(
            companionAppInstalled = true,
            pluginInstalled = true,
            frameworkVerdict = FrameworkVerdict.MISSING,
            probed = true,
        )

        assertEquals(EnvironmentAdvisory.FRAMEWORK_MISSING, state.advisory)
    }
}
