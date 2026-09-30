package dev.fitface.studio.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * That the sampler picks the frames and angles the vendor's own style previews are
 * rendered at, so the generated AOD row cannot disagree with the packaged style pictures
 * beside it — or with itself, digit against hand.
 *
 * Pure arithmetic, so it needs no container and no corpus: the sampled time is the whole
 * input, and these are the four sources every corpus AOD's clock is built out of.
 */
class AodSampleTimeTest {
    @Test
    fun theDigitsSpellTheSampledTime() {
        // 10:08 — hour tens, hour units, minute tens, minute units.
        assertEquals(1, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 2, count = 3))
        assertEquals(0, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 3, count = 10))
        assertEquals(0, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 10, count = 6))
        assertEquals(8, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 11, count = 10))
    }

    /** The date the vendor renders its previews at: Saturday 28 December 2024. */
    @Test
    fun theDateDigitsSpellTheSampledDate() {
        assertEquals(2, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 19, count = 4))
        assertEquals(8, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 20, count = 10))
        assertEquals(1, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 22, count = 2))
        assertEquals(2, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 23, count = 10))
        // Month artwork is zero-based, so December is the twelfth frame, not the eleventh.
        assertEquals(11, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 21, count = 12))
    }

    /** A table shorter than the digit asks for still names a real frame, as the watch does. */
    @Test
    fun anOutOfRangeDigitIsReducedRatherThanClamped() {
        assertEquals(3, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 11, count = 5))
        assertEquals(0, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 11, count = 1))
    }

    /** An unrecognised reading draws its first frame — real artwork, just not a value. */
    @Test
    fun anUnknownSpriteSourceFallsToItsFirstFrame() {
        assertEquals(0, dev.fitface.studio.core.format.WidgetPreviewSample.spriteFrame(source = 999, count = 7))
    }

    @Test
    fun theHandsPointAtTheSampledTime() {
        val hour = requireNotNull(dev.fitface.studio.core.format.WidgetPreviewSample.fraction(source = 1))
        val minute = requireNotNull(dev.fitface.studio.core.format.WidgetPreviewSample.fraction(source = 9))

        // 10:08 puts the hour hand a little past ten and the minute hand on the eight:
        // 304° and 48° clockwise from twelve, across a hand storing the usual 0..360.
        assertEquals(304.0, dev.fitface.studio.core.format.WidgetPreviewSample.handAngle(0, 360, hour), 0.5)
        assertEquals(48.0, dev.fitface.studio.core.format.WidgetPreviewSample.handAngle(0, 360, minute), 0.5)
        // The hour hand moves with the minutes. Pinned to the hour it would sit exactly
        // on the ten, which is not what 10:08 looks like.
        assertNotEquals(300.0, dev.fitface.studio.core.format.WidgetPreviewSample.handAngle(0, 360, hour), 0.5)
    }

    /** A needle sweeping less than a full turn maps across the sweep it stores. */
    @Test
    fun aPartialSweepIsMappedAcrossItsOwnRange() {
        val minute = requireNotNull(dev.fitface.studio.core.format.WidgetPreviewSample.fraction(source = 9))

        assertEquals(24.0, dev.fitface.studio.core.format.WidgetPreviewSample.handAngle(0, 180, minute), 0.5)
        assertEquals(-24.0, dev.fitface.studio.core.format.WidgetPreviewSample.handAngle(0, -180, minute), 0.5)
    }

    /** Three sources are clock hands — hour, minute and second — and the second is 12. */
    @Test
    fun theSecondHandIsAClockHandToo() {
        val second = requireNotNull(dev.fitface.studio.core.format.WidgetPreviewSample.fraction(source = 13))

        assertEquals(0.0, dev.fitface.studio.core.format.WidgetPreviewSample.handAngle(0, 360, second), 0.5)
    }

    /**
     * The same primitive sweeps health readings. These now have explicit illustrative
     * samples too; the UI discloses that the scene is not live watch data.
     */
    @Test
    fun aGaugeNeedleUsesAnExplicitIllustrativeSample() {
        // Every non-clock reading Hand accepts, from `WidgetSchema`'s own list.
        listOf(17, 21, 29, 37, 41, 48, 70, 71).forEach { source ->
            org.junit.Assert.assertNotNull(
                "source $source must have an illustrative sampled value",
                dev.fitface.studio.core.format.WidgetPreviewSample.fraction(source),
            )
        }
    }
}
