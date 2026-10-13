package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.WATCH_MAX_STYLES
import java.nio.charset.StandardCharsets

/**
 * What a container has to say about itself, consistently, before it is sent.
 *
 * [Fit3Container.validate] proves a container is well formed; this proves it is the face it
 * claims to be. The watch reads `setting.bin`'s face ID, style count and default style
 * (`docs/bin-format.md` §5) and extracts every entry by its path, so a container whose paths,
 * `setting.bin` and package disagree about which face it is, or whose declared style count is
 * not the number of styles it carries, is not something to send however valid its bytes.
 * An older R390 Studio export, repackaged into a project archive, is exactly that: count zero
 * in `setting.bin`, which Fit3-App reports breaking installed faces.
 *
 * Every check holds for all 99 catalogue containers and for every edit this app makes,
 * including the custom template and style deletion, so it refuses only input the app did not
 * produce. Empty styles are reported separately and do not refuse: removing every widget is
 * legal while editing, and whether the watch draws an empty style is still to be tested.
 */
object DeliveryChecks {
    const val CONTAINER_VERSION = 4L
    const val MAX_STYLES = WATCH_MAX_STYLES

    private const val STRUCT_MAGIC = 0x12345678L
    private val SETTING_MARKER = "LQ_WF".toByteArray(StandardCharsets.US_ASCII)
    private val ENTRY_PATH = Regex("""\./SM-R390_(\d{5})_256x402/[^/]+""")
    private val REQUIRED = listOf("setting.bin", "preview.bin", "aod.bin", "style0.bin")

    data class Refusal(val code: String, val detail: String)

    data class Report(
        val refusals: List<Refusal>,
        /** Styles, and the always-on display, that carry no widgets. */
        val emptyVariants: List<String>,
    ) {
        val canSend: Boolean get() = refusals.isEmpty()
    }

    /** [expectedFaceId] is the five-digit ID the package's container member is named for. */
    fun check(container: Fit3Container, expectedFaceId: String): Report {
        val refusals = mutableListOf<Refusal>()
        fun refuse(code: String, detail: String) {
            refusals += Refusal(code, detail)
        }

        if (container.header.version != CONTAINER_VERSION) {
            refuse("container_version", "version ${container.header.version}, expected $CONTAINER_VERSION")
        }
        REQUIRED.forEach { name ->
            val count = container.entries.count { it.basename == name }
            if (count != 1) refuse("required_entry", "$name present $count times")
        }

        val pathIds = container.entries.map { entry ->
            ENTRY_PATH.matchEntire(entry.path)?.groupValues?.get(1).also {
                if (it == null) refuse("entry_path", entry.path)
            }
        }
        val faceId = pathIds.filterNotNull().distinct().singleOrNull()
        if (faceId == null) {
            refuse("face_id_paths", "entry paths name ${pathIds.filterNotNull().distinct()}")
        } else if (faceId != expectedFaceId) {
            refuse("face_id_package", "entry paths name $faceId, the package $expectedFaceId")
        }

        val styles = FaceResources.selectableStyles(container)
        if (styles.map { FaceResources.styleNumber(it.basename) } != styles.indices.toList()) {
            refuse("style_numbering", styles.joinToString { it.basename })
        }
        if (styles.size > MAX_STYLES) {
            refuse("too_many_styles", "${styles.size} styles, at most $MAX_STYLES")
        }

        FaceResources.settingOrNull(container)?.let { entry ->
            checkSetting(entry, faceId, styles.size, ::refuse)
        }

        val empty = FaceResources.variantEntries(container).filter { entry ->
            runCatching { StyleHeader.parse(entry).widgetCount == 0 }.getOrDefault(false)
        }.map { it.basename }
        return Report(refusals, empty)
    }

    private fun checkSetting(
        entry: ContainerEntry,
        faceId: String?,
        styleCount: Int,
        refuse: (String, String) -> Unit,
    ) {
        val record = runCatching { SettingRecord.parse(entry) }.getOrElse {
            refuse("setting_unreadable", it.message ?: "unreadable")
            return
        }
        val data = entry.data
        if (!data.copyOfRange(0, SETTING_MARKER.size).contentEquals(SETTING_MARKER) ||
            data.u32(0x0C) != STRUCT_MAGIC
        ) {
            refuse("setting_marker", "setting.bin does not open with LQ_WF and its struct magic")
        }
        if (faceId != null && record.faceId != faceId) {
            refuse("face_id_setting", "setting.bin names ${record.faceId}, the entry paths $faceId")
        }
        // The two name slots at +0x38/+0x78 are deliberately not checked: the watch never reads
        // them, and `00018` stores "00018_Overall image(Large)" there rather than its directory
        // name, so a canonical-name rule would refuse a vendor face.
        if (record.styleCount != styleCount) {
            refuse("style_count_mismatch", "setting.bin declares ${record.styleCount} styles, the face carries $styleCount")
        }
        if (styleCount > 0 && record.defaultStyle >= styleCount) {
            refuse("default_style_out_of_range", "setting.bin selects style ${record.defaultStyle} of $styleCount")
        }
    }
}
