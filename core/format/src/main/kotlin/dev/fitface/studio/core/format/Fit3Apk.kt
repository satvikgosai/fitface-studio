package dev.fitface.studio.core.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

data class Fit3Apk(
    val faceId: String,
    val samplerId: Int?,
    val faceName: String?,
    val binaryMember: String,
    val binary: ByteArray,
    /**
     * The vendor's own rendered preview of each style, keyed by style index, as the
     * PNG bytes shipped in the package.
     *
     * These are the images the store and the watch's own picker show, at the full
     * 256 × 402 panel size, so they are the truthful answer to "which style is this?"
     * without opening a container at all. 98 of the 99 catalogue faces that carry a
     * container ship exactly one per `styleN.bin` with matching indices; face `00031`
     * ships none, so a caller must treat a missing index as "no preview" rather than
     * as a parse failure.
     */
    val stylePreviews: Map<Int, ByteArray>,
    private val members: List<Member>,
) {
    data class Member(
        val name: String,
        val method: Int,
        val time: Long,
        val comment: String?,
        val extra: ByteArray?,
        val isDirectory: Boolean,
        val data: ByteArray,
    )

    fun rebuildWithBinary(replacement: ByteArray): ByteArray {
        if (members.isEmpty()) {
            throw Fit3FormatException("APK members were not retained for rebuilding")
        }
        val output = ByteArrayOutputStream()
        var replaced = 0
        ZipOutputStream(output).use { zip ->
            members.forEach { member ->
                val payload = if (member.name == binaryMember) {
                    replaced++
                    replacement
                } else {
                    member.data
                }
                val entry = ZipEntry(member.name).apply {
                    method = member.method
                    if (member.time >= 0) time = member.time
                    comment = member.comment
                    extra = member.extra?.copyOf()
                    if (method == ZipEntry.STORED) {
                        size = payload.size.toLong()
                        compressedSize = payload.size.toLong()
                        crc = CRC32().apply { update(payload) }.value
                    }
                }
                zip.putNextEntry(entry)
                if (!member.isDirectory) zip.write(payload)
                zip.closeEntry()
            }
        }
        if (replaced != 1) {
            throw Fit3FormatException("expected one APK binary replacement, found $replaced")
        }
        val rebuilt = output.toByteArray()
        val reparsed = parse(rebuilt)
        if (!reparsed.binary.contentEquals(replacement)) {
            throw Fit3FormatException("rebuilt APK does not contain the requested binary")
        }
        return rebuilt
    }

    companion object {
        private val facePattern =
            Regex("""(?:^|/)SM-R390_(\d{5})_256x402\.bin$""")

        /**
         * `assets/SM-R390_<face>_<group>_<style>.png` — the package's style previews.
         *
         * Anchored at `assets/` on purpose: the same file names repeat under
         * `assets/<locale>/` for localised artwork, and those are not the defaults.
         */
        private val stylePreviewPattern =
            Regex("""^assets/SM-R390_\d{5}_\d{1,3}_(\d{1,3})\.png$""")

        fun parse(apkBytes: ByteArray, retainMembers: Boolean = true): Fit3Apk {
            val members = mutableListOf<Member>()
            try {
                ZipInputStream(ByteArrayInputStream(apkBytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val payload = if (entry.isDirectory) byteArrayOf() else zip.readBytes()
                        members += Member(
                            name = entry.name,
                            method = entry.method,
                            time = entry.time,
                            comment = entry.comment,
                            extra = entry.extra?.copyOf(),
                            isDirectory = entry.isDirectory,
                            data = payload,
                        )
                        zip.closeEntry()
                    }
                }
            } catch (error: Exception) {
                throw Fit3FormatException("APK is not a readable ZIP: ${error.message}", error)
            }
            val matches = members.mapNotNull { member ->
                facePattern.find(member.name)?.let { match -> member to match.groupValues[1] }
            }
            if (matches.isEmpty()) {
                // Some catalogue entries are companion/customisation apps rather than
                // watch-face content. The "Photos" face (00254) is the live example:
                // 601 members, none of them a container — the watch renders it from a
                // preinstalled face plus a photo the plugin pushes separately.
                throw Fit3NoContainerException(
                    members.any { it.name.startsWith("assets/bandface_info") },
                )
            }
            if (matches.size != 1) {
                throw Fit3FormatException(
                    "expected exactly one Fit3 watch-face binary, found ${matches.size}",
                )
            }
            val (member, faceId) = matches.single()
            val container = Fit3Container.parse(member.data)
            val report = container.validate()
            if (!report.isValid) {
                throw Fit3FormatException(
                    "embedded watch face is invalid: ${report.errors.joinToString { it.code }}",
                )
            }
            return Fit3Apk(
                faceId = faceId,
                samplerId = parseSamplerId(members),
                faceName = parseFaceName(members),
                binaryMember = member.name,
                binary = member.data.copyOf(),
                // Kept even when the members are dropped: they are a few kilobytes
                // each and they are the only style artwork available offline.
                stylePreviews = parseStylePreviews(members),
                members = if (retainMembers) members else emptyList(),
            )
        }

        private fun parseStylePreviews(members: List<Member>): Map<Int, ByteArray> = members
            .mapNotNull { member ->
                val index = stylePreviewPattern.matchEntire(member.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?: return@mapNotNull null
                index.takeIf { member.data.isNotEmpty() }?.let { it to member.data.copyOf() }
            }
            .toMap()

        private fun parseFaceName(members: List<Member>): String? = runCatching {
            val metadata = members.singleOrNull {
                it.name == "assets/bandface_info.json"
            } ?: return@runCatching null
            val names = Json.parseToJsonElement(metadata.data.decodeToString())
                .jsonObject["info"]
                ?.jsonObject
                ?.get("name")
                ?.jsonArray
                ?: return@runCatching null
            val localized = names.mapNotNull { item ->
                val value = item.jsonObject
                val text = value["__text"]?.jsonPrimitive?.content
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                value["_lang"]?.jsonPrimitive?.content to text
            }
            localized.firstOrNull { it.first == "en_US" }?.second
                ?: localized.firstOrNull()?.second
        }.getOrNull()

        private fun parseSamplerId(members: List<Member>): Int? = runCatching {
            val metadata = members.singleOrNull {
                it.name == "assets/bandface_info.json"
            } ?: return@runCatching null
            val thumbnail = Json.parseToJsonElement(metadata.data.decodeToString())
                .jsonObject["info"]
                ?.jsonObject
                ?.get("thumbnail")
                ?.jsonPrimitive
                ?.content
                ?: return@runCatching null
            Regex("""^SM-R390_\d{5}_\d{1,3}_(\d{1,3})\.png$""")
                .matchEntire(thumbnail)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it in 0..255 }
        }.getOrNull()
    }
}
