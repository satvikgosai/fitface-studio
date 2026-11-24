package dev.fitface.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TextPictureTest {
    @Test
    fun whatCannotBeDrawnIsNamedBeforeAnythingIsDrawn() {
        assertNull(TextPicture("steps").problem)
        assertEquals(TextProblem.EMPTY, TextPicture("").problem)
        assertEquals(TextProblem.EMPTY, TextPicture(" \n ").problem)
        assertNull(TextPicture("a".repeat(TextPicture.MAX_LENGTH)).problem)
        assertEquals(TextProblem.TOO_LONG, TextPicture("a".repeat(TextPicture.MAX_LENGTH + 1)).problem)
        assertNull(TextPicture("a\nb\nc\nd").problem)
        assertEquals(TextProblem.TOO_MANY_LINES, TextPicture("a\nb\nc\nd\ne").problem)
    }

    @Test
    fun trailingBlankLinesAreNotLinesButBlankLinesBetweenAre() {
        assertEquals(listOf("a"), TextPicture("a\n\n \n").lines)
        assertEquals(listOf("a", "", "b"), TextPicture("a\n\nb").lines)
        assertNull("four lines once the trailing ones go", TextPicture("a\nb\nc\nd\n\n").problem)
    }

    @Test
    fun sizesStayWithinTheirRangeAndAPreparedTextIsAPictureOrAProblem() {
        assertEquals(TextPicture.MIN_SIZE_PX, TextPicture.size(1))
        assertEquals(TextPicture.MAX_SIZE_PX, TextPicture.size(999))
        assertEquals(24, TextPicture.size(24))
        assertThrows(IllegalArgumentException::class.java) { PreparedText(TextPicture("a"), null, null) }
    }
}
