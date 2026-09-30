package dev.fitface.studio.core.model

/** The actual primary background resource; null when a donor paints directly onto black. */
data class BackgroundDonorVariant(val background: PreviewFrame?, val fullPanelImageCount: Int)

/** A prepared, validated candidate bound to one project revision and donor variant. */
data class BackgroundImportPreview(
    val ticket: String,
    val preview: PreviewFrame,
    val changedVariants: List<String>,
    val skippedVariants: List<String>,
    val addedBackground: Boolean,
    val addedBytes: Int,
    val containerBytes: Int,
)
