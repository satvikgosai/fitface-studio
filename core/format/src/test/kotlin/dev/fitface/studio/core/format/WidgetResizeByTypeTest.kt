package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WIDGET_EXTENT_CEILING
import dev.fitface.studio.core.model.WidgetResizeKind
import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Resize, once per type, against the field that type would otherwise leave describing the
 * old artwork.
 *
 * Every one of these has the same shape and the same reason: the resize itself is easy and
 * the *other* field is where the bug lives. A stale field of this kind fails no validation
 * — the container parses, the CRCs match, the install is accepted — and the widget simply
 * draws in the wrong place, which is the class of fault this codebase has had to find
 * after the fact more than once.
 *
 * `WidgetPlacementTest.resizableSpritesAreAcceptedByTheStructuralEditor` is the companion
 * sweep: it commits a resize for *every* record the gate offers one on, over the whole
 * corpus. That proves the gate and the editor agree. These tests prove the edit is right.
 */
class WidgetResizeByTypeTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private lateinit var containers: List<Path>

    @Before
    fun locateContainers() {
        val directory = root.resolve("SM_R390")
        assumeTrue("no corpus at $directory", Files.isDirectory(directory))
        containers = Files.walk(directory, 3).asSequence()
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".bin") }
            .sortedBy { it.fileName.toString() }
            .toList()
        assumeTrue("corpus holds no containers", containers.isNotEmpty())
    }

    /**
     * A Static carries no size at all, so resampling its raster is the whole edit — and
     * because its data source is `0` in 678 of the catalogue's 681 records, it is also the
     * type that proves the selector works. Face `00008` `style0` carries **three** drawable
     * Statics, all at source 0 and all on different rasters; the old
     * `(type, sequenceId).singleOrNull()` selector could not have named any of them.
     */
    @Test
    fun aStaticResizesItsOwnRasterAndLeavesItsTwinAlone() {
        val original = face("00008")
        val style = original.entryByBasename("style0.bin")
        val statics = FaceRecordParser.widgetGuides(style)
            .filter { it.type == WIDGET_STATIC && it.canResize }
        // Two Statics at the same data source that draw *different* rasters. Two pointing
        // at one record would resize together and rightly so — that is the pool rule — so
        // the pair that proves the selector works is the pair that shares only a source.
        val target = statics.firstOrNull { first ->
            statics.any { it.globalIndex != first.globalIndex &&
                it.sequenceId == first.sequenceId &&
                rasterIndexOf(style, it) != rasterIndexOf(style, first)
            }
        }
        assumeTrue("face 00008 style0 has no two Statics sharing a source", target != null)
        requireNotNull(target)
        val twin = statics.first {
            it.globalIndex != target.globalIndex &&
                it.sequenceId == target.sequenceId &&
                rasterIndexOf(style, it) != rasterIndexOf(style, target)
        }
        assertEquals(
            "the Statics this test rests on must share one data source",
            target.sequenceId,
            twin.sequenceId,
        )
        val before = rasterOf(style, twin)

        val edited = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = target,
            width = target.width / 2,
            height = target.height / 2,
            pristine = original,
        ).container
        val editedStyle = edited.entryByBasename("style0.bin")
        val guides = FaceRecordParser.widgetGuides(editedStyle)

        val resized = guides.single { it.globalIndex == target.globalIndex }
        assertEquals(target.width / 2, resized.width)
        assertEquals(target.height / 2, resized.height)
        assertEquals(
            "the other Static at the same data source must keep its own artwork",
            before,
            rasterOf(editedStyle, guides.single { it.globalIndex == twin.globalIndex }),
        )
        assertEquals(
            "a resize may never change the image-record count",
            FaceRecordParser.scanImages(style).size,
            FaceRecordParser.scanImages(editedStyle).size,
        )
        assertEquals(
            "a Static resize moves nothing: its alignment code is 0 or 1 in every " +
                "catalogue record, so its origin does not depend on its own width",
            target.drawLeft to target.drawTop,
            resized.drawLeft to resized.drawTop,
        )
    }

    /**
     * A Hand's `+0x20` is a pivot *inside its raster*, so the watch rotates it about
     * `x + pivot`. Scale the artwork and leave the pivot alone and the hand rotates about
     * the wrong point; scale the pivot and leave `x` alone and the rotation centre slides
     * off the dial by the pivot's own offset — which is exactly how the AOD renderer got
     * it wrong the first time it used the field, by 8×76 px on face `00046`.
     *
     * The invariant is that the centre does not move. It is checked in panel coordinates,
     * not in stored ones, because those are what the watch draws with.
     */
    @Test
    fun aResizedHandKeepsItsRotationCentre() {
        val original = face("00001")
        val style = original.entryByBasename("style0.bin")
        val hand = FaceRecordParser.widgetGuides(style)
            .firstOrNull { it.type == WIDGET_HAND && it.canResize }
        assumeTrue("face 00001 style0 has no resizable Hand", hand != null)
        requireNotNull(hand)
        val centreBefore = rotationCentre(style, hand.globalIndex)

        val edited = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = hand,
            width = (hand.width * 3 / 4).coerceAtLeast(1),
            height = (hand.height * 3 / 4).coerceAtLeast(1),
            pristine = original,
        ).container
        val editedStyle = edited.entryByBasename("style0.bin")
        val resized = FaceRecordParser.widgetGuides(editedStyle)
            .single { it.globalIndex == hand.globalIndex }

        assertEquals((hand.width * 3 / 4).coerceAtLeast(1), resized.width)
        assertNotEquals(
            "the artwork has to have actually changed size for this to prove anything",
            hand.width,
            resized.width,
        )
        assertEquals(
            "the point the watch rotates the hand about must not move",
            centreBefore,
            rotationCentre(editedStyle, hand.globalIndex),
        )
    }

    /**
     * Stepping a Hand down and back up returns the exact bytes it started from.
     *
     * The pivot is what makes this worth asserting separately: it is scaled from the
     * *pristine* pivot rather than from the current one, and `x` absorbs the difference, so
     * the two roundings cancel. Scaling the current pivot instead drifts the hand a pixel
     * at a time — the same defect the resize ladder was built to remove from the extent.
     */
    @Test
    fun aHandResizeRoundTripIsExact() {
        val original = face("00001")
        val style = original.entryByBasename("style0.bin")
        val hand = FaceRecordParser.widgetGuides(style)
            .firstOrNull { it.type == WIDGET_HAND && it.canResize }
        assumeTrue("face 00001 style0 has no resizable Hand", hand != null)
        requireNotNull(hand)

        val shrunk = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = hand,
            width = (hand.width * 2 / 3).coerceAtLeast(1),
            height = (hand.height * 2 / 3).coerceAtLeast(1),
            pristine = original,
        ).container
        val shrunkGuide = FaceRecordParser.widgetGuides(shrunk.entryByBasename("style0.bin"))
            .single { it.globalIndex == hand.globalIndex }
        val restored = resizeGuide(
            source = shrunk,
            entryBasenames = listOf("style0.bin"),
            guide = shrunkGuide,
            width = hand.width,
            height = hand.height,
            pristine = original,
        ).container

        assertEquals(
            "restoring the shipped extent must hand back the shipped bytes",
            emptyList<String>(),
            differences(original, restored, "style0.bin"),
        )
    }

    /**
     * An image Arc's stored box is not the size of its artwork, and face `00028` is the
     * proof in the catalogue: an 84×84 box around a 90×90 raster. The vendor's own render
     * of face `00108` settles which one wins — a 204×204 ring inside a 256×256 box draws at
     * 204 px, centred — so the box and the raster have to scale *together*, by the same
     * ratio, or the container states two different sizes for one widget.
     */
    @Test
    fun anImageArcScalesItsBoxAndItsRasterTogether() {
        val original = face("00028")
        val style = original.entryByBasename("style0.bin")
        val arc = FaceRecordParser.widgetGuides(style)
            .firstOrNull { it.type == WIDGET_ARC && it.canResize }
        assumeTrue("face 00028 style0 has no resizable image Arc", arc != null)
        requireNotNull(arc)
        val rasterBefore = rasterOf(style, arc)
        assumeTrue("this test needs a box that differs from its raster", rasterBefore != null)
        requireNotNull(rasterBefore)

        val edited = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = arc,
            width = arc.width / 2,
            height = arc.height / 2,
            pristine = original,
        ).container
        val editedStyle = edited.entryByBasename("style0.bin")
        val resized = FaceRecordParser.widgetGuides(editedStyle)
            .single { it.globalIndex == arc.globalIndex }
        val rasterAfter = requireNotNull(rasterOf(editedStyle, resized))

        assertEquals("the box is what the requested size means", arc.width / 2, resized.width)
        assertEquals(arc.height / 2, resized.height)
        // The raster keeps the box's ratio rather than taking the box's numbers: on this
        // face it is 90/84 of it, and it has to stay that.
        assertEquals(
            "the raster scales by the box's ratio",
            (rasterBefore.first * (arc.width / 2) + arc.width / 2) / arc.width,
            rasterAfter.first,
        )
        assertEquals(
            "a resize may never change the image-record count",
            FaceRecordParser.scanImages(style).size,
            FaceRecordParser.scanImages(editedStyle).size,
        )
    }

    /**
     * A LineBar stores its box, its raster *and* a thickness, and all three agree in every
     * one of the catalogue's 16 records: box equals raster, and thickness equals the stored
     * height. The thickness is what the watch derives the bar's corner radius from, so a
     * resize that left it behind would round a 32 px bar as though it were 14 px.
     */
    @Test
    fun aResizedLineBarKeepsItsThicknessEqualToItsHeight() {
        val original = face("00028")
        val style = original.entryByBasename("style0.bin")
        val bar = FaceRecordParser.widgetGuides(style)
            .firstOrNull { it.type == WIDGET_LINE_BAR && it.canResize }
        assumeTrue("face 00028 style0 has no resizable LineBar", bar != null)
        requireNotNull(bar)
        assertEquals(
            "the premise: thickness equals the stored height in every catalogue LineBar",
            bar.height,
            thicknessOf(style, bar.globalIndex),
        )

        val edited = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = bar,
            width = bar.width / 2,
            height = (bar.height / 2).coerceAtLeast(2),
            pristine = original,
        ).container
        val editedStyle = edited.entryByBasename("style0.bin")
        val resized = FaceRecordParser.widgetGuides(editedStyle)
            .single { it.globalIndex == bar.globalIndex }

        assertEquals(bar.width / 2, resized.width)
        assertEquals(
            "thickness has to follow the height it always equalled",
            resized.height,
            thicknessOf(editedStyle, bar.globalIndex),
        )
        assertEquals(
            "box and raster agree in all 16 catalogue LineBars and must go on agreeing",
            resized.width to resized.height,
            rasterOf(editedStyle, resized),
        )
    }

    /**
     * A vector arc names no raster, so its resize is two halfwords and **the container does
     * not change length by a single byte**. That is worth asserting rather than assuming:
     * it is what makes this the one resize that cannot cross the 4 MiB ceiling, cannot
     * disturb the image-record count and cannot leave a pointer stale.
     */
    @Test
    fun aVectorArcResizeIsASameSizePatch() {
        val original = face("00023")
        val style = original.entryByBasename("style0.bin")
        val arc = FaceRecordParser.widgetGuides(style)
            .firstOrNull { it.type == WIDGET_VECTOR_ARC && it.canResize }
        assumeTrue("face 00023 style0 has no resizable vector arc", arc != null)
        requireNotNull(arc)
        assertEquals(
            "a widget that stores its own extent is bounded by the panel, not by bytes",
            WidgetResizeKind.FIELDS,
            arc.resizeKind,
        )

        val edit = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = arc,
            width = arc.width * 3 / 4,
            height = arc.height * 3 / 4,
            pristine = original,
        )
        val resized = FaceRecordParser.widgetGuides(edit.container.entryByBasename("style0.bin"))
            .single { it.globalIndex == arc.globalIndex }

        assertEquals(0, edit.sizeDelta)
        assertEquals(original.fileSize, edit.container.fileSize)
        assertEquals(arc.width * 3 / 4, resized.width)
        assertEquals(arc.height * 3 / 4, resized.height)
        assertTrue(
            "only the two halfwords of the box may change, so at most four bytes: " +
                "${edit.changedPayloadBytes}",
            edit.changedPayloadBytes in 1..4,
        )
    }

    /**
     * Growing a vector arc to the top of its ladder must not turn it into "the background".
     *
     * The ladder tops out at 512 px a side for a widget that stores its own extent, and the
     * panel is 256 × 402, so a 400 × 400 arc can be stepped past the panel in both axes. The
     * size-based background test is about panel-sized *artwork* — faces `00076` and `00089`
     * stack two such layers, one Static apiece — and a widget that draws no raster is not
     * one however large its box is. Before that distinction the arc relabelled itself as the
     * background layer at the top of its own ladder, and could then be neither selected nor
     * resized back: a one-way door reachable with two taps.
     */
    @Test
    fun aVectorArcGrownPastThePanelStaysAWidget() {
        val original = face("00023")
        val style = original.entryByBasename("style0.bin")
        val arc = FaceRecordParser.widgetGuides(style)
            .firstOrNull { it.type == WIDGET_VECTOR_ARC && it.canResize }
        assumeTrue("face 00023 style0 has no resizable vector arc", arc != null)
        requireNotNull(arc)
        val panel = FaceRecordParser.panelSize(style)
        val grown = WIDGET_EXTENT_CEILING
        assumeTrue(
            "this test needs a box the ladder can push past the panel",
            grown >= panel.width && grown >= panel.height,
        )

        val edited = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = arc,
            width = grown,
            height = grown,
            pristine = original,
        ).container
        val resized = FaceRecordParser.widgetGuides(edited.entryByBasename("style0.bin"))
            .single { it.globalIndex == arc.globalIndex }

        assertEquals(grown, resized.width)
        assertEquals(
            "a widget that draws no raster is never the background layer",
            dev.fitface.studio.core.model.WidgetPlacement.CANVAS,
            resized.placement,
        )
        assertTrue("and it has to still be resizable, or that was a one-way door", resized.canResize)
    }

    /**
     * A Rule's `+0x1C`/`+0x1E` is its second *endpoint*, so a resize scales the endpoint
     * vector. Two things fall out of that and both are asserted here: an exactly horizontal
     * Rule — 32 of the catalogue's 84 — stays exactly horizontal however its height is
     * stepped, and a Rule whose stored endpoint is the far one (52 of the 84) is not
     * flipped across its own start point.
     */
    @Test
    fun aResizedRuleStaysTheLineItWas() {
        val original = face("00004")
        val style = original.entryByBasename("style0.bin")
        val rule = FaceRecordParser.widgetGuides(style)
            .firstOrNull { it.type == WIDGET_BADGE && it.canResize }
        assumeTrue("face 00004 style0 has no resizable Rule", rule != null)
        requireNotNull(rule)
        val before = endpointOf(style, rule.globalIndex)

        val edit = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = rule,
            width = rule.width / 2,
            height = (rule.height / 2).coerceAtLeast(1),
            pristine = original,
        )
        val editedStyle = edit.container.entryByBasename("style0.bin")
        val after = endpointOf(editedStyle, rule.globalIndex)
        val start = FaceRecordParser.scanWidgets(style)
            .single { it.globalIndex == rule.globalIndex }

        assertEquals("a Rule stores its own geometry, so nothing is resampled", 0, edit.sizeDelta)
        assertEquals(
            "an axis with no span has no direction to scale, and must stay at zero",
            before.second == start.y,
            after.second == start.y,
        )
        assertEquals(
            "the endpoint must stay on the same side of the start point",
            (before.first - start.x).coerceIn(-1, 1),
            (after.first - start.x).coerceIn(-1, 1),
        )
        assertTrue(
            "the span has to have actually shortened",
            kotlin.math.abs(after.first - start.x) < kotlin.math.abs(before.first - start.x),
        )
    }

    /**
     * A plain-RGB565 sprite pool resizes, and restoring the shipped extent hands back the
     * shipped bytes.
     *
     * This is the 620-record unlock: those pools failed one condition, that the resampler
     * could only read three bytes per pixel. Nothing about them was ever unsafe, which is
     * what the byte-exact restore says — resampling to the original dimensions returns
     * every record to its original length and the container to the size the store shipped.
     */
    @Test
    fun aPlainRgb565PoolResizesAndRestoresExactly() {
        val original = face("00005")
        val style = original.entryByBasename("style0.bin")
        val sprite = FaceRecordParser.widgetGuides(style)
            .firstOrNull { guide ->
                guide.type == WIDGET_SPRITE && guide.canResize &&
                    rasterFormatOf(style, guide) == IMAGE_RGB565
            }
        assumeTrue("face 00005 style0 has no resizable plain-RGB565 sprite", sprite != null)
        requireNotNull(sprite)

        val shrunk = resizeGuide(
            source = original,
            entryBasenames = listOf("style0.bin"),
            guide = sprite,
            width = (sprite.width / 2).coerceAtLeast(1),
            height = (sprite.height / 2).coerceAtLeast(1),
            pristine = original,
        ).container
        assertTrue(
            "shrinking a pool must shrink the container",
            shrunk.fileSize < original.fileSize,
        )
        val shrunkGuide = FaceRecordParser.widgetGuides(shrunk.entryByBasename("style0.bin"))
            .single { it.globalIndex == sprite.globalIndex }
        val restored = resizeGuide(
            source = shrunk,
            entryBasenames = listOf("style0.bin"),
            guide = shrunkGuide,
            width = sprite.width,
            height = sprite.height,
            pristine = original,
        ).container

        assertEquals(
            "restoring the shipped extent must hand back the shipped bytes",
            emptyList<String>(),
            differences(original, restored, "style0.bin"),
        )
    }

    /**
     * Where two versions of one entry differ, as offsets rather than as bytes.
     *
     * A style entry is a few hundred kilobytes, so a list-equality failure prints a wall of
     * numbers that says nothing about which field went wrong.
     */
    private fun differences(
        expected: Fit3Container,
        actual: Fit3Container,
        basename: String,
    ): List<String> {
        val before = expected.entryByBasename(basename).data
        val after = actual.entryByBasename(basename).data
        if (before.size != after.size) {
            return listOf("entry length ${before.size} became ${after.size}")
        }
        return before.indices
            .filter { before[it] != after[it] }
            .take(12)
            .map { "0x${it.toString(16)}: ${before[it]} became ${after[it]}" }
    }

    private fun rasterIndexOf(
        entry: ContainerEntry,
        guide: dev.fitface.studio.core.model.WidgetGuide,
    ): Int? = imageOf(entry, guide)?.index

    private fun face(id: String): Fit3Container {
        val path = containers.firstOrNull { it.fileName.toString().contains(id) }
        assumeTrue("face $id not in corpus", path != null)
        return Fit3Container.parse(Files.readAllBytes(requireNotNull(path)))
    }

    /** The dimensions of the single raster a widget addresses, or null if it names none. */
    private fun rasterOf(
        entry: ContainerEntry,
        guide: dev.fitface.studio.core.model.WidgetGuide,
    ): Pair<Int, Int>? = imageOf(entry, guide)?.let { it.width to it.height }

    private fun rasterFormatOf(
        entry: ContainerEntry,
        guide: dev.fitface.studio.core.model.WidgetGuide,
    ): Int? = imageOf(entry, guide)?.format

    private fun imageOf(
        entry: ContainerEntry,
        guide: dev.fitface.studio.core.model.WidgetGuide,
    ): ImageRecord? {
        val images = FaceRecordParser.scanImages(entry)
        val start = images.firstOrNull()?.recordOffset ?: return null
        val relative = images.associateBy { (it.recordOffset - start).toLong() }
        val record = FaceRecordParser.scanWidgets(entry)
            .single { it.globalIndex == guide.globalIndex }
        return runCatching { FaceRecordParser.imagePointerFields(record, relative) }
            .getOrNull()
            ?.firstOrNull()
            ?.image
    }

    /** `x + pivot`, in panel coordinates: the point the watch rotates a Hand about. */
    private fun rotationCentre(entry: ContainerEntry, globalIndex: Int): Pair<Int, Int> {
        val record = FaceRecordParser.scanWidgets(entry).single { it.globalIndex == globalIndex }
        val pivotX = entry.data.u16(record.recordOffset + 0x20).toShort().toInt()
        val pivotY = entry.data.u16(record.recordOffset + 0x22).toShort().toInt()
        return (record.x + pivotX) to (record.y + pivotY)
    }

    private fun thicknessOf(entry: ContainerEntry, globalIndex: Int): Int {
        val record = FaceRecordParser.scanWidgets(entry).single { it.globalIndex == globalIndex }
        return entry.data[record.recordOffset + 0x30].toInt() and 0xFF
    }

    private fun endpointOf(entry: ContainerEntry, globalIndex: Int): Pair<Int, Int> {
        val record = FaceRecordParser.scanWidgets(entry).single { it.globalIndex == globalIndex }
        return record.raw1C.toShort().toInt() to record.raw1E.toShort().toInt()
    }
}
