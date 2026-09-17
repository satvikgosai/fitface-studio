package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetCategory

/**
 * What each widget record type stores, and where.
 *
 * **One table, not five.** Parsing, pointer relocation, capability checks, labels and
 * validation all read this object. A type added to one `when` and missed in another is
 * how a container that parses cleanly turns into a widget that silently stops drawing,
 * and that has happened here more than once.
 *
 * A record is a **24-byte common prefix followed by a type-specific layout** — not the
 * fixed 36-byte head plus anonymous 32-bit words this layer used to assume. The old
 * model was accidentally survivable because most records are 4-byte aligned and most of
 * the app only needed the pointers, but it named several fields wrongly: what it called
 * `width`/`height` at `+0x1C`/`+0x1E` is the alignment code and target index on a Static
 * or a Hand, a signed extent on a Pair, Comp, Arc or LineBar, and the second endpoint on
 * a Badge. A later analysis pass over the whole catalogue settled every type's layout;
 * this table is that result, and [WidgetLayout] is what turns it into geometry.
 *
 * Sizes are the ones the 4,034 records in the 99-face catalogue actually use: every one
 * is exactly its type's size, with no record carrying padding beyond its layout.
 */
object WidgetSchema {

    /** Bytes every record shares before its type-specific layout begins. */
    const val COMMON_PREFIX_SIZE = 0x18

    /**
     * The smallest record this layer will read.
     *
     * The watch needs only the type and the low half of `+0x0C` to walk the stream, so it
     * would accept 14 bytes. The app needs the *high* half too — that is the global index
     * every alignment reference is resolved through — so 16 is the floor here.
     */
    const val MINIMUM_RECORD_SIZE = 0x10

    /** Widget types this format defines. Anything outside `1..17` is not a widget. */
    val SUPPORTED_TYPES = 1..17

    /** Vendor-produced types whose resource closure the experimental importer implements. */
    val IMPORTABLE_STOCK_TYPES = setOf(1, 2, 3, 5, 6, 7, 13, 16, 17)

    /**
     * A field holding the global index of the widget this one is positioned against.
     *
     * Four types carry one, at two different pairs of offsets. The reference is live
     * whenever the code is not [ALIGNMENT_DISABLED]; when it is live, the record's
     * `+0x18`/`+0x1A` are **offsets from the target's rectangle**, not panel coordinates.
     */
    data class AlignmentFields(val codeOffset: Int, val targetOffset: Int)

    /** How a record addresses rasters, if it addresses any. */
    sealed interface PointerLayout {
        /** Pair, Comp, Badge and the vector Arc draw themselves; they name no raster. */
        data object None : PointerLayout

        data class Single(val offset: Int) : PointerLayout

        /** A `u8` count followed by exactly that many consecutive pointers. */
        data class Table(val countOffset: Int, val firstOffset: Int) : PointerLayout
    }

    /**
     * How a widget of this type changes size — the shape of the edit, not whether any
     * particular record may take it.
     *
     * This is in the schema table for the reason the pointer layout is: resize used to be
     * one hardcoded path for one type, checked in `canResize` and then checked again in
     * `StructuralEditor`, and "a type added to one `when` and missed in another is how a
     * valid container becomes a blank widget". Five types resize by five slightly
     * different rewrites, and the differences are exactly which *other* field has to move
     * with the artwork.
     *
     * A null [Spec.resize] is a type this app will not resize, and each null has a reason
     * beside it — a missing sample, or a size that does not live in the record at all.
     */
    sealed interface ResizeModel {
        /**
         * The record carries no size: its extent is its raster's, so resampling the
         * artwork in place is the whole edit. Static and Sprite.
         */
        data object Raster : ResizeModel

        /**
         * A raster plus a rotation pivot *inside* it, at [pivotOffset] as `x,y` halfwords.
         *
         * The pivot scales with the artwork and `x`/`y` absorbs the difference, so the
         * point the watch rotates about does not move. `pivot_x` is the artwork's
         * horizontal middle in 448 of the catalogue's 469 Hand records, so this is what
         * the producer does too.
         */
        data class RasterWithPivot(val pivotOffset: Int) : ResizeModel

        /**
         * A raster plus the stored box at `+0x1C`/`+0x1E`, which the watch draws the
         * artwork *centred in* at native size rather than scaling it into.
         *
         * Face `00108` settles that from the vendor's own render: styles 0–3 store a
         * 256×256 box around a 204×204 raster and the ring lands at 204 px, and styles 4–5
         * reach the identical picture with a 256×256 raster whose ring is inset 26 px. So
         * the box has to grow by the same delta about the same centre, or the container
         * states two sizes for one widget.
         *
         * [thicknessOffset] is a `u8` that equals the stored height in all 16 LineBar
         * records and has to keep doing so; an image Arc's thickness is independent.
         */
        data class RasterWithBox(val thicknessOffset: Int? = null) : ResizeModel

        /**
         * No raster at all: `+0x1C`/`+0x1E` is the extent the watch draws into, so the
         * resize is two halfwords and the container does not change size by a single byte.
         */
        data object Box : ResizeModel

