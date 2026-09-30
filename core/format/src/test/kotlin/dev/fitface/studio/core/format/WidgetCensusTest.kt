package dev.fitface.studio.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * What the live catalogue actually contains, pinned.
 *
 * This is the regression net under the typed record layer. Every number here was
 * measured over all 99 container-carrying faces, and each one is a claim the rest of the
 * app now relies on: that a record is exactly its type's size, that relative alignment is
 * the normal case rather than a corner, and that the reference fields name either widget
 * zero or nothing at all.
 *
 * A parser change that quietly reinterprets a field will move one of these counts, which
 * is the whole point — the last time a field was misread the symptom was a widget drawn
 * 200 px from where the watch draws it, and nothing failed.
 */
class WidgetCensusTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val catalogue = root.resolve("SM_R390")

    @Before
    fun requireCorpus() {
        assumeTrue(
            "the catalogue corpus is not available under $catalogue",
            Files.isDirectory(catalogue),
        )
    }

    /**
     * One container at a time, never a list of them.
     *
     * The catalogue is 99 containers of up to 4 MiB, and holding them all at once is
     * hundreds of megabytes of byte arrays — the first version of this test ran the JVM
     * out of heap before it asserted anything.
     */
    private fun forEachContainer(action: (String, Fit3Container) -> Unit) {
        val paths = Files.walk(catalogue).use { walk ->
            walk.asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".bin") }
                .sorted()
                .toList()
        }
        assumeTrue("the catalogue corpus is empty", paths.isNotEmpty())
        paths.forEach { path ->
            action(path.fileName.toString(), Fit3Container.parse(Files.readAllBytes(path)))
        }
    }

    private fun forEachStyle(action: (ContainerEntry, List<WidgetRecord>) -> Unit) {
        forEachContainer { _, container ->
            FaceResources.variantEntries(container).forEach {
                action(it, FaceRecordParser.scanWidgets(it))
            }
        }
    }

    @Test
    fun everyRecordIsExactlyItsTypesSize() {
        val byType = mutableMapOf<Int, Int>()
        var total = 0
        forEachStyle { entry, records ->
            records.forEach { record ->
                total++
                byType[record.widgetType] = (byType[record.widgetType] ?: 0) + 1
                val spec = WidgetSchema.spec(record.widgetType)
                spec.exactSize?.let {
                    assertEquals(
                        "${entry.basename} widget ${record.globalIndex} (${spec.name})",
                        it,
                        record.recordSize,
                    )
                }
                (spec.pointers as? WidgetSchema.PointerLayout.Table)?.let { layout ->
                    assertEquals(
                        "${entry.basename} widget ${record.globalIndex} frame table",
                        layout.firstOffset + (record.frameCount ?: 0) * 4,
                        record.recordSize,
                    )
                }
            }
        }

        assertEquals(4_034, total)
        assertEquals(
            mapOf(
                1 to 681, 2 to 469, 3 to 1_518, 5 to 734,
                6 to 75, 7 to 84, 13 to 427, 16 to 30, 17 to 16,
            ),
            byType.toSortedMap(),
        )
    }

    /**
     * Relative alignment is how the catalogue positions text and artwork, not an oddity.
     *
     * Not one of these 2,311 records uses the sentinel that would make its coordinates
     * absolute, which is why treating the fields as an unsigned width and height was
     * survivable for so long: the alignment they describe almost always resolves to a
     * full-panel background sitting at the origin.
     */
    @Test
    fun everyAligningRecordCarriesALiveReference() {
        val codes = mutableMapOf<Int, Int>()
        var aligning = 0
        var live = 0
        forEachStyle { _, records ->
            records.forEach { record ->
                if (record.alignment == null) return@forEach
                aligning++
                record.liveAlignment?.let {
                    live++
                    codes[it.code] = (codes[it.code] ?: 0) + 1
                }
            }
        }

        assertEquals(2_311, aligning)
        assertEquals("no catalogue record disables its alignment", aligning, live)
        assertEquals(mapOf(0 to 1_537, 1 to 651, 2 to 37, 3 to 86), codes.toSortedMap())
    }

    /**
     * A reference either names widget zero or names nothing.
     *
     * The 397 that name nothing are not damage: the producer writes 5, 10, 20, 30 and 40
     * into that field, none of which is a record in the style, and the watch positions
     * those widgets against the whole face. That is why the app has to treat an
     * unresolvable reference as an ordinary outcome instead of an error.
     */
    @Test
    fun everyResolvingReferenceNamesWidgetZero() {
        var resolving = 0
        var panelFallback = 0
        var selfReference = 0
        val targets = mutableSetOf<Int>()
        forEachStyle { _, records ->
            val indices = records.mapTo(mutableSetOf(), WidgetRecord::globalIndex)
            records.forEach { record ->
                val alignment = record.liveAlignment ?: return@forEach
                targets += alignment.targetGlobalIndex
                when {
                    alignment.targetGlobalIndex == record.globalIndex -> selfReference++
                    alignment.targetGlobalIndex in indices -> resolving++
                    else -> panelFallback++
                }
            }
        }

        assertEquals(1_537, resolving)
        assertEquals(397, panelFallback)
        assertEquals(377, selfReference)
        assertEquals(setOf(0, 5, 10, 20, 30, 40), targets)
    }

    /** A widget follows one of the watch's readings; it cannot introduce one. */
    @Test
    fun everyRecordFollowsAReadingItsTypeSupports() {
        forEachStyle { entry, records ->
            records.forEach { record ->
                val accepted = WidgetSchema.spec(record.widgetType).sources ?: return@forEach
                assertTrue(
                    "${entry.basename} widget ${record.globalIndex} follows reading " +
                        "${record.sourceId}, which its type does not support",
                    record.sourceId in accepted,
                )
            }
        }
    }

    /**
     * How many widgets the sign rule used to put in the wrong place.
     *
     * The old geometry read a negative stored coordinate as "anchored to the far edge of
     * the face". That is exactly right for the one alignment code measured from the right
     * edge, and wrong for a coordinate that is simply negative — a gauge that starts off
     * the left of the panel, or a label aligned to the top-left with a small negative
     * offset. It was also wrong for every widget centred on its target.
     *
     * These are the records where the two disagree. The count is pinned so the geometry
     * cannot quietly go back: they are drawn where the watch draws them now.
     */
    @Test
    fun theSignRuleDisagreesWithResolvedGeometryOnExactlySixtyTwoRecords() {
        fun signRule(value: Int, extent: Int, canvasExtent: Int) =
            if (value < 0) canvasExtent + value - extent else value

        var disagreements = 0
        forEachContainer { _, container ->
            FaceResources.variantEntries(container).forEach { entry ->
                val panel = FaceRecordParser.panelSize(entry)
                FaceRecordParser.widgetGuides(entry).forEach { guide ->
                    val oldLeft = signRule(guide.x, guide.width, panel.width) + guide.drawOffsetX
                    val oldTop = signRule(guide.y, guide.height, panel.height) + guide.drawOffsetY
                    if (oldLeft != guide.originX + guide.x + guide.drawOffsetX ||
                        oldTop != guide.originY + guide.y + guide.drawOffsetY
                    ) {
                        disagreements++
                    }
                }
            }
        }

        assertEquals(62, disagreements)
    }
}
