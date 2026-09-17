package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.drawLeft
import dev.fitface.studio.core.model.drawTop
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WidgetImporterTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))
    private val samples = listOf("00008" to 1, "00002" to 2, "00025" to 1,
        "00001" to 1, "00023" to 2, "00004" to 1, "00003" to 3,
        "00028" to 1, "00028" to 13)
    private fun face(id: String): Fit3Container {
        val name = "SM-R390_${id}_256x402"
        val path = root.resolve("SM_R390/$name/$name.bin")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        return Fit3Container.parse(Files.readAllBytes(path))
    }
    private fun entry(c: Fit3Container) = c.entryByBasename("style0.bin")
    private fun valid(c: Fit3Container) {
        assertEquals(emptyList<ValidationIssue>(), c.validate().issues)
        assertArrayEquals(c.toByteArray(), Fit3Container.parse(c.toByteArray()).toByteArray())
    }

    @Test fun allNineTypesAppendWithoutChangingAnyNativeRecordOrRaster() {
        val original = face("00106")
        var current = original
        val types = mutableSetOf<Int>()
        samples.forEach { (id, index) ->
            val donor = face(id)
            val guide = FaceRecordParser.widgetGuides(entry(donor))[index]
            val old = entry(current)
            val oldRecords = FaceRecordParser.scanWidgets(old)
            val result = WidgetImporter.importWidget(current, "style0.bin", donor, "style0.bin", index)
            current = result.edit.container
            valid(current)
            val after = entry(current)
            val added = FaceRecordParser.widgetGuides(after).last()
            assertEquals(guide.type, added.type)
            assertEquals(guide.drawLeft, added.drawLeft)
            assertEquals(guide.drawTop, added.drawTop)
            assertEquals(oldRecords.size, result.globalIndex)
            oldRecords.zip(FaceRecordParser.scanWidgets(after)).forEach { (a, b) ->
                assertArrayEquals(old.data.copyOfRange(a.recordOffset, a.recordOffset + a.recordSize),
                    after.data.copyOfRange(b.recordOffset, b.recordOffset + b.recordSize))
            }
            assertArrayEquals(old.data.copyOfRange(StyleHeader.parse(old).storedImageOffset, old.size),
                after.data.copyOfRange(StyleHeader.parse(after).storedImageOffset,
                    StyleHeader.parse(after).storedImageOffset + StyleHeader.parse(old).imageBytes))
            for (variant in FaceResources.variantEntries(original).filter { it.basename != "style0.bin" }) {
                val actual = current.entryByBasename(variant.basename).data.copyOf()
                actual[0x11] = variant.data[0x11] // Package-wide font count only.
                assertArrayEquals(variant.data, actual)
            }
            types += added.type
        }
        assertEquals(WidgetImporter.stockTypes, types)
    }

    @Test fun textMeaningAndFontBindingArePreservedForEveryLocale() {
        for ((id, index) in listOf("00001" to 1, "00003" to 3)) {
            val donor = face(id)
            val result = WidgetImporter.importWidget(face("00106"), "style0.bin", donor, "style0.bin", index)
            val c = result.edit.container
            val before = FaceRecordParser.scanWidgets(entry(donor))[index]
            val after = FaceRecordParser.scanWidgets(entry(c)).last()
            FaceResources.dictionaries(c).forEach { dictionary ->
                val locale = dictionary.basename.removePrefix("font_").removeSuffix(".bin")
                val expected = WidgetTextResources(donor.entries, locale).text(entry(donor), before)!!
                val actual = WidgetTextResources(c.entries, locale).text(entry(c), after)!!
                assertEquals("$id $locale", expected.text, actual.text)
                assertEquals(expected.font.pixelSize, actual.font.pixelSize)
                assertArrayEquals(expected.font.familyByLanguage, actual.font.familyByLanguage)
            }
        }
    }

    @Test fun importedRasterResizesAlwaysUseItsOwnPristineArtwork() {
        samples.filterIndexed { index, _ -> index in listOf(0, 1, 2, 7, 8) }.forEach { (id, index) ->
            val target = face("00106")
            val result = WidgetImporter.importWidget(target, "style0.bin", face(id), "style0.bin", index)
            val start = result.edit.container
            val guide = FaceRecordParser.widgetGuides(entry(start)).last()
            val sources = mapOf("style0.bin" to WidgetPristine(result.baseline, mapOf(result.globalIndex to 0)))
            fun resize(c: Fit3Container, width: Int, height: Int): Fit3Container {
                val r = FaceRecordParser.scanWidgets(entry(c)).last()
                return StructuralEditor.resizeWidget(c, listOf("style0.bin"), r.globalIndex,
                    r.widgetType, r.sequenceId, r.x, r.y, width, height, target, sources).container
            }
            val small = resize(start, maxOf(1, guide.width / 2), maxOf(1, guide.height / 2))
            val restored = resize(small, guide.width, guide.height)
            val again = resize(restored, maxOf(1, guide.width / 2), maxOf(1, guide.height / 2))
            assertArrayEquals("$id restores original pixels and geometry", entry(start).data, entry(restored).data)
            assertArrayEquals("$id no chained resampling", entry(small).data, entry(again).data)
            valid(restored)
        }
    }

    @Test fun addingTwiceCopiesIsolatedRasterPoolsAndPreservesRootAlignment() {
        val donor = face("00008")
        val first = WidgetImporter.importWidget(face("00106"), "style0.bin", donor, "style0.bin", 1)
        val second = WidgetImporter.importWidget(first.edit.container, "style0.bin", donor, "style0.bin", 1)
        val e = entry(second.edit.container)
        val records = FaceRecordParser.scanWidgets(e).takeLast(2)
        assertEquals(listOf(WidgetImporter.ROOT_TARGET, WidgetImporter.ROOT_TARGET),
            records.map { it.liveAlignment!!.targetGlobalIndex })
        val images = FaceRecordParser.imagesByRelativeOffset(e)
        assertNotEquals(FaceRecordParser.imagePointerFields(records[0], images).single().image.index,
            FaceRecordParser.imagePointerFields(records[1], images).single().image.index)
        assertTrue(FaceRecordParser.originalWidgetSources(e, entry(face("00106")),
            setOf(first.globalIndex, second.globalIndex)).keys.none { it >= first.globalIndex })
    }

    @Test fun oversizeImportFailsWithoutChangingTheTarget() {
        val target = face("00022")
        val before = target.toByteArray()
        val error = assertThrows(Fit3FormatException::class.java) {
            WidgetImporter.importWidget(target, "style0.bin", face("00002"), "style0.bin", 2)
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("4194304"))
        assertArrayEquals(before, target.toByteArray())
    }

    @Test fun backgroundIsNotAnImportCandidate() {
        val e = entry(face("00002"))
        val background = FaceRecordParser.widgetGuides(e).single { it.placement ==
            dev.fitface.studio.core.model.WidgetPlacement.BACKGROUND }
        assertNotNull(WidgetImporter.unavailableReason(e, background.globalIndex))
    }

    @Test fun catalogueSourceAndAlignmentProfilesKeepTheirPlacement() {
        val target = face("00106")
        val profiles = mutableSetOf<List<Int>>()
        Files.list(root.resolve("SM_R390")).use { directories ->
            directories.sorted().forEach { directory ->
                val path = directory.resolve(directory.fileName.toString() + ".bin")
                if (!Files.isRegularFile(path)) return@forEach
                val donor = Fit3Container.parse(Files.readAllBytes(path))
                val e = donor.entryByBasename("style0.bin")
                val guides = FaceRecordParser.widgetGuides(e).associateBy { it.globalIndex }
                for (record in FaceRecordParser.scanWidgets(e)) {
                    if (WidgetImporter.unavailableReason(e, record.globalIndex) != null) continue
                    val profile = listOf(record.widgetType, record.sequenceId, record.liveAlignment?.code ?: -1)
                    if (!profiles.add(profile)) continue
                    val result = WidgetImporter.importWidget(target, "style0.bin", donor, "style0.bin", record.globalIndex)
                    val added = FaceRecordParser.widgetGuides(entry(result.edit.container)).last()
                    val before = guides.getValue(record.globalIndex)
                    assertEquals("${directory.fileName} $profile x", before.drawLeft, added.drawLeft)
                    assertEquals("${directory.fileName} $profile y", before.drawTop, added.drawTop)
                    valid(result.edit.container)
                }
            }
        }
        assertTrue("Only ${profiles.size} profiles tested", profiles.size > 40)
    }

    /**
     * The picker quotes a price before the pick, so the estimate has to be a floor that the
     * real edit never comes in under — and for a raster-backed widget it has to be the real
     * figure, since that is the class whose cost decides anything. A Value or a Composite
     * also carries a font binding and a dictionary slice the estimate cannot see, which is
     * why it is only a floor there.
     */
    @Test fun theRowEstimateIsAFloorAndIsExactForArtwork() {
        val target = face("00106")
        var artwork = 0
        samples.forEach { (id, index) ->
            val donor = face(id)
            val source = entry(donor)
            val estimate = requireNotNull(WidgetImporter.addedBytesEstimate(source, index)) {
                "no estimate for $id widget $index"
            }
            val exact = WidgetImporter.importWidget(target, "style0.bin", donor, "style0.bin", index)
                .edit.sizeDelta
            assertTrue("$id widget $index estimated $estimate over exact $exact", estimate <= exact)
            val record = FaceRecordParser.scanWidgets(source).single { it.globalIndex == index }
            val carriesText = record.widgetType == 5 || record.widgetType == 13
            if (!carriesText) {
                assertEquals("$id widget $index", exact, estimate)
                artwork++
            }
        }
        assertTrue("no artwork widgets in the sample", artwork > 0)
    }

    @Test fun fullFontTableRefusesNewBindingButStillAllowsIdenticalBindings() {
        val target = face("00106")
        val bindings = FaceResources.fontBindings(target)
        val replacements = FaceResources.variantEntries(target).associate { it.index to it.data.copyOf().also { data -> data[0x11] = 10 } }
        val additions = (bindings.size until 10).associate { "font_$it.bin" to bindings.first().data }
        val full = StructuralEditor.rebuild(target, replacements, additions).container
        val donor = face("00001")
        val binding = FaceRecordParser.scanWidgets(entry(donor))[1].fontBindingIndex!!
        assertFalse(FaceResources.fontBindings(full).any { it.data.contentEquals(donor.entryByBasename("font_$binding.bin").data) })
        val before = full.toByteArray()
        val error = assertThrows(Fit3FormatException::class.java) {
            WidgetImporter.importWidget(full, "style0.bin", donor, "style0.bin", 1)
        }
        assertTrue(error.message.orEmpty().contains("all 10"))
        assertArrayEquals(before, full.toByteArray())
        val nativeValue = FaceRecordParser.scanWidgets(entry(full)).first { it.widgetType == 5 }
        val same = WidgetImporter.importWidget(full, "style0.bin", full, "style0.bin", nativeValue.globalIndex)
        assertEquals(10, FaceResources.fontBindings(same.edit.container).size)
    }
}
