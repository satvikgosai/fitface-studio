package dev.fitface.studio.core.format

import java.nio.charset.StandardCharsets

const val CONTAINER_HEADER_SIZE = 32
const val DIRECTORY_ENTRY_SIZE = 74
const val DIRECTORY_PATH_SIZE = 64

open class Fit3FormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * The package parses as a ZIP and may even carry watch-face metadata, but it holds
 * no `SM-R390_<id>_256x402.bin` container — so there is nothing this app can edit.
 *
 * [hasFaceMetadata] distinguishes a customisation companion (metadata present, the
 * watch renders the face itself) from an unrelated file.
 */
class Fit3NoContainerException(val hasFaceMetadata: Boolean) : Fit3FormatException(
    if (hasFaceMetadata) {
        "package carries watch-face metadata but no editable container"
    } else {
        "package contains no Fit3 watch-face container"
    },
)

data class ContainerHeader(
    val magic: String,
    val version: Long,
    val payloadSize: Int,
    val entryCount: Int,
    val checksum: Int,
    val checksumUpper: ByteArray,
    val reserved: ByteArray,
)

/** Panel geometry a container declares for itself, in pixels. */
data class PanelSize(val width: Int, val height: Int)

data class ContainerEntry(
    val index: Int,
    val path: String,
    val offset: Int,
    val size: Int,
    val checksum: Int,
    val rawRecord: ByteArray,
    val data: ByteArray,
) {
    val end: Int get() = offset + size
    val basename: String get() = path.substringAfterLast('/')

    /**
     * Panel geometry from the directory this entry lives in, e.g.
     * `./SM-R390_00046_256x402/style0.bin` → 256 × 402.
     *
     * This — not the first raster in the style — is the editor's coordinate space.
     * A style does not have to carry a full-panel background raster at all: face
     * `00022` opens with a 37×28 icon as its first raster and `00108` style0 with a
     * 204×204 one, and every `aod.bin` in the corpus starts with a digit sprite.
     * Sizing the canvas from raster 0 shrinks those faces to the icon and then
     * classifies every widget bigger than it as the background layer, which is how
     * they came to render with no selection border.
     */
    val declaredPanelSize: PanelSize?
        get() = PANEL_GEOMETRY.find(path)?.destructured?.let { (width, height) ->
            val parsedWidth = width.toIntOrNull() ?: return null
            val parsedHeight = height.toIntOrNull() ?: return null
            PanelSize(parsedWidth, parsedHeight).takeIf {
                it.width in 1..MAX_PANEL_EXTENT && it.height in 1..MAX_PANEL_EXTENT
            }
        }

    private companion object {
        /** Matches the `_<width>x<height>` suffix of the container directory name. */
        val PANEL_GEOMETRY = Regex("""_(\d{1,5})x(\d{1,5})(?:/|$)""")
        const val MAX_PANEL_EXTENT = 4096
    }
}

data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val entryIndex: Int? = null,
) {
    enum class Severity { ERROR, WARNING }
}

