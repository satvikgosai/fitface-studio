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
        assertEquals(1, AodPreviewComposer.sampleSpriteFrame(sourceId = 2, frameCount = 3))
        assertEquals(0, AodPreviewComposer.sampleSpriteFrame(sourceId = 3, frameCount = 10))
        assertEquals(0, AodPreviewComposer.sampleSpriteFrame(sourceId = 10, frameCount = 6))
        assertEquals(8, AodPreviewComposer.sampleSpriteFrame(sourceId = 11, frameCount = 10))
    }

    /** The date the vendor renders its previews at: Saturday 28 December 2024. */
    @Test
    fun theDateDigitsSpellTheSampledDate() {
        assertEquals(2, AodPreviewComposer.sampleSpriteFrame(sourceId = 19, frameCount = 4))
        assertEquals(8, AodPreviewComposer.sampleSpriteFrame(sourceId = 20, frameCount = 10))
        assertEquals(1, AodPreviewComposer.sampleSpriteFrame(sourceId = 22, frameCount = 2))
        assertEquals(2, AodPreviewComposer.sampleSpriteFrame(sourceId = 23, frameCount = 10))
        // Month artwork is zero-based, so December is the twelfth frame, not the eleventh.
        assertEquals(11, AodPreviewComposer.sampleSpriteFrame(sourceId = 21, frameCount = 12))
    }

    /** A table shorter than the digit asks for still names a real frame, as the watch does. */
    @Test
    fun anOutOfRangeDigitIsReducedRatherThanClamped() {
        assertEquals(3, AodPreviewComposer.sampleSpriteFrame(sourceId = 11, frameCount = 5))
        assertEquals(0, AodPreviewComposer.sampleSpriteFrame(sourceId = 11, frameCount = 1))
    }

    /** An unrecognised reading draws its first frame — real artwork, just not a value. */
    @Test
    fun anUnknownSpriteSourceFallsToItsFirstFrame() {
        assertEquals(0, AodPreviewComposer.sampleSpriteFrame(sourceId = 999, frameCount = 7))
    }

    @Test
    fun theHandsPointAtTheSampledTime() {
        val hour = requireNotNull(AodPreviewComposer.sampleHandFraction(sourceId = 1))
        val minute = requireNotNull(AodPreviewComposer.sampleHandFraction(sourceId = 9))

        // 10:08 puts the hour hand a little past ten and the minute hand on the eight:
        // 304° and 48° clockwise from twelve, across a hand storing the usual 0..360.
        assertEquals(304.0, AodPreviewComposer.handAngle(0, 360, hour), 0.5)
        assertEquals(48.0, AodPreviewComposer.handAngle(0, 360, minute), 0.5)
        // The hour hand moves with the minutes. Pinned to the hour it would sit exactly
        // on the ten, which is not what 10:08 looks like.
        assertNotEquals(300.0, AodPreviewComposer.handAngle(0, 360, hour), 0.5)
    }

    /** A needle sweeping less than a full turn maps across the sweep it stores. */
    @Test
    fun aPartialSweepIsMappedAcrossItsOwnRange() {
        val minute = requireNotNull(AodPreviewComposer.sampleHandFraction(sourceId = 9))

        assertEquals(24.0, AodPreviewComposer.handAngle(0, 180, minute), 0.5)
        assertEquals(-24.0, AodPreviewComposer.handAngle(0, -180, minute), 0.5)
    }

    /** Three sources are clock hands — hour, minute and second — and the second is 12. */
    @Test
    fun theSecondHandIsAClockHandToo() {
        val second = requireNotNull(AodPreviewComposer.sampleHandFraction(sourceId = 13))

        assertEquals(0.0, AodPreviewComposer.handAngle(0, 360, second), 0.5)
    }

    /**
     * Nothing else is a clock hand. The same primitive sweeps steps, battery, heart rate
     * and calories, and a guessed angle for one of those would be a preview stating a
     * reading the watch never took — so it has no sampled value, and the render says it
     * left something out rather than pointing a needle somewhere.
     */
    @Test
    fun aGaugeNeedleHasNoSampledValue() {
        // Every non-clock reading Hand accepts, from `WidgetSchema`'s own list.
        listOf(17, 21, 29, 37, 41, 48, 70, 71).forEach { source ->
            assertNull(
                "source $source should have no sampled value",
                AodPreviewComposer.sampleHandFraction(source),
            )
        }
    }
}
