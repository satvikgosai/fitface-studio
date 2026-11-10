package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFaceNumberTest {
    private fun id(number: Int) = number.toString().padStart(5, '0')

    @Test
    fun theLowestFreeNumberIsGivenOut() {
        assertEquals("00201", nextCustomFaceId(emptySet()))
        // Other faces' numbers do not count; a gap left by a deletion is filled first.
        assertEquals("00201", nextCustomFaceId(setOf("00006", "00112", "00254")))
        assertEquals("00202", nextCustomFaceId(setOf("00201", "00203")))
    }

    @Test
    fun withAllFiftyTakenThereIsNone() {
        assertEquals("00250", nextCustomFaceId((201..249).map(::id).toSet()))
        assertNull(nextCustomFaceId((201..250).map(::id).toSet()))
    }

    /** The range fits the install command's one byte, below the Photos face. */
    @Test
    fun onlyFiveDigitIdsInTheRangeAreCustomFaceNumbers() {
        assertTrue(isCustomFaceNumber("00201"))
        assertTrue(isCustomFaceNumber("00250"))
        listOf("00200", "00251", "00254", "00006", "201", "0201", "0020a", "").forEach {
            assertFalse(it, isCustomFaceNumber(it))
        }
        assertTrue(CUSTOM_FACE_NUMBERS.last < PHOTO_ALBUM_FACE_ID)
        CUSTOM_FACE_NUMBERS.forEach { DirectInstallPayload.create(it, 0, "SM-R390_${id(it)}_256x402.bin", byteArrayOf(1)) }
    }
}