data class ValidationReport(val issues: List<ValidationIssue>) {
    val errors: List<ValidationIssue> =
        issues.filter { it.severity == ValidationIssue.Severity.ERROR }
    val warnings: List<ValidationIssue> =
        issues.filter { it.severity == ValidationIssue.Severity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()
}

class Fit3Container private constructor(
    val header: ContainerHeader,
    val entries: List<ContainerEntry>,
    val bodyOffset: Int,
    private val source: ByteArray,
) {
    val fileSize: Int get() = source.size

    fun toByteArray(): ByteArray = source.copyOf()

    fun entryByBasename(basename: String): ContainerEntry {
        val matches = entries.filter { it.basename == basename }
        if (matches.size != 1) {
            throw Fit3FormatException(
                "expected one entry named $basename, found ${matches.size}",
            )
        }
        return matches.single()
    }

    fun validate(): ValidationReport = validateContainer(this, source)

    companion object {
        fun parse(input: ByteArray): Fit3Container {
            if (input.size < CONTAINER_HEADER_SIZE) {
                throw Fit3FormatException(
                    "truncated header: need $CONTAINER_HEADER_SIZE bytes, got ${input.size}",
                )
            }
            val source = input.copyOf()
            val magic = String(source, 0, 4, StandardCharsets.US_ASCII)
            val entryCount = source.u32(12).checkedInt("entry count")
            val directorySize = try {
                Math.multiplyExact(entryCount, DIRECTORY_ENTRY_SIZE)
            } catch (error: ArithmeticException) {
                throw Fit3FormatException("directory size overflow", error)
            }
            val bodyOffset = try {
                Math.addExact(CONTAINER_HEADER_SIZE, directorySize)
            } catch (error: ArithmeticException) {
                throw Fit3FormatException("directory end overflow", error)
            }
            if (bodyOffset > source.size) {
                throw Fit3FormatException(
                    "truncated directory: $entryCount entries require $directorySize bytes",
                )
            }

            val header = ContainerHeader(
                magic = magic,
                version = source.u32(4),
                payloadSize = source.u32(8).checkedInt("payload size"),
                entryCount = entryCount,
                checksum = source.u16(16),
                checksumUpper = source.copyOfRange(18, 20),
                reserved = source.copyOfRange(20, 32),
            )
            val entries = buildList(entryCount) {
                repeat(entryCount) { index ->
                    val recordOffset = CONTAINER_HEADER_SIZE + index * DIRECTORY_ENTRY_SIZE
                    val rawRecord =
                        source.copyOfRange(recordOffset, recordOffset + DIRECTORY_ENTRY_SIZE)
                    val pathEnd = (0 until DIRECTORY_PATH_SIZE)
                        .firstOrNull { rawRecord[it] == 0.toByte() }
                        ?: DIRECTORY_PATH_SIZE
                    val path = String(rawRecord, 0, pathEnd, StandardCharsets.UTF_8)
                    val offset = rawRecord.u32(DIRECTORY_PATH_SIZE)
                        .checkedInt("entry $index offset")
                    val size = rawRecord.u32(DIRECTORY_PATH_SIZE + 4)
                        .checkedInt("entry $index size")
                    val end = offset.toLong() + size.toLong()
                    val payload = if (offset <= source.size) {
                        source.copyOfRange(offset, minOf(end, source.size.toLong()).toInt())
                    } else {
                        byteArrayOf()
                    }
                    add(
                        ContainerEntry(
                            index = index,
                            path = path,
                            offset = offset,
                            size = size,
                            checksum = rawRecord.u16(DIRECTORY_PATH_SIZE + 8),
                            rawRecord = rawRecord,
                            data = payload,
                        ),
                    )
                }
            }
            return Fit3Container(header, entries, bodyOffset, source)
        }
    }
}

private fun validateContainer(
    container: Fit3Container,
    source: ByteArray,
): ValidationReport {
    val issues = mutableListOf<ValidationIssue>()

    fun add(
        severity: ValidationIssue.Severity,
        code: String,
        message: String,
        entryIndex: Int? = null,
    ) {
        issues += ValidationIssue(severity, code, message, entryIndex)
    }

    val header = container.header
    if (header.magic != "oppo") {
        add(ValidationIssue.Severity.ERROR, "bad_magic", "expected oppo, got ${header.magic}")
    }
    if (header.payloadSize != source.size - CONTAINER_HEADER_SIZE) {
        add(
            ValidationIssue.Severity.ERROR,
            "bad_payload_size",
            "stored ${header.payloadSize}, expected ${source.size - CONTAINER_HEADER_SIZE}",
        )
    }
    if (header.entryCount != container.entries.size) {
        add(
            ValidationIssue.Severity.ERROR,
            "entry_count_mismatch",
            "stored ${header.entryCount}, parsed ${container.entries.size}",
        )
    }
    if (header.checksumUpper.any { it != 0.toByte() }) {
        add(
            ValidationIssue.Severity.WARNING,
            "nonzero_checksum_upper",
            "unrecognized checksum bytes are nonzero",
        )
    }
    if (header.reserved.any { it != 0.toByte() }) {
        add(
            ValidationIssue.Severity.WARNING,
            "nonzero_header_reserved",
            "unrecognized header bytes are nonzero",
        )
    }
    val expectedContainerCrc =
        Crc16.ccittFalse(source, CONTAINER_HEADER_SIZE, source.size)
    if (header.checksum != expectedContainerCrc) {
        add(
            ValidationIssue.Severity.ERROR,
            "bad_container_crc",
            "stored 0x${header.checksum.toString(16)}, " +
                "expected 0x${expectedContainerCrc.toString(16)}",
        )
    }

    val spans = mutableListOf<Triple<Int, Int, Int>>()
    container.entries.forEach { entry ->
        if (entry.rawRecord.copyOfRange(0, DIRECTORY_PATH_SIZE).none { it == 0.toByte() }) {
            add(
                ValidationIssue.Severity.WARNING,
                "unterminated_path",
                "64-byte path field has no NUL terminator",
                entry.index,
            )
        }
        if (entry.offset < container.bodyOffset) {
            add(
                ValidationIssue.Severity.ERROR,
                "entry_before_body",
                "offset ${entry.offset} precedes body ${container.bodyOffset}",
                entry.index,
            )
        }
        val end = entry.offset.toLong() + entry.size.toLong()
        if (end > source.size) {
            add(
                ValidationIssue.Severity.ERROR,
                "entry_out_of_bounds",
                "range ${entry.offset}..$end exceeds ${source.size}",
                entry.index,
            )
        } else if (entry.offset <= source.size) {
            spans += Triple(entry.offset, end.toInt(), entry.index)
            val expected = Crc16.ccittFalse(source, entry.offset, end.toInt())
            if (entry.checksum != expected) {
                add(
                    ValidationIssue.Severity.ERROR,
                    "bad_entry_crc",
                    "stored 0x${entry.checksum.toString(16)}, " +
                        "expected 0x${expected.toString(16)}",
                    entry.index,
                )
            }
        }
    }

    var previousEnd = container.bodyOffset
    spans.sortedBy { it.first }.forEach { (start, end, index) ->
        when {
            start < previousEnd -> add(
                ValidationIssue.Severity.ERROR,
                "overlapping_entry",
                "entry starts at $start before previous end $previousEnd",
                index,
            )
            start > previousEnd -> add(
                ValidationIssue.Severity.WARNING,
                "unreferenced_gap",
                "${start - previousEnd} unreferenced bytes before entry",
                index,
            )
        }
        previousEnd = maxOf(previousEnd, end)
    }
    if (spans.isNotEmpty() && previousEnd < source.size) {
        add(
            ValidationIssue.Severity.WARNING,
            "trailing_bytes",
            "${source.size - previousEnd} unreferenced bytes after final entry",
        )
    } else if (spans.isEmpty() && source.size > container.bodyOffset) {
        add(
            ValidationIssue.Severity.WARNING,
            "unreferenced_body",
            "${source.size - container.bodyOffset} body bytes with no entries",
        )
    }
    return ValidationReport(issues)
}
