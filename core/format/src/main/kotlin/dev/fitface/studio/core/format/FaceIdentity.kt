package dev.fitface.studio.core.format

import java.nio.charset.StandardCharsets

/**
 * A container under another five-digit face ID, at the same length.
 *
 * The watch knows a face by the ID in its entry paths and in `setting.bin +0x10`
 * (`docs/bin-format.md` §5), and [DeliveryChecks] refuses a container in which those disagree
 * with each other or with its package. This rewrites both, and the entry and header CRCs follow
 * from the rebuild. Nothing else changes.
 *
 * **The name slots keep the old name.** The watch never reads them, and these are exactly the
 * bytes T3 installed: Info_4 renumbered to `00201` and `00202`, its slots still reading
 * `SM-R390_00006_256x402`, both installed beside Info_4 and selectable. Rewriting them as well
 * would send a face no watch has been seen to take.
 */
object FaceIdentity {
    private val FIVE_DIGITS = Regex("""\d{5}""")
    private const val ID_OFFSET = 0x10

    fun renumber(container: Fit3Container, faceId: String): Fit3Container {
        require(FIVE_DIGITS.matches(faceId)) { "face ID $faceId is not five digits" }
        val setting = FaceResources.settingOrNull(container)
            ?: throw Fit3FormatException("the face has no setting.bin to renumber")
        val old = SettingRecord.parse(setting).faceId
        if (!FIVE_DIGITS.matches(old)) throw Fit3FormatException("setting.bin names face \"$old\"")
        val oldDirectory = "SM-R390_${old}_256x402/"
        val renamed = container.entries.associate { entry ->
            if (!entry.path.contains(oldDirectory)) {
                throw Fit3FormatException("${entry.path} is not under face $old's directory")
            }
            entry.index to entry.path.replace(oldDirectory, "SM-R390_${faceId}_256x402/")
        }
        val data = setting.data.copyOf()
        faceId.toByteArray(StandardCharsets.US_ASCII).copyInto(data, ID_OFFSET)
        return StructuralEditor.rebuild(container, mapOf(setting.index to data), renamedEntries = renamed).container
    }
}