        /**
         * No raster: `+0x1C`/`+0x1E` is the *second endpoint*, so resizing means scaling
         * the endpoint vector. An axis whose span is zero stays zero — 32 of the 84 Rules
         * are exactly horizontal — and the stored endpoint is the far one in the other 52,
         * which is why the sign of each delta is preserved rather than recomputed.
         */
        data object Endpoint : ResizeModel
    }

    data class Spec(
        val type: Int,
        val name: String,
        val category: WidgetCategory,
        /** Exact record size, or null for a type whose size follows a pointer table. */
        val exactSize: Int?,
        val minimumSize: Int,
        val alignment: AlignmentFields?,
        /** Whether `+0x1C`/`+0x1E` is this type's own signed width/height pair. */
        val hasStoredExtent: Boolean,
        val pointers: PointerLayout,
        /**
         * Live-data sources this type can actually follow, or null where the accepted set
         * is not established. A record naming anything else does not gain a new data
         * source; at best it never updates.
         */
        val sources: Set<Int>?,
        /**
         * Whether the common `+0x04` word is the reading this widget actually follows.
         *
         * False for three types that all store something there anyway, which is why this
         * cannot be derived from the value. A Static registers its source and is then
         * never updated; a Composite ignores the common word entirely and gives each of
         * its four parts its own; and an Animation runs off its own timer. Labelling
         * those with a reading would put a live value's name on a widget that never
         * shows one.
         */
        val followsCommonSource: Boolean = true,
        /**
         * A type the watch accepts structurally and then does nothing with: no drawn
         * object, no live update. Preserved verbatim, never presented as editable.
         */
        val inert: Boolean = false,
        /** How this type changes size, or null for one this app will not resize. */
        val resize: ResizeModel? = null,
    ) {
        /** The exact size a record of this type must have, given its own bytes. */
        fun expectedSize(record: ByteArray, base: Int): Int? = when (val layout = pointers) {
            is PointerLayout.Table -> {
                val count = record[base + layout.countOffset].toInt() and 0xFF
                layout.firstOffset + count * 4
            }
            else -> exactSize
        }
    }

    /** `+0x1C` (Static, Hand) or `+0x20` (Pair, Comp): relative alignment is off. */
    const val ALIGNMENT_DISABLED = 0xFFFF

    private val STATIC = Spec(
        type = 1,
        name = "Static",
        category = WidgetCategory.IMAGE,
        exactSize = 40,
        minimumSize = 40,
        alignment = AlignmentFields(codeOffset = 0x1C, targetOffset = 0x1E),
        hasStoredExtent = false,
        pointers = PointerLayout.Single(0x20),
        // Registers the low half of the common source word, though nothing updates a
        // Static afterwards. 678 of the 681 in the catalogue store zero.
        sources = null,
        followsCommonSource = false,
        // 40 bytes of position, alignment, one pointer and an interaction flag: not one
        // of them holds a size, so resampling the artwork is the entire edit. 388 of the
        // 681 draw the panel background and are refused by the pool check, not by this.
        resize = ResizeModel.Raster,
    )

    private val HAND = Spec(
        type = 2,
        name = "Hand",
        category = WidgetCategory.HAND,
        exactSize = 44,
        minimumSize = 44,
        alignment = AlignmentFields(codeOffset = 0x1C, targetOffset = 0x1E),
        hasStoredExtent = false,
        pointers = PointerLayout.Single(0x28),
        // A hand is not only a clock hand: the same primitive sweeps a gauge needle over
        // steps, battery, heart rate or calories.
        sources = setOf(1, 9, 13, 17, 21, 29, 37, 41, 48, 70, 71),
        resize = ResizeModel.RasterWithPivot(pivotOffset = 0x20),
    )

    private val SPRITE = Spec(
        type = 3,
        name = "Sprite",
        category = WidgetCategory.SPRITE,
        exactSize = null,
        minimumSize = 0x24 + 4,
        alignment = null,
        hasStoredExtent = false,
        pointers = PointerLayout.Table(countOffset = 0x20, firstOffset = 0x24),
        sources = setOf(
            1, 2, 3, 5, 9, 10, 11, 13, 14, 15, 17, 19, 20, 21, 22, 23,
            25, 26, 27, 28, 29, 37, 41, 48, 69, 70, 71,
            106, 107, 109, 110, 115, 117, 118, 119, 125,
        ),
        resize = ResizeModel.Raster,
    )

    private val ANIMATION = Spec(
        type = 4,
        name = "Animation",
        category = WidgetCategory.ANIMATION,
        exactSize = null,
        minimumSize = 0x28 + 4,
        alignment = null,
        hasStoredExtent = false,
        pointers = PointerLayout.Table(countOffset = 0x24, firstOffset = 0x28),
        sources = null,
        followsCommonSource = false,
        // Its frame table has the same shape as a Sprite's, so [ResizeModel.Raster] would
        // fit — but no catalogue face carries one, [FaceRecordParser.referencedImages] has
        // no case for it, so it has no drawn extent to resize *from*, and a resize offered
        // on a type with no sample is a control nothing here can test. Fail closed.
        resize = null,
    )

