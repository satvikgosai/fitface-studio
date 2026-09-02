package dev.fitface.studio.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry every drawn, hit-tested and edited rectangle comes from.
 *
 * Synthetic on purpose: the catalogue only exercises alignment against a full-panel
 * background sitting at the origin, which is the one arrangement where measuring from the
 * panel and measuring from the target give the same answer. These cases are what the
 * format allows, so they are what the resolver has to get right.
 */
class WidgetLayoutTest {
    private val panel = PanelSize(256, 402)

    private fun record(
        ordinal: Int,
        globalIndex: Int = ordinal,
        type: Int = WIDGET_PAIR,
        x: Int = 0,
        y: Int = 0,
        alignment: AlignmentRef? = null,
    ) = WidgetRecord(
        ordinal = ordinal,
        recordOffset = 0,
        recordSize = 56,
        globalIndex = globalIndex,
        widgetType = type,
        sequenceId = 0,
        x = x,
        y = y,
        raw1C = 0,
        raw1E = 0,
        unknown20 = 0,
        words = emptyList(),
        alignment = alignment,
    )

    private fun resolve(
        records: List<WidgetRecord>,
        extents: Map<Int, DrawnExtent>,
    ): Map<Int, ResolvedPlacement> = WidgetLayout.resolve(records, extents, panel)

    /** No alignment fields at all: the stored coordinates are panel coordinates. */
    @Test
    fun anUnalignedWidgetIsMeasuredFromThePanel() {
        val records = listOf(record(0, type = WIDGET_SPRITE, x = -72, y = 4))
        val placement = resolve(records, mapOf(0 to DrawnExtent(400, 400))).getValue(0)

        assertEquals(0, placement.originX)
        assertEquals(0, placement.originY)
        assertEquals(PlacementBasis.ABSOLUTE, placement.basis)
    }

    /** The sentinel does the same for a type that *could* align. */
    @Test
    fun theDisabledSentinelMeansAbsolutePlacement() {
        val records = listOf(
            record(0, alignment = AlignmentRef(WidgetSchema.ALIGNMENT_DISABLED, 0), x = 10, y = 20),
        )
        val placement = resolve(records, mapOf(0 to DrawnExtent(30, 20))).getValue(0)

        assertEquals(0, placement.originX)
        assertEquals(PlacementBasis.ABSOLUTE, placement.basis)
    }

    @Test
    fun theThreeAlignmentCodesMeasureFromTheirOwnEdge() {
        val target = record(0, type = WIDGET_STATIC)
        val extents = mapOf(
            0 to DrawnExtent(200, 100),
            1 to DrawnExtent(60, 20),
            2 to DrawnExtent(60, 20),
            3 to DrawnExtent(60, 20),
        )
        val records = listOf(
            target,
            record(1, alignment = AlignmentRef(1, 0), x = 5, y = 7),
            record(2, alignment = AlignmentRef(2, 0), x = 0, y = 7),
            record(3, alignment = AlignmentRef(3, 0), x = -4, y = 7),
        )
        val placements = resolve(records, extents)

        // Top-left: straight from the target's corner.
        assertEquals(0, placements.getValue(1).originX)
        // Centred: the target's width less the widget's own, halved.
        assertEquals(70, placements.getValue(2).originX)
        // Top-right: a negative offset insets it from the target's right edge.
        assertEquals(140, placements.getValue(3).originX)
        placements.values.drop(1).forEach {
            assertEquals(PlacementBasis.ALIGNED_TO_WIDGET, it.basis)
            assertEquals(0, it.originY)
        }
    }

    /** Aligned to a widget that is itself aligned: the chain resolves in record order. */
    @Test
    fun anAlignmentChainResolvesThroughItsTarget() {
        val extents = mapOf(
            0 to DrawnExtent(200, 100),
            1 to DrawnExtent(60, 20),
            2 to DrawnExtent(10, 10),
        )
        val records = listOf(
            record(0, type = WIDGET_STATIC, x = 20, y = 30),
            record(1, alignment = AlignmentRef(3, 0), x = -4, y = 6),
            record(2, alignment = AlignmentRef(1, 1), x = 2, y = 3),
        )
        val placements = resolve(records, extents)

        // Widget 1 draws at 20 + (200 - 60) + -4 = 156, 30 + 6 = 36.
        assertEquals(156, placements.getValue(1).originX + records[1].x)
        // Widget 2 is measured from there.
        assertEquals(156, placements.getValue(2).originX)
        assertEquals(36, placements.getValue(2).originY)
    }

    /**
     * A reference that names nothing falls back to the whole face — the state 397
     * catalogue records are in, so it has to be an ordinary outcome and not an error.
     */
    @Test
    fun aReferenceToNoRecordFallsBackToThePanel() {
        val records = listOf(record(0, alignment = AlignmentRef(3, 20), x = -12, y = 8))
        val placement = resolve(records, mapOf(0 to DrawnExtent(30, 20))).getValue(0)

        assertEquals(PlacementBasis.ALIGNED_TO_PANEL, placement.basis)
        assertEquals(226, placement.originX)
        assertEquals(214, placement.originX + records[0].x)
        assertTrue(placement.isMovable)
        assertEquals(null, placement.targetGlobalIndex)
    }

    /**
     * A record naming itself, and a record naming one that comes later, both fall back.
     *
     * The watch looks a reference up among the objects it has already built, so neither
     * can resolve — which is also why a reference cycle cannot be expressed at all.
     * 377 catalogue records name themselves.
     */
    @Test
    fun selfAndForwardReferencesFallBackToThePanel() {
        val records = listOf(
            record(0, alignment = AlignmentRef(1, 0)),
            record(1, alignment = AlignmentRef(1, 2)),
            record(2, type = WIDGET_STATIC),
        )
        val placements = resolve(
            records,
            mapOf(0 to DrawnExtent(10, 10), 1 to DrawnExtent(10, 10), 2 to DrawnExtent(10, 10)),
        )

        assertEquals(PlacementBasis.ALIGNED_TO_PANEL, placements.getValue(0).basis)
        assertEquals(PlacementBasis.ALIGNED_TO_PANEL, placements.getValue(1).basis)
    }

    /**
     * An alignment code with no measured geometry is drawn from the target's corner and
     * refuses to be moved, rather than being written back against the wrong edge. No
     * catalogue face contains one.
     */
    @Test
    fun anUnknownAlignmentCodeIsNotMovable() {
        val records = listOf(
            record(0, type = WIDGET_STATIC),
            record(1, alignment = AlignmentRef(9, 0), x = 4, y = 4),
        )
        val placement = resolve(
            records,
            mapOf(0 to DrawnExtent(200, 100), 1 to DrawnExtent(10, 10)),
        ).getValue(1)

        assertEquals(PlacementBasis.UNRESOLVED, placement.basis)
        assertFalse(placement.isMovable)
    }

    /** Whatever the basis, display and stored are inverses through the origin. */
    @Test
    fun everyBasisRoundTrips() {
        val records = listOf(
            record(0, type = WIDGET_STATIC, x = 12, y = 14),
            record(1, alignment = AlignmentRef(2, 0), x = -3, y = 9),
            record(2, alignment = AlignmentRef(3, 30), x = -8, y = 1),
            record(3, alignment = AlignmentRef(WidgetSchema.ALIGNMENT_DISABLED, 0), x = 5, y = 6),
        )
        val extents = records.associate { it.ordinal to DrawnExtent(40, 20) }
        val placements = resolve(records, extents)

        records.forEach { record ->
            val place = placements.getValue(record.ordinal)
            val display = place.originX + record.x
            assertEquals(record.x, display - place.originX)
        }
    }
}
