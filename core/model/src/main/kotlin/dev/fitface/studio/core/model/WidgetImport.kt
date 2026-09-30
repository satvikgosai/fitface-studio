package dev.fitface.studio.core.model

data class WidgetDonor(val handle: String, val faceId: String, val variants: List<EditorVariant>)

/**
 * One donor variant, as the picker shows it.
 *
 * [composed] is the donor face drawn from its own resources — the same picture the editor's
 * canvas draws for the face being edited. The repository already composed it to obtain
 * [layers]; carrying it lets the picker put the face itself on screen and hit-test
 * [widgets] on it, instead of describing each record in words.
 *
 * [addedBytes] is what a widget would cost the target, keyed by global index and near
 * enough to show on a row *before* it is picked. It is a floor, not a promise — see
 * `WidgetImporter.addedBytesEstimate`. The exact figure comes back with
 * [WidgetImportPreview.addedBytes] once the edit has actually been built.
 */
data class WidgetDonorVariant(
    val widgets: List<WidgetGuide>,
    val layers: List<WidgetImageLayer>,
    val unavailable: Map<Int, String>,
    val composed: PreviewFrame,
    val addedBytes: Map<Int, Int> = emptyMap(),
)

data class WidgetImportPreview(
    val ticket: String,
    val addedBytes: Int,
    val containerBytes: Int,
    val widget: WidgetGuide,
    val preview: PreviewFrame,
)
