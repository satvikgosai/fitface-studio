package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.AOD_ENTRY_NAME
import dev.fitface.studio.core.model.BackgroundDonorVariant
import dev.fitface.studio.core.model.PreviewFrame

/** Copies only the primary panel raster, never the donor's composed face or widget table. */
object BackgroundImporter {
    fun read(entry: ContainerEntry): BackgroundDonorVariant {
        StyleWidgetMatch.requireVariantEntry(entry)
        val panel = FaceRecordParser.panelSize(entry)
        val backgrounds = FaceRecordParser.scanImages(entry).filter {
            it.width == panel.width && it.height == panel.height
        }
        val image = FaceRecordParser.backgroundImage(entry)
        return BackgroundDonorVariant(image?.let { FaceRecordParser.decodeImage(entry, it) }, backgrounds.size)
    }

    fun prepare(target: Fit3Container, targetVariant: String, donor: ContainerEntry): BackgroundImportEdit {
        StyleWidgetMatch.requireVariantEntry(target.entryByBasename(targetVariant))
        val background = read(donor).background
            ?: throw Fit3FormatException("This source style has no background image. Choose another style.")
        val scope = if (targetVariant == AOD_ENTRY_NAME) listOf(target.entryByBasename(AOD_ENTRY_NAME))
            else FaceResources.variantEntries(target).filter { it.basename != AOD_ENTRY_NAME }
        val existing = scope.filter { FaceRecordParser.backgroundImage(it) != null }
        val adding = existing.isEmpty()
        val targets = if (adding) StructuralEditor.backgroundStylesThatFit(target,
            scope.map { it.basename }, targetVariant) else existing.map { it.basename }
        if (targets.isEmpty()) throw Fit3CapacityException(target.fileSize, target.fileSize +
            StructuralEditor.addedBackgroundBytes(background.width, background.height))
        targets.forEach { name ->
            val panel = FaceRecordParser.panelSize(target.entryByBasename(name))
            if (panel.width != background.width || panel.height != background.height)
                throw Fit3FormatException("The source background has a different panel size. Choose another face.")
        }
        // A donor mask is not a destination mask. Match picked photos: flatten its colour
        // against black, then let the destination encoder retain its own RGB565+A mask.
        val pixels = flattenOnBlack(background)
        val edit = if (adding) StructuralEditor.addBackgrounds(target, targets,
            background.width, background.height, pixels) else {
            val replacement = FaceEditor.replaceBackgrounds(target, targets,
                background.width, background.height, pixels)
            StructuralEdit(replacement.container, replacement.changedPayloadBytes, replacement.changedStyles, 0)
        }
        return BackgroundImportEdit(edit, adding, scope.map { it.basename }.filterNot { it in targets })
    }

    internal fun flattenOnBlack(frame: PreviewFrame): IntArray = frame.argb.map { color ->
        val alpha = color ushr 24
        fun channel(shift: Int) = (((color ushr shift and 255) * alpha + 127) / 255) shl shift
        0xFF000000.toInt() or channel(16) or channel(8) or channel(0)
    }.toIntArray()
}

data class BackgroundImportEdit(val edit: StructuralEdit, val addedBackground: Boolean,
    val skippedVariants: List<String>)
