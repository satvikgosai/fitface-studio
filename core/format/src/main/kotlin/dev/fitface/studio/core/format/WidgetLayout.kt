package dev.fitface.studio.core.format

/** Why a widget's rectangle sits where it does. */
enum class PlacementBasis {
    /** The stored coordinates are panel coordinates. Negative means partly off-panel. */
    ABSOLUTE,

    /** Offsets from another widget's rectangle. */
    ALIGNED_TO_WIDGET,

    /**
     * Offsets from the panel, because the alignment target names no earlier record.
     *
     * A real and common state rather than a fault: the catalogue's own faces store
     * target values — 5, 10, 20, 30, 40 — that match no record in the style, and the
     * watch falls back to the whole face when a target does not resolve.
     */
    ALIGNED_TO_PANEL,

    /** An alignment code this layer has no geometry for. Drawn top-left, never moved. */
    UNRESOLVED,
}

/**
 * Where a widget's stored coordinates are measured from.
 *
 * The whole of the geometry fix lives in this one idea: a widget's display position is
 * `origin + stored`, and its stored position is `display − origin`. That holds for every
 * type and every alignment code, so the canvas and the editor's write-back are inverses
 * by construction.
 *
 * What it replaces was a guess from the sign of the coordinate — negative meant "anchored
 * to the far edge". That happens to give the right answer for the one alignment code that
 * measures from the right edge, which is why it survived, and the wrong answer for a
 * genuinely negative coordinate: 62 records across six catalogue faces were drawn at the
 * opposite side of the face from where the watch draws them, and dragging one wrote back
 * a coordinate measured from an edge it was never measured from.
 */
data class ResolvedPlacement(
    val originX: Int,
    val originY: Int,
    val basis: PlacementBasis,
    /** The widget this one is positioned against, when the reference resolved. */
    val targetGlobalIndex: Int? = null,
) {
    val isMovable: Boolean get() = basis != PlacementBasis.UNRESOLVED
}

/** The rectangle a widget actually covers, in panel coordinates. */
data class DrawnExtent(
    val width: Int,
    val height: Int,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
)

object WidgetLayout {

    /** Positioned from the target's top-left corner. */
    private const val ALIGN_DEFAULT = 0
    private const val ALIGN_TOP_LEFT = 1

    /** Positioned from the middle of the target's top edge. */
    private const val ALIGN_TOP_MID = 2

    /** Positioned from the target's top-right corner, so a negative x insets it. */
    private const val ALIGN_TOP_RIGHT = 3

    /**
     * Resolve every record's origin, keyed by [WidgetRecord.ordinal].
     *
     * Records are walked in order and a target only resolves against a record *already*
     * walked. That is the watch's own behaviour — it looks the target up among the
     * objects it has built so far — and it means a forward reference and a
     * self-reference both land on the panel, which is what the 377 self-referencing
     * records in the catalogue do. It also makes a reference cycle unrepresentable
     * rather than something to detect.
     */
    fun resolve(
        records: List<WidgetRecord>,
        extents: Map<Int, DrawnExtent>,
        panel: PanelSize,
    ): Map<Int, ResolvedPlacement> {
        val placements = LinkedHashMap<Int, ResolvedPlacement>(records.size)
        val rectangles = HashMap<Int, IntArray>(records.size)
        records.forEach { record ->
            val extent = extents[record.ordinal] ?: DrawnExtent(0, 0)
            val placement = placementOf(record, extent, panel, rectangles)
            placements[record.ordinal] = placement
            rectangles[record.globalIndex] = intArrayOf(
                placement.originX + record.x + extent.offsetX,
                placement.originY + record.y + extent.offsetY,
                extent.width,
                extent.height,
            )
        }
        return placements
    }

    private fun placementOf(
        record: WidgetRecord,
        extent: DrawnExtent,
        panel: PanelSize,
        rectangles: Map<Int, IntArray>,
    ): ResolvedPlacement {
        val alignment = record.liveAlignment
            ?: return ResolvedPlacement(0, 0, PlacementBasis.ABSOLUTE)
        val target = rectangles[alignment.targetGlobalIndex]
        val base = target ?: intArrayOf(0, 0, panel.width, panel.height)
        val basis = if (target != null) {
            PlacementBasis.ALIGNED_TO_WIDGET
        } else {
            PlacementBasis.ALIGNED_TO_PANEL
        }
        return when (alignment.code) {
            ALIGN_DEFAULT, ALIGN_TOP_LEFT -> ResolvedPlacement(
                originX = base[0],
                originY = base[1],
                basis = basis,
                targetGlobalIndex = target?.let { alignment.targetGlobalIndex },
            )

            ALIGN_TOP_MID -> ResolvedPlacement(
                originX = base[0] + (base[2] - extent.width) / 2,
                originY = base[1],
                basis = basis,
                targetGlobalIndex = target?.let { alignment.targetGlobalIndex },
            )

            ALIGN_TOP_RIGHT -> ResolvedPlacement(
                originX = base[0] + base[2] - extent.width,
                originY = base[1],
                basis = basis,
                targetGlobalIndex = target?.let { alignment.targetGlobalIndex },
            )

            // Only 0..3 occur in the catalogue, so anything else is a code this layer has
            // no measured geometry for. Draw it from the target's corner so it is at
            // least visible, and refuse to move it rather than write back an offset
            // computed from the wrong edge.
            else -> ResolvedPlacement(
                originX = base[0],
                originY = base[1],
                basis = PlacementBasis.UNRESOLVED,
                targetGlobalIndex = target?.let { alignment.targetGlobalIndex },
            )
        }
    }
}
