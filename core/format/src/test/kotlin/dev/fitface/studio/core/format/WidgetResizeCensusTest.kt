package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetCategory
import dev.fitface.studio.core.model.WidgetResizeKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * What the whole catalogue can be resized, counted.
 *
 * The numbers are the point. Resize covered one type and 859 of the catalogue's 4,034
 * records, and every extension of it is a claim about how many more — a claim that is easy
 * to make and easy to lose to a refactor, because a widget that silently stops being
 * resizable looks exactly like a widget that never was. So the counts are pinned per type,
 * and the reasons the remainder is refused are pinned with them.
 *
 * These are counts over the 99 corpus containers' numbered styles **and** their `aod.bin`
 * entries, which is every editable face entry the app can open.
 */
class WidgetResizeCensusTest {
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
        assumeTrue("corpus holds 99 containers", containers.size == 99)
    }

    @Test
    fun everyTypeIsResizableExactlyAsOftenAsItsEvidenceAllows() {
        val resizable = mutableMapOf<Int, Int>()
        val refused = mutableMapOf<Int, Int>()
        val kinds = mutableMapOf<WidgetResizeKind, Int>()
        var records = 0
        containers.forEach { path ->
            val container = Fit3Container.parse(Files.readAllBytes(path))
            container.entries
                .filter { StyleWidgetMatch.isVariantEntry(it.basename) }
                .forEach { entry ->
                    FaceRecordParser.widgetGuides(entry).forEach { guide ->
                        records++
                        kinds.merge(guide.resizeKind, 1, Int::plus)
                        if (guide.canResize) {
                            resizable.merge(guide.type, 1, Int::plus)
                        } else {
                            refused.merge(guide.type, 1, Int::plus)
                        }
                    }
                }
        }

        val census = "resizable=$resizable refused=$refused kinds=$kinds records=$records"
        assertEquals(census, 4_034, records)

        // Per type. Each of these is a fact about the catalogue, not a preference: the
        // refused column is the evidence for the ceiling on each row.
        assertEquals("$census — Static", 293, resizable[WIDGET_STATIC])
        assertEquals(
            "$census — the refused Statics are the panel backgrounds, which the " +
                "Background page replaces instead",
            388,
            refused[WIDGET_STATIC],
        )
        assertEquals("$census — Hand", 469, resizable[WIDGET_HAND])
        assertEquals("$census — vector arc", 75, resizable[WIDGET_VECTOR_ARC])
        assertEquals("$census — Rule", 84, resizable[WIDGET_BADGE])
        assertEquals("$census — image Arc", 30, resizable[WIDGET_ARC])
        assertEquals("$census — LineBar", 16, resizable[WIDGET_LINE_BAR])

        // The two text types are refused as a whole, because their size is not in the
        // record: the watch draws their glyphs from one of its own fonts and `+0x1C` is
        // only the box around the result. Face 00005 proves it from the vendor's own
        // render — the same Composite at box 180x40 and 180x60, same font, pixel-identical
        // output — so a box-only "resize" would move nothing on the watch while the
        // preview showed scaled text.
        assertEquals("$census — no Value is resizable", null, resizable[WIDGET_PAIR])
        assertEquals("$census — no Composite is resizable", null, resizable[WIDGET_COMP])
        assertEquals("$census — every Value is refused", 734, refused[WIDGET_PAIR])
        assertEquals("$census — every Composite is refused", 427, refused[WIDGET_COMP])

        // Sprites: 859 were resizable when RGB565+A was the only format the resampler
        // could read, and 620 of the rest were refused for the format alone — see
        // WidgetResizeByTypeTest.aPlainRgb565PoolResizesAndRestoresExactly. The remaining
        // 32 were refused for requiring a data source unique among the entry's Sprites,
        // which stopped being a condition when the selector stopped using it as an
        // identity. What is left is the 7 whose pooled frames disagree about their size,
        // where "the largest frame is the extent" does not hold and a single pair of
        // numbers cannot describe the pool.
        assertEquals("$census — Sprite", 1_511, resizable[WIDGET_SPRITE])
        assertEquals("$census — Sprites with a non-uniform pool", 7, refused[WIDGET_SPRITE])

        // The totals, which are the reason this test exists.
        assertEquals("$census — resizable records", 2_478, resizable.values.sum())
        assertEquals(
            "$census — nothing else is refused: 388 background Statics, 7 non-uniform " +
                "Sprite pools and the 1,161 text records",
            388 + 7 + 734 + 427,
            refused.values.sum(),
        )

        assertEquals(
            "$census — a widget that stores its own extent adds no bytes, so it is " +
                "bounded by the panel rather than by the container ceiling",
            (resizable[WIDGET_VECTOR_ARC] ?: 0) + (resizable[WIDGET_BADGE] ?: 0),
            kinds[WidgetResizeKind.FIELDS],
        )
    }

    /**
     * Every face in the catalogue has something resizable.
     *
     * It was 63 of the 99 when a Sprite with uniform RGB565+A frames was the only thing
     * that qualified. The 36 that had nothing were not unusual faces — most of them are
     * analog, so their widgets are hands and statics.
     */
    @Test
    fun everyFaceHasSomethingResizable() {
        val without = containers.filter { path ->
            val container = Fit3Container.parse(Files.readAllBytes(path))
            container.entries
                .filter { StyleWidgetMatch.isVariantEntry(it.basename) }
                .none { entry -> FaceRecordParser.widgetGuides(entry).any { it.canResize } }
        }
        assertEquals(emptyList<String>(), without.map { it.fileName.toString() })
    }

    /**
     * Nothing outside the five raster-backed types and the two field-only ones is offered
     * a resize, whatever a future schema edit does.
     *
     * The list is short on purpose. An Animation would follow a Sprite's code path exactly
     * and is still refused, because no catalogue face carries one: a control offered on a
     * type with no sample is one nothing here can test.
     */
    @Test
    fun onlyTheSevenEstablishedTypesAreOfferedAResize() {
        val offered = mutableSetOf<Int>()
        containers.forEach { path ->
            val container = Fit3Container.parse(Files.readAllBytes(path))
            container.entries
                .filter { StyleWidgetMatch.isVariantEntry(it.basename) }
                .forEach { entry ->
                    FaceRecordParser.widgetGuides(entry)
                        .filter { it.canResize }
                        .forEach { offered += it.type }
                }
        }
        assertEquals(
            setOf(
                WIDGET_STATIC,
                WIDGET_HAND,
                WIDGET_SPRITE,
                WIDGET_VECTOR_ARC,
                WIDGET_BADGE,
                WIDGET_ARC,
                WIDGET_LINE_BAR,
            ),
            offered,
        )
        assertTrue(
            "the schema table and the gate have to agree about which types have a model",
            offered.all { WidgetSchema.spec(it).resize != null },
        )
        assertTrue(
            "a type with no resize model must never be offered one",
            WidgetSchema.all
                .filter { it.resize == null }
                .none { it.type in offered },
        )
        assertEquals(
            "the categories the UI explains a refusal for have to be the ones refused",
            listOf(WidgetCategory.VALUE, WidgetCategory.COMPOSITE),
            listOf(WIDGET_PAIR, WIDGET_COMP).map { WidgetSchema.spec(it).category },
        )
    }

    /**
     * Nothing the editor refuses to resize may describe a resize in its help text.
     *
     * The Hand arm of `supportMessage` is the one that can: it appends the shared-pool
     * note, which is a sentence about what resizing does, and a Hand whose pool failed
     * `poolIsResizable` would carry it with no size controls on the screen beside it.
     * Every corpus Hand is resizable today, so the count cannot pin this — the phrasing
     * has to, and it then holds whatever a later schema or pool check does to the counts.
     */
    @Test
    fun nothingTheEditorRefusesToResizePromisesOne() {
        val promises = listOf("Resizing ", "resize rewrites", "resize with it")
        val offenders = mutableListOf<String>()
        var poolNotes = 0
        containers.forEach { path ->
            val container = Fit3Container.parse(Files.readAllBytes(path))
            val face = path.fileName.toString()
            container.entries
                .filter { StyleWidgetMatch.isVariantEntry(it.basename) }
                .forEach { entry ->
                    FaceRecordParser.widgetGuides(entry).forEach { guide ->
                        val promised = promises.filter { it in guide.supportMessage }
                        if (guide.canResize) {
                            if (guide.type == WIDGET_HAND && promised.isNotEmpty()) poolNotes++
                        } else if (promised.isNotEmpty()) {
                            offenders += "$face/${entry.basename} #${guide.globalIndex} " +
                                "(type ${guide.type}) is not resizable and says " +
                                "\"${guide.supportMessage}\""
                        }
                    }
                }
        }
        assertEquals(emptyList<String>(), offenders.take(4))
        assertTrue(
            "the shared-pool note has to be reachable, or this test asserts nothing",
            poolNotes > 0,
        )
    }

    @Test
    fun everyResizableHandExplainsItsPivotPreservingResize() {
        var checked = 0
        containers.forEach { path ->
            val container = Fit3Container.parse(Files.readAllBytes(path))
            container.entries.filter { StyleWidgetMatch.isVariantEntry(it.basename) }
                .flatMap(FaceRecordParser::widgetGuides)
                .filter { it.type == WIDGET_HAND && it.canResize }
                .forEach { hand ->
                    assertTrue(hand.supportMessage, "Resizing scales its artwork and pivot" in hand.supportMessage)
                    checked++
                }
        }
        assertTrue("the corpus must exercise a hand", checked > 0)
    }
}