    private val PAIR = Spec(
        type = 5,
        name = "Value",
        category = WidgetCategory.VALUE,
        exactSize = 56,
        minimumSize = 56,
        alignment = AlignmentFields(codeOffset = 0x20, targetOffset = 0x22),
        hasStoredExtent = true,
        pointers = PointerLayout.None,
        sources = null,
    )

    private val VECTOR_ARC = Spec(
        type = 6,
        name = "Vector arc",
        category = WidgetCategory.VECTOR_ARC,
        exactSize = 76,
        minimumSize = 76,
        alignment = null,
        hasStoredExtent = true,
        // Draws from stored colour, thickness and angles. This is the type that used to
        // show up as "Other" on nine faces: it is a gauge, not an unknown record.
        pointers = PointerLayout.None,
        sources = setOf(29, 37, 41, 48, 70, 71, 104, 115),
        // Names no raster, so the box is the whole size and nothing is resampled: the
        // cheapest resize in the format, and the only one that adds no bytes at all.
        resize = ResizeModel.Box,
    )

    private val BADGE = Spec(
        type = 7,
        name = "Rule",
        category = WidgetCategory.RULE,
        exactSize = 52,
        minimumSize = 52,
        alignment = null,
        // `+0x1C`/`+0x1E` is the second endpoint, which is why the guide derives its
        // rectangle from the span and not from a stored extent.
        hasStoredExtent = false,
        pointers = PointerLayout.None,
        sources = setOf(29, 37, 41, 48, 70, 71, 115),
        resize = ResizeModel.Endpoint,
    )

    private val SOURCE_GROUP = Spec(
        type = 9,
        name = "Complication group",
        category = WidgetCategory.GROUP,
        exactSize = null,
        // Nothing past `+0x1C` is consumed, and no catalogue face carries one, so the
        // producer's own padded size is unknown. 28 covers every field that is read.
        minimumSize = 0x1C,
        alignment = null,
        hasStoredExtent = false,
        pointers = PointerLayout.None,
        sources = setOf(75),
    )

    private val COMP = Spec(
        type = 13,
        name = "Composite",
        category = WidgetCategory.COMPOSITE,
        exactSize = 100,
        minimumSize = 100,
        alignment = AlignmentFields(codeOffset = 0x20, targetOffset = 0x22),
        hasStoredExtent = true,
        pointers = PointerLayout.None,
        // Four 12-byte parts from `+0x24`, each with its own source; the common source
        // word is not consumed.
        sources = null,
        followsCommonSource = false,
    )

    private val IMAGE_ARC = Spec(
        type = 16,
        name = "Image arc",
        category = WidgetCategory.ARC,
        exactSize = 60,
        minimumSize = 60,
        alignment = null,
        hasStoredExtent = true,
        pointers = PointerLayout.Single(0x34),
        sources = setOf(29, 37, 41, 48, 70, 71, 104, 115),
        // Thickness at +0x24 is independent of the box: face 00108 ships the same
        // 256x256 box at thicknesses 70, 60 and 50 across its styles.
        resize = ResizeModel.RasterWithBox(),
    )

    private val LINE_BAR = Spec(
        type = 17,
        name = "Bar",
        category = WidgetCategory.BAR,
        exactSize = 50,
        minimumSize = 50,
        alignment = null,
        hasStoredExtent = true,
        pointers = PointerLayout.Single(0x2C),
        sources = setOf(29, 37, 41, 48, 70, 71, 115),
        resize = ResizeModel.RasterWithBox(thicknessOffset = 0x30),
    )

    private fun reserved(type: Int) = Spec(
        type = type,
        name = "Reserved $type",
        category = WidgetCategory.RESERVED,
        exactSize = null,
        minimumSize = MINIMUM_RECORD_SIZE,
        alignment = null,
        hasStoredExtent = false,
        pointers = PointerLayout.None,
        sources = null,
        followsCommonSource = false,
        inert = true,
    )

    private val SPECS: Map<Int, Spec> = listOf(
        STATIC, HAND, SPRITE, ANIMATION, PAIR, VECTOR_ARC, BADGE,
        reserved(8), SOURCE_GROUP, reserved(10), reserved(11), reserved(12),
        COMP, reserved(14), reserved(15), IMAGE_ARC, LINE_BAR,
    ).associateBy(Spec::type)

    /** The layout for [type], or null when the type is not one this format defines. */
    fun specOrNull(type: Int): Spec? = SPECS[type]

    fun spec(type: Int): Spec = SPECS[type]
        ?: throw Fit3FormatException("widget type $type is not one of 1..17")

    /** Every type, in order, for censuses and diagnostics. */
    val all: List<Spec> get() = SUPPORTED_TYPES.map(::spec)

    /** Types that address at least one raster. */
    val pointerBearingTypes: Set<Int> =
        SPECS.values.filter { it.pointers !is PointerLayout.None }.map(Spec::type).toSet()
}
