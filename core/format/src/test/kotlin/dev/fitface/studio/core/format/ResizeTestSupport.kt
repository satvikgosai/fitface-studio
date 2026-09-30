package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WidgetGuide

/**
 * [StructuralEditor.resizeWidget] selected the way these tests know a widget.
 *
 * The editor names a widget by its global index *and* its type, data source and stored
 * position, because none of those is an identity on its own — a structural edit renumbers
 * the table, and a Static's data source is `0` in 678 of the catalogue's 681 records. That
 * is the right contract for the app and a tedious one for a test that knows only "the
 * sprite following the minute tens", so these two helpers close the gap.
 *
 * [resizeBySource] resolves the record **in the container being edited**, which is what a
 * test that has already added a background or removed a widget needs: the guide it captured
 * from the pristine container names a different index by then.
 */
internal fun resizeBySource(
    source: Fit3Container,
    entryBasenames: List<String>,
    sequenceId: Int,
    width: Int,
    height: Int,
    widgetType: Int = WIDGET_SPRITE,
    pristine: Fit3Container? = null,
): StructuralEdit {
    val entry = source.entryByBasename(entryBasenames.first())
    val guide = FaceRecordParser.widgetGuides(entry)
        .single { it.type == widgetType && it.sequenceId == sequenceId }
    return resizeGuide(source, entryBasenames, guide, width, height, pristine)
}

/** As [resizeBySource], for a guide already read from the container being edited. */
internal fun resizeGuide(
    source: Fit3Container,
    entryBasenames: List<String>,
    guide: WidgetGuide,
    width: Int,
    height: Int,
    pristine: Fit3Container? = null,
): StructuralEdit = StructuralEditor.resizeWidget(
    source = source,
    entryBasenames = entryBasenames,
    globalIndex = guide.globalIndex,
    widgetType = guide.type,
    sequenceId = guide.sequenceId,
    x = guide.x,
    y = guide.y,
    width = width,
    height = height,
    pristine = pristine,
)
