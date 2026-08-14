package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetCategory
import dev.fitface.studio.core.model.WidgetPlacement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Placement and extent are derived from the panel the watch renders and from the
 * rasters a record actually addresses — not from "raster 0 is the background" and
 * not from the stored width/height, both of which the corpus contradicts.
 */
class WidgetPlacementTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    private fun container(face: String): Fit3Container {
        val path = root.resolve("SM_R390/SM-R390_${face}_256x402/SM-R390_${face}_256x402.bin")
        assumeTrue("corpus container for $face is not available", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }

    private val faces = listOf("00022", "00028", "00046", "00079", "00106", "00108")

    private fun variantEntries(container: Fit3Container) = container.entries.filter {
        it.basename == "aod.bin" || it.basename.matches(Regex("""style\d+\.bin"""))
    }

    @Test
    fun everyVariantEntryDeclaresThePanelGeometryRatherThanItsFirstRaster() {
        faces.forEach { face ->
            variantEntries(container(face)).forEach { entry ->
                assertEquals(
                    "$face/${entry.basename}",
                    PanelSize(256, 402),
                    FaceRecordParser.panelSize(entry),
                )
            }
        }
    }

    /**
     * The regression that made widgets render without a selection border: sizing the
     * canvas from raster 0 shrank these entries to an icon, after which every widget
     * larger than the icon counted as the full-face background layer.
     */
    @Test
    fun entriesWhoseFirstRasterIsNotThePanelHaveNoBackgroundLayer() {
        val withoutBackground = mutableListOf<String>()
        faces.forEach { face ->
            variantEntries(container(face)).forEach { entry ->
                val background = FaceRecordParser.backgroundImage(entry)
                val firstRaster = FaceRecordParser.scanImages(entry).first()
                if (background == null) {
                    withoutBackground += "$face/${entry.basename}"
                    assertTrue(
                        "$face/${entry.basename} first raster is panel sized",
                        firstRaster.width != 256 || firstRaster.height != 402,
                    )
                } else {
                    assertEquals(256 to 402, background.width to background.height)
                }
            }
        }
        assertEquals(
            listOf(
                "00022/aod.bin",
                "00022/style0.bin",
                "00022/style1.bin",
                "00022/style2.bin",
                "00028/aod.bin",
                "00079/aod.bin",
                "00106/aod.bin",
                "00108/aod.bin",
                "00108/style0.bin",
                "00108/style1.bin",
                "00108/style2.bin",
                "00108/style3.bin",
            ),
            withoutBackground.sorted(),
        )
    }

    @Test
    fun atMostOneWidgetPerVariantIsTheBackgroundLayer() {
        faces.forEach { face ->
            variantEntries(container(face)).forEach { entry ->
                val guides = FaceRecordParser.widgetGuides(entry)
                val backgrounds = guides.filter {
                    it.placement == WidgetPlacement.BACKGROUND
                }
                assertTrue(
                    "$face/${entry.basename} reports ${backgrounds.size} background layers: " +
                        backgrounds.map { it.globalIndex },
                    backgrounds.size <= 1,
                )
                // A style with no panel raster has no background widget either.
                if (FaceRecordParser.backgroundImage(entry) == null) {
                    assertTrue(
                        "$face/${entry.basename} invented a background layer",
                        backgrounds.isEmpty(),
                    )
                }
            }
        }
    }

    /** Faces 00022 and 00108 used to report most of their widgets as the background. */
    @Test
    fun facesWithoutAPanelRasterExposeEveryWidgetOnTheCanvas() {
        listOf("00022" to 11, "00108" to 10).forEach { (face, expectedWidgets) ->
            val entry = container(face).entryByBasename("style0.bin")
            val guides = FaceRecordParser.widgetGuides(entry)

            assertEquals(face, expectedWidgets, guides.size)
            assertEquals(
                "$face/style0.bin should place every record on the canvas",
                expectedWidgets,
                guides.count { it.placement == WidgetPlacement.CANVAS },
            )
        }
    }

