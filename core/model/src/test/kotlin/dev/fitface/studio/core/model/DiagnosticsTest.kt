package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun theBufferKeepsTheMostRecentEntriesAndDropsTheOldest() {
        val log = DiagnosticsLog(capacity = 3)
        repeat(5) { log.info("T", "entry $it") }

        assertEquals(listOf("entry 2", "entry 3", "entry 4"), log.snapshot().map { it.message })
    }

    @Test
    fun aBluetoothAddressNeverReachesTheBuffer() {
        // :core:delivery reads bonded-device addresses, so this is a real call site and
        // not a hypothetical. Scrubbing happens on the way in, so the address is never
        // held in memory waiting to be rendered.
        val log = DiagnosticsLog()
        log.warn("Transfer", "peer AA:BB:CC:11:22:33 went away")

        val recorded = log.snapshot().single().message
        assertEquals("peer <mac> went away", recorded)
        assertFalse(recorded.contains("AA:BB"))
    }

    @Test
    fun aQueryStringIsDroppedBecauseItCarriesTheDeviceIdentifier() {
        // The catalogue request sends Settings.Secure.ANDROID_ID as its extuk parameter,
        // so a full URL is never safe to keep even though the path is useful.
        val log = DiagnosticsLog()
        log.warn("Catalog", "GET https://vas.samsungapps.com/product/list.as?extuk=abc123&cc=KOR")

        val recorded = log.snapshot().single().message
        assertTrue(recorded.endsWith("/product/list.as?<redacted>"))
        assertFalse(recorded.contains("abc123"))
    }

    @Test
    fun pickedImagePathsAndUrisAreDroppedWholesale() {
        val log = DiagnosticsLog()
        log.warn("Editor", "opened content://media/external/images/1/Holiday-with-Sam.jpg")
        log.warn("Editor", "opened /storage/emulated/0/DCIM/Camera/private.jpg")

        val messages = log.snapshot().map { it.message }
        assertEquals(listOf("opened <uri>", "opened <path>"), messages)
    }

    @Test
    fun aFailureIsSummarisedToThisAppsOwnFrames() {
        val log = DiagnosticsLog()
        val cause = IllegalStateException("the raster moved")
        log.error("Editor", "commit refused", error = WatchFaceException("nope", cause = cause))

        val failure = log.snapshot().single().failure.orEmpty()
        assertTrue(failure.startsWith("WatchFaceException: nope"))
        assertTrue(failure.contains("IllegalStateException: the raster moved"))
        // Framework frames say nothing a reader of this repository needs.
        assertFalse(failure.contains("org.junit"))
    }

    @Test
    fun technicalDetailIsKeptBesideTheMessageBecauseItIsTheHalfThatExplains() {
        val log = DiagnosticsLog()
        log.warn(
            "Catalog",
            "The watch-face catalogue did not return any faces.",
            "resultCode=1005 message=locale not supported",
        )

        assertEquals(
            "resultCode=1005 message=locale not supported",
            log.snapshot().single().detail,
        )
    }

    @Test
    fun theMirrorSeesTheScrubbedEntryAndTheRawThrowable() {
        val log = DiagnosticsLog()
        var seen: DiagnosticsEntry? = null
        var raw: Throwable? = null
        log.mirror = { entry, error -> seen = entry; raw = error }
        val failure = IllegalStateException("boom")

        log.warn("Transfer", "lost AA:BB:CC:11:22:33", error = failure)

        assertEquals("lost <mac>", seen?.message)
        assertEquals(failure, raw)
    }

    @Test
    fun theReportRendersRelativeTimesSoItCarriesNoWallClock() {
        val report = DiagnosticsReport(
            app = "0.1.0 (16)",
            android = "15 (sdk 35)",
            device = "Google Pixel 7",
            locale = "es_419 (sent as es_MX)",
            sections = listOf(DiagnosticsSection("catalogue", listOf("faces=0"))),
            entries = listOf(
                DiagnosticsEntry(1_000L, DiagnosticsLevel.INFO, "A", "first"),
                DiagnosticsEntry(3_250L, DiagnosticsLevel.WARN, "B", "second", detail = "x=1"),
            ),
        )

        val rendered = report.render()
        assertTrue(rendered.contains("locale=es_419 (sent as es_MX)"))
        assertTrue(rendered.contains("## catalogue\nfaces=0"))
        assertTrue(rendered.contains("+0.000s I A  first"))
        assertTrue(rendered.contains("+2.250s W B  second"))
        assertTrue(rendered.contains("    detail: x=1"))
        assertFalse(rendered.contains("1000"))
    }

    @Test
    fun anEmptyReportStillSaysSoRatherThanRenderingNothing() {
        val rendered = DiagnosticsReport("0.1.0 (16)", "15 (sdk 35)", "Pixel", "en_US").render()

        assertTrue(rendered.contains("## log\n(nothing recorded)"))
    }

    @Test
    fun sectionLinesAreScrubbedToo() {
        // A section is assembled by an allowlist, but the allowlist is written by hand and
        // this is the backstop for the day someone adds the wrong field to it.
        val report = DiagnosticsReport(
            app = "0.1.0 (16)",
            android = "15 (sdk 35)",
            device = "Pixel",
            locale = "en_US",
            sections = listOf(DiagnosticsSection("install", listOf("watch=AA:BB:CC:11:22:33"))),
        )

        assertTrue(report.render().contains("watch=<mac>"))
    }
}
