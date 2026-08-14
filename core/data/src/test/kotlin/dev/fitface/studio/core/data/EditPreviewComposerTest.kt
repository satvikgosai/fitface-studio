package dev.fitface.studio.core.data

import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.RemovedWidget
import dev.fitface.studio.core.model.WidgetGuide
import dev.fitface.studio.core.model.WidgetImageLayer
import org.junit.Assert.assertEquals
import org.junit.Test

class EditPreviewComposerTest {
    @Test
    fun embeddedWidgetLayerMovesFromItsDecodedFrame() {
        val width = 4
        val height = 2
        val background = PreviewFrame(width, height, IntArray(width * height) { Black })
        val reference = background.argb.copyOf().apply {
            this[0] = Red
        }
        val widget = guide(
            globalIndex = 2,
            x = 2,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 1,
            height = 1,
        )

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = PreviewFrame(width, height, reference),
            widgets = listOf(widget),
            imageLayers = listOf(
                WidgetImageLayer(
                    globalIndex = widget.globalIndex,
                    frame = PreviewFrame(1, 1, intArrayOf(Blue)),
                ),
            ),
        )

        assertEquals(Black, result.composed.argb[0])
        assertEquals(Blue, result.composed.argb[2])
        assertEquals(Blue, result.widgetOverlay.argb[2])
    }

    @Test
    fun embeddedWidgetMoveClearsReferenceEdgesOutsideItsDecodedAlpha() {
        val width = 5
        val height = 1
        val background = PreviewFrame(width, height, IntArray(width * height) { Black })
        val reference = background.argb.copyOf().apply {
            this[0] = Red
            this[1] = Red
        }
        val widget = guide(
            globalIndex = 2,
            x = 3,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 2,
            height = 1,
        )

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = PreviewFrame(width, height, reference),
            widgets = listOf(widget),
            imageLayers = listOf(
                WidgetImageLayer(
                    globalIndex = widget.globalIndex,
                    frame = PreviewFrame(2, 1, intArrayOf(Blue, Transparent)),
                ),
            ),
        )

        assertEquals(Black, result.composed.argb[0])
        assertEquals(Black, result.composed.argb[1])
        assertEquals(Blue, result.composed.argb[3])
        assertEquals(Transparent, result.widgetOverlay.argb[1])
    }

    @Test
    fun embeddedOwnershipMaskPreservesSmallerOverlappingWidgetPixels() {
        val width = 5
        val height = 1
        val background = PreviewFrame(width, height, IntArray(width * height) { Black })
        val reference = background.argb.copyOf().apply {
            this[0] = Red
            this[1] = Green
        }
        val embedded = guide(
            globalIndex = 8,
            x = 3,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 3,
            height = 1,
        )
        val child = guide(
            globalIndex = 3,
            x = 1,
            y = 0,
            originalX = 1,
            originalY = 0,
            width = 1,
            height = 1,
        )

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = PreviewFrame(width, height, reference),
            widgets = listOf(embedded, child),
            imageLayers = listOf(
                WidgetImageLayer(
                    globalIndex = embedded.globalIndex,
                    frame = PreviewFrame(
                        3,
                        1,
                        intArrayOf(Blue, Transparent, Transparent),
                    ),
                ),
            ),
        )

        assertEquals(Black, result.composed.argb[0])
        assertEquals(Green, result.composed.argb[1])
        assertEquals(Blue, result.composed.argb[3])
    }

    @Test
    fun movingLargeOverlapLeavesSmallerWidgetPixelsInPlace() {
        val width = 5
        val height = 4
        val background = PreviewFrame(width, height, IntArray(width * height) { Black })
        val reference = background.argb.copyOf().apply {
            this[1 * width + 1] = Red
            this[2 * width + 2] = Green
        }
        val large = guide(
            globalIndex = 8,
            x = 1,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 4,
            height = 4,
        )
        val child = guide(
            globalIndex = 3,
            x = 2,
            y = 2,
            originalX = 2,
            originalY = 2,
            width = 1,
            height = 1,
        )

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = PreviewFrame(width, height, reference),
            widgets = listOf(large, child),
        )

        assertEquals(Black, result.composed.argb[1 * width + 1])
        assertEquals(Red, result.composed.argb[1 * width + 2])
        assertEquals(Green, result.composed.argb[2 * width + 2])
        assertEquals(Black, result.composed.argb[2 * width + 3])
    }

    @Test
    fun movedDuplicateKeepsTheSourceAndDrawsTheSelectedClone() {
        val background = PreviewFrame(4, 1, IntArray(4) { Black })
        val reference = PreviewFrame(4, 1, intArrayOf(Red, Black, Black, Black))
        val source = guide(
            globalIndex = 2,
            x = 0,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 1,
            height = 1,
        )
        val duplicate = guide(
            globalIndex = 5,
            x = 2,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 1,
            height = 1,
        ).copy(duplicateSourceGlobalIndex = source.globalIndex)

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = reference,
            widgets = listOf(source, duplicate),
        )

        assertEquals(Red, result.composed.argb[0])
        assertEquals(Red, result.composed.argb[2])
    }

    @Test
    fun pairColorChangeIsVisibleWithoutMovingTheWidget() {
        val background = PreviewFrame(2, 1, intArrayOf(Black, Black))
        val reference = PreviewFrame(2, 1, intArrayOf(Red, Black))
        val widget = guide(
            globalIndex = 2,
            x = 0,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 1,
            height = 1,
        ).copy(
            colorArgb = Green,
            originalColorArgb = Red,
        )

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = reference,
            widgets = listOf(widget),
        )

        assertEquals(Green, result.composed.argb[0])
        assertEquals(Green, result.widgetOverlay.argb[0])
    }

    /**
     * `preview.bin` still shows a removed widget — nothing rewrites the vendor's
     * rendered raster — so the composer has to drop its pixels itself.
     */
    @Test
    fun removedWidgetPixelsLeaveTheComposedPreview() {
        val width = 4
        val height = 1
        val background = PreviewFrame(width, height, IntArray(width * height) { Black })
        val reference = PreviewFrame(
            width,
            height,
            background.argb.copyOf().apply {
                this[1] = Red
                this[2] = Green
            },
        )
        val surviving = guide(
            globalIndex = 5,
            x = 2,
            y = 0,
            originalX = 2,
            originalY = 0,
            width = 1,
            height = 1,
        )

        val before = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = reference,
            widgets = listOf(surviving),
        )
        assertEquals(Red, before.composed.argb[1])
        assertEquals(Green, before.composed.argb[2])

        val after = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = reference,
            widgets = listOf(surviving),
            removedWidgets = listOf(
                RemovedWidget(
                    id = 1,
                    label = "Widget #4",
                    widgetType = 5,
                    sequenceId = 4,
                    x = 0,
                    y = 0,
                    width = 2,
                    height = 1,
                    recordsByStyle = mapOf("style0.bin" to byteArrayOf()),
                ),
            ),
        )

        assertEquals("removed pixel must fall back to the background", Black, after.composed.argb[1])
        assertEquals(Transparent, after.widgetOverlay.argb[1])
        // The smaller surviving widget inside the removed rectangle is untouched.
        assertEquals(Green, after.composed.argb[2])
    }

    /**
     * A Sprite resize rewrites every frame, so the widget's extent follows the new
     * raster while `preview.bin` still holds the vendor's render at the old one. The
     * shrunk rectangle no longer covers those pixels, so clearing with it leaves the
     * outer ring of the old sprite on the canvas — face `00022`, which opens with a
     * 37×28 icon and resizes down.
     */
    @Test
    fun shrinkingAWidgetClearsThePixelsItsOldRectangleCovered() {
        val width = 3
        val height = 1
        val background = PreviewFrame(width, height, IntArray(width * height) { Black })
        val reference = PreviewFrame(width, height, intArrayOf(Red, Red, Black))
        val shrunk = guide(
            globalIndex = 2,
            x = 0,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 1,
            height = 1,
        ).copy(originalWidth = 2, originalHeight = 1)

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = reference,
            widgets = listOf(shrunk),
            imageLayers = listOf(
                WidgetImageLayer(
                    globalIndex = shrunk.globalIndex,
                    frame = PreviewFrame(1, 1, intArrayOf(Blue)),
                ),
            ),
        )

        assertEquals(Blue, result.composed.argb[0])
        assertEquals("the old sprite's second column must not survive", Black, result.composed.argb[1])
        assertEquals(Transparent, result.widgetOverlay.argb[1])
    }

    /** The same, without an embedded layer to redraw from: the extent still changed. */
    @Test
    fun shrinkingAWidgetWithNoEmbeddedLayerClearsItsOldRectangle() {
        val width = 3
        val height = 1
        val background = PreviewFrame(width, height, IntArray(width * height) { Black })
        val reference = PreviewFrame(width, height, intArrayOf(Red, Red, Black))
        val shrunk = guide(
            globalIndex = 2,
            x = 0,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 1,
            height = 1,
        ).copy(originalWidth = 2, originalHeight = 1)

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = reference,
            widgets = listOf(shrunk),
        )

        assertEquals(Red, result.composed.argb[0])
        assertEquals(Black, result.composed.argb[1])
    }

    /**
     * Clearing and drawing have to be separate passes. Interleaved, a widget dragged
     * onto the rectangle another widget is vacating is painted first and then erased
     * by that widget's own clear, so moving several widgets around each other makes
     * them vanish one at a time.
     */
    @Test
    fun aWidgetMovedOntoAnotherWidgetsVacatedRectangleSurvives() {
        val width = 5
        val height = 1
        val background = PreviewFrame(width, height, IntArray(width * height) { Black })
        val reference = PreviewFrame(width, height, intArrayOf(Red, Black, Green, Black, Black))
        val first = guide(
            globalIndex = 2,
            x = 2,
            y = 0,
            originalX = 0,
            originalY = 0,
            width = 1,
            height = 1,
        )
        val second = guide(
            globalIndex = 5,
            x = 4,
            y = 0,
            originalX = 2,
            originalY = 0,
            width = 1,
            height = 1,
        )

        val result = EditPreviewComposer.compose(
            currentBackground = background,
            originalBackground = background,
            reference = reference,
            widgets = listOf(first, second),
        )

        assertEquals(Black, result.composed.argb[0])
        assertEquals("the widget dragged into the vacated slot must stay", Red, result.composed.argb[2])
        assertEquals(Green, result.composed.argb[4])
    }

    private fun guide(
        globalIndex: Int,
        x: Int,
        y: Int,
        originalX: Int,
        originalY: Int,
        width: Int,
        height: Int,
    ) = WidgetGuide(
        ordinal = globalIndex,
        globalIndex = globalIndex,
        type = 3,
        sequenceId = globalIndex,
        x = x,
        y = y,
        originalX = originalX,
        originalY = originalY,
        width = width,
        height = height,
        recordSize = 0,
        isFinal = false,
        canEditPosition = true,
        colorArgb = null,
        supportMessage = "",
    )

    private companion object {
        const val Black = 0xFF000000.toInt()
        const val Red = 0xFFFF0000.toInt()
        const val Green = 0xFF00FF00.toInt()
        const val Blue = 0xFF0000FF.toInt()
        const val Transparent = 0x00000000
    }
}