    /**
     * A raster-backed widget is as big as the raster the watch blits. Face 00079
     * stores width 1 for digit sprites whose frames are 52 px wide, and 00022 stores
     * height 20 for frames that are 136 px tall; trusting the stored extent drew a
     * one-pixel sliver where the widget should be.
     */
    @Test
    fun rasterBackedWidgetsTakeTheirExtentFromTheirFrames() {
        val digits = FaceRecordParser.widgetGuides(
            container("00079").entryByBasename("style0.bin"),
        ).filter { it.category == WidgetCategory.SPRITE }

        assertEquals(4, digits.size)
        digits.forEach { widget ->
            assertEquals("seq ${widget.sequenceId}", 52 to 72, widget.width to widget.height)
        }

        val tall = FaceRecordParser.widgetGuides(
            container("00022").entryByBasename("style0.bin"),
        ).single { it.sequenceId == 2 && it.category == WidgetCategory.SPRITE }
        assertEquals(114 to 136, tall.width to tall.height)
    }

    @Test
    fun spriteFrameCountsComeFromTheRecordAndOtherTypesReportNone() {
        val guides = FaceRecordParser.widgetGuides(
            container("00106").entryByBasename("style0.bin"),
        ).associateBy { it.globalIndex }

        // Weather icon set, hour tens, hour ones, minute tens, minute ones.
        assertEquals(24, guides.getValue(6).frameCount)
        assertEquals(3, guides.getValue(7).frameCount)
        assertEquals(10, guides.getValue(8).frameCount)
        assertEquals(6, guides.getValue(9).frameCount)
        assertEquals(10, guides.getValue(10).frameCount)
        assertNull(guides.getValue(11).frameCount)
        assertNull(guides.getValue(13).frameCount)
    }

    @Test
    fun categoriesNameEveryTypeTheCorpusUses() {
        val seen = mutableMapOf<Int, WidgetCategory>()
        faces.forEach { face ->
            variantEntries(container(face)).forEach { entry ->
                FaceRecordParser.widgetGuides(entry).forEach { seen[it.type] = it.category }
            }
        }
        assertEquals(
            mapOf(
                1 to WidgetCategory.IMAGE,
                2 to WidgetCategory.HAND,
                3 to WidgetCategory.SPRITE,
                5 to WidgetCategory.VALUE,
                7 to WidgetCategory.RULE,
                13 to WidgetCategory.COMPOSITE,
                16 to WidgetCategory.ARC,
                17 to WidgetCategory.BAR,
            ),
            seen.toSortedMap(),
        )
    }

    /**
     * `words[0]` of a Static is `0x0` in every corpus record, which is the background
     * raster's own relative offset. Falling through to the type-word list therefore
     * resolved unrelated Statics onto the background.
     */
    @Test
    fun staticWidgetsResolveOnlyTheirOwnPointerWord() {
        val entry = container("00106").entryByBasename("style0.bin")
        val statics = FaceRecordParser.scanWidgets(entry).filter { it.widgetType == 1 }
        assertTrue(statics.isNotEmpty())
        assertTrue(
            "corpus assumption changed: a Static no longer pads words[0] with 0",
            statics.all { it.words.firstOrNull() == 0L },
        )

        val glyph = FaceRecordParser.widgetGuides(entry).single { it.globalIndex == 11 }
        assertEquals(WidgetCategory.IMAGE, glyph.category)
        assertEquals(WidgetPlacement.CANVAS, glyph.placement)
        assertEquals(30 to 90, glyph.width to glyph.height)

        val background = FaceRecordParser.widgetGuides(entry).single { it.globalIndex == 0 }
        assertEquals(WidgetPlacement.BACKGROUND, background.placement)
        assertNotNull(FaceRecordParser.backgroundImage(entry))
    }

    /**
     * Resize is only offered where StructuralEditor.resizeSprite will accept it — the
     * UI enabling a control whose commit always fails is the failure this guards.
     */
    @Test
    fun resizableSpritesAreAcceptedByTheStructuralEditor() {
        var resized = 0
        faces.forEach { face ->
            val source = container(face)
            source.entries.filter { it.basename.matches(Regex("""style\d+\.bin""")) }
                .forEach { entry ->
                    FaceRecordParser.widgetGuides(entry)
                        .filter { it.canResize }
                        .forEach { widget ->
                            val edit = StructuralEditor.resizeSprite(
                                source,
                                listOf(entry.basename),
                                widget.sequenceId,
                                (widget.width - 2).coerceIn(1, 128),
                                (widget.height - 2).coerceIn(1, 128),
                            )
                            assertTrue(
                                "$face/${entry.basename} seq ${widget.sequenceId}",
                                edit.container.validate().isValid,
                            )
                            resized++
                        }
                }
        }
        assertTrue("no resizable sprite found in the corpus", resized > 0)
    }
}
