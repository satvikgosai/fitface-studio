package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.CUSTOM_FACE_TEMPLATE_FACE_ID

/**
 * A starting point for a face of one's own: face `00006`'s clock on an empty panel.
 *
 * **Built on the phone, never shipped.** `NOTICE.md` promises that no watch-face container,
 * raster, font or preview is bundled in the app or committed here, and a template shipped as
 * an asset would be exactly that — the vendor's digit artwork inside every published APK. So
 * the template is made at the reader's request from the package the store serves them, like
 * any other face they download, and it lives only in the app's private storage.
 *
 * The recipe keeps **one** style — the face's four differ only in which readings they show,
 * so once those are gone the other three are copies with nothing to choose between — and
 * removes those readings from it through [StructuralEditor.removeWidget], the same edit the
 * tray's ✕ makes. What is left is by construction something a reader could have made by
 * hand, bar the style count, which no edit in the app changes. Face `00006` suits it because nothing on it is anchored to
 * anything — every alignment reference names no record — so the removals are allowed in any
 * order; its eleven rasters a style all belong to the clock, so stripping leaves no dead
 * weight; and its digit glyphs carry alpha, so a photo added behind them shows between the
 * strokes rather than as a row of boxes. `aod.bin` already holds just the clock.
 *
 * **It refuses anything but the shape it was written for.** The store serves the newest
 * version of a face, so a later one could put different widgets at these indices. Every
 * style is checked against the expected record types first, and a face that does not match
 * is refused with a sentence rather than stripped of the wrong things.
 */
object CustomFaceTemplate {
    const val FACE_ID = CUSTOM_FACE_TEMPLATE_FACE_ID

    /**
     * Bumped whenever the recipe changes, so a project records which one made it. `1` kept
     * all four styles; `2` keeps one.
     */
    const val VERSION = 2

    /**
     * The nine records every numbered style carries, by index: four digit sprites, the two
     * readings, the colon, and the two readings' labels.
     */
    private val expectedTypes = listOf(
        WIDGET_SPRITE, WIDGET_SPRITE, WIDGET_SPRITE, WIDGET_SPRITE,
        WIDGET_PAIR, WIDGET_PAIR, WIDGET_STATIC, WIDGET_PAIR, WIDGET_PAIR,
    )

    /**
     * The readings and their labels, **highest index first**: removing a record renumbers
     * every one after it, so taking them from the top leaves each index still to go naming
     * the record it was chosen as.
     */
    private val removed = listOf(8, 7, 5, 4)

    /** What a stripped style holds: the four digits and the colon, in their shipped order. */
    private val keptTypes = listOf(WIDGET_SPRITE, WIDGET_SPRITE, WIDGET_SPRITE, WIDGET_SPRITE, WIDGET_STATIC)

    /** The style the template keeps. The watch numbers styles from here, so it is the only choice. */
    private const val KEPT_STYLE = "style0.bin"

    /** One style, reduced to its clock; AOD is left alone. */
    fun strip(source: Fit3Container): Fit3Container {
        val kept = FaceResources.selectableStyles(source).firstOrNull { it.basename == KEPT_STYLE }
            ?: throw Fit3TemplateMismatchException("face $FACE_ID has no $KEPT_STYLE")
        val types = FaceRecordParser.scanWidgets(kept).map { it.widgetType }
        if (types != expectedTypes) {
            throw Fit3TemplateMismatchException(
                "$KEPT_STYLE holds widget types $types, not the $expectedTypes this template " +
                    "was written for",
            )
        }
        // The styles go first, so the removals below rebuild a container a third the size.
        var current = StructuralEditor.keepFirstStyles(source, 1).container
        removed.forEach { index ->
            val record = FaceRecordParser.scanWidgets(current.entryByBasename(KEPT_STYLE))
                .single { it.globalIndex == index }
            current = StructuralEditor.removeWidget(
                source = current,
                entryBasenames = listOf(KEPT_STYLE),
                globalIndex = record.globalIndex,
                widgetType = record.widgetType,
                sequenceId = record.sequenceId,
                x = record.x,
                y = record.y,
                requireFinal = false,
            ).container
        }
        val left = FaceRecordParser.scanWidgets(current.entryByBasename(KEPT_STYLE)).map { it.widgetType }
        check(left == keptTypes) { "$KEPT_STYLE was stripped to $left" }
        return current
    }
}

/**
 * The template's package: what [Fit3Apk.parse] reads out of the store's package, and nothing
 * else — the container swapped for [container] and each default style preview for the
 * matching entry of [previews], the face's own metadata carried over unchanged. A style
 * preview with no entry in [previews] is **dropped**: it belongs to a style the template no
 * longer has, and a package preview for a missing style is a picture of nothing.
 *
 * The same three member shapes a project archive holds, for the same reason: this becomes the
 * project's `source.apk`, and a package that holds exactly what the parser reads is one that
 * opening, duplicating, exporting and installing all accept without knowing where it came
 * from.
 */
fun CustomFaceTemplate.pack(
    storePackage: ByteArray,
    container: ByteArray,
    previews: Map<Int, ByteArray>,
): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    var containers = 0
    java.util.zip.ZipOutputStream(output).use { out ->
        java.util.zip.ZipInputStream(storePackage.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory || !Fit3Apk.readsMember(entry.name)) {
                    input.closeEntry()
                    continue
                }
                val original = input.readBytes()
                val preview = Fit3Apk.stylePreviewIndex(entry.name)
                val payload = when {
                    Fit3Apk.isFaceBinary(entry.name) -> container.also { containers++ }
                    preview != null -> previews[preview]
                    else -> original
                }
                if (payload == null) {
                    input.closeEntry()
                    continue
                }
                out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                out.write(payload)
                out.closeEntry()
                input.closeEntry()
            }
        }
    }
    if (containers != 1) {
        throw Fit3FormatException("the store's package holds $containers watch-face containers, not one")
    }
    val packed = output.toByteArray()
    val reparsed = Fit3Apk.parse(packed, retainMembers = false)
    check(reparsed.faceId == FACE_ID && reparsed.binary.contentEquals(container) &&
        previews.keys.containsAll(reparsed.stylePreviews.keys)) {
        "the template package does not read back as the template"
    }
    return packed
}

/** The store served a version of the template's face that the recipe was not written for. */
class Fit3TemplateMismatchException(message: String) : Fit3FormatException(message)
