package dev.fitface.studio.core.data

import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.WidgetImageLayer

/** A current-record scene, shared by numbered styles and the always-on display. */
internal data class WidgetPreview(
    val composed: PreviewFrame,
    val widgetOverlay: PreviewFrame,
    val widgetImageLayers: List<WidgetImageLayer>,
    /** Substitute ROM glyphs, missing resources or unavailable source readings. */
    val isApproximate: Boolean,
)
