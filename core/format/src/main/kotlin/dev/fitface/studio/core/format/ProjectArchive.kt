package dev.fitface.studio.core.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An archive that is not one, or is one this build cannot read. */
class ProjectArchiveException(message: String, cause: Throwable? = null) :
    Fit3FormatException(message, cause)

/**
 * Everything about a project that is not already in the package it was started from.
 *
 * Written as `fitface/project.json`. Every field after the first release has to carry a
 * default for the reason `StoredRemovedWidget`'s do: an archive is read back from files
 * written by other builds, and a missing key throws — which here is a whole project that
 * will not import rather than one that imports without a detail.
 */
@Serializable
data class ProjectManifest(
    /**
     * What shape this file is in. Refused when it is higher than [ProjectArchive.Schema]:
     * a build that guessed at a newer layout would import a project silently missing
     * whatever the newer field was, and the reader would find out by looking at the face.
     */
    val schema: Int = 1,
    /** The name the project had. Deduplicated against the importing library on the way in. */
    val projectName: String,
    val faceId: String,
    /** The package's own file name, kept because the row has a column for it. */
    val displayName: String,
    val faceName: String? = null,
    /**
     * Provenance, exactly as the row held it. Null for a row that never had a parseable
     * one, and null has to read as "say nothing" here too.
     */
    val sourceUri: String? = null,
    val productId: String? = null,
    val packageVersionCode: Long? = null,
    val styleId: Int? = null,
    /** The style the editor was last left on. Never `aod.bin` — that is not a row's state. */
    val selectedStyle: String? = null,
    val exportedAtEpochMillis: Long = 0,
    /** The app version that wrote the file, for a bug report that arrives with one. */
    val exportedByVersion: String? = null,
)

/** What was read out of the `fitface/` sidecar. */
data class ProjectArchiveContents(
    val manifest: ProjectManifest,
    /** The edited container, or null for a project exported before it was edited. */
    val editedContainer: ByteArray?,
    /** The removed-widget records, or null when there were none to keep. */
    val sessionState: ByteArray?,
)

/**
 * A project as a file: the package members the app actually reads, under the names the
 * package gave them, plus the edit and the metadata that make it a project.
 *
 * The layout is the whole design, and it is chosen so that **the archive is a package**:
 *
 * ```
 * assets/SM-R390_00046_256x402.bin   the pristine container, byte for byte
 * assets/bandface_info.json          the face's name and its sampler id
 * assets/SM-R390_00046_2_0.png       the default style previews
 * fitface/project.json               the manifest above
 * fitface/edited.bin                 the current container, when there is an edit
 * fitface/session.json               the removed-widget records
 * ```
 *
 * [Fit3Apk.parse] reads that unchanged — one container matching its own name pattern,
 * metadata at the member it looks for, previews anchored at `assets/` — so on import the
 * archive is stored *as the project's `source.apk`* and every path downstream of that
 * needs no special case at all: opening it, duplicating it, extracting its style previews,
 * resolving the pristine container a resize resamples from, building the install payload.
 * Nothing in this app has to know a project came from a file rather than from the store.
 * That is why the entry names are the package's rather than something tidier: a tidier
 * name would have meant a second parser, and a second parser is a second set of the
 * pointer rules in `docs/bin-format.md`.
 *
 * What is left out is everything else — the dex, the resources, the manifest, the signature
 * block, the accessory JARs, and the `assets/<locale>/` copies of the previews, which are
 * localised artwork the parser deliberately does not read. Face `00046`'s package is 2.5 MiB
 * over 571 members; its archive is the container, four PNGs and a JSON.
 *
 * The `fitface/` prefix is safe to add because no package has one: every member of one is
 * under `assets/`, `res/`, `lib/`, `META-INF/`, or is a top-level `AndroidManifest.xml`,
 * `classes*.dex` or `resources.arsc`. And because [Fit3Apk.readsMember] refuses those three
 * names, packing an archive that was itself imported drops the old sidecar and writes a
 * fresh one instead of nesting them.
 */
object ProjectArchive {
    const val Schema = 2

    const val ManifestEntry = "fitface/project.json"
    const val EditedEntry = "fitface/edited.bin"
    const val SessionEntry = "fitface/session.json"

    /**
     * The most an archive may inflate to while it is being read.
     *
     * A legitimate one is two containers and a handful of PNGs: the container ceiling is
     * `WATCH_CONTAINER_BYTE_CEILING`, 4 MiB, and the largest package in the catalogue
     * carries 4,149,034 bytes of container and about 50 KiB of previews. 16 MiB is nearly
     * twice what two of the largest plus their previews can come to, and it has to stay
     * well under the heap rather than merely over the legitimate maximum: the archive is
     * held in memory while its members are inflated beside it, and a phone is where that
     * has to fit. Same reasoning, and the same shape, as `Fit3Apk.MAX_INFLATED_BYTES`.
     */
    const val MaxArchiveBytes = 16L * 1024 * 1024

    /**
     * The most entries [read] will walk before giving up.
     *
     * An empty zip entry costs about 76 bytes across its local header and central directory,
     * so a file at [MaxArchiveBytes] can declare roughly 220,000 of them — each one a
     * `nextEntry` and a name comparison. That is a slow loop rather than a hang, but it is a
     * loop this has no reason to run: a real archive holds five to eight members, and
     * `Fit3Apk.parse`, which refuses past 4,096, does not run until after this. The two
     * limits are deliberately different — that one bounds a vendor package of 571 members,
     * this one bounds a file this app wrote itself.
     */
    private const val MaxEntries = 1_024

    private val SidecarEntries = setOf(ManifestEntry, EditedEntry, SessionEntry)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Copies the members of [source] that [Fit3Apk.readsMember] accepts into [destination],
     * under exactly the names they had, and appends the sidecar.
     *
     * Streamed rather than parsed: a package is up to 32 MiB and [Fit3Apk.parse] holds every
     * inflated member at once, which is the allocation this feature has no reason to make.
     * One member is in memory at a time, and the largest of those is the container.
     *
     * The container count is checked *after* the copy rather than trusted from the caller.
     * An archive with no container is one [Fit3Apk.parse] rejects as an uneditable package
     * on the way back in, and an archive with two is one it rejects outright — either way
     * the reader would be told at import time about a file that was already wrong when it
     * was written, with the project it came from long gone.
     *
     * Both streams are closed, including [destination]: the caller's is a document the
     * system picker opened, and a zip whose central directory was never flushed is a file
     * that looks like an export and is not one.
     */
    fun pack(
        source: InputStream,
        destination: OutputStream,
        manifest: ProjectManifest,
        editedContainer: ByteArray?,
        sessionState: ByteArray?,
    ) {
        require(manifest.schema in 1..Schema) {
            "a manifest is written at schema $Schema, not ${manifest.schema}"
        }
        require(manifest.schema != 2 || (editedContainer != null && sessionState != null)) {
            "imported-widget archives require both the edit and its artwork metadata"
        }
        var containers = 0
        try {
            ZipOutputStream(destination).use { out ->
                ZipInputStream(source).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (!entry.isDirectory && Fit3Apk.readsMember(entry.name)) {
                            if (Fit3Apk.isFaceBinary(entry.name)) containers++
                            out.putNextEntry(ZipEntry(entry.name))
                            input.copyTo(out)
                            out.closeEntry()
                        }
                        input.closeEntry()
                    }
                }
                if (containers != 1) {
                    throw ProjectArchiveException(
                        "the package holds $containers watch-face containers, not one",
                    )
                }
                out.write(ManifestEntry, json.encodeToString(manifest).encodeToByteArray())
                editedContainer?.let { out.write(EditedEntry, it) }
                // Only ever beside an edit. The records name widgets cut out of the edited
                // container, so on their own they describe a face nothing removed anything
                // from — `restoreSessionState` is not even called without one.
                sessionState?.takeIf { editedContainer != null }?.let { out.write(SessionEntry, it) }
            }
        } catch (error: ProjectArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ProjectArchiveException("the project could not be written: ${error.message}", error)
        }
    }

    /**
     * The sidecar out of [bytes], with the same bounds a package parse has.
     *
     * The package half is not re-read here — [Fit3Apk.parse] is the reader for that, and
     * running it separately is what keeps this from becoming a second container parser.
     */
    fun read(bytes: ByteArray): ProjectArchiveContents {
        var manifestBytes: ByteArray? = null
        var edited: ByteArray? = null
        var session: ByteArray? = null
        var budget = MaxArchiveBytes
        var entries = 0
        val seen = mutableSetOf<String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (++entries > MaxEntries) {
                        throw ProjectArchiveException(
                            "the archive holds more than $MaxEntries members",
                        )
                    }
                    if (!entry.isDirectory && entry.name in SidecarEntries) {
                        // A repeat is refused rather than resolved. A zip may carry the same
                        // name twice, and then "which manifest is the real one" is decided by
                        // whichever the reader happens to take — first or last, both
                        // defensible, neither knowable from the file. Two containers are
                        // refused by `Fit3Apk.parse` for the same reason.
                        if (!seen.add(entry.name)) {
                            throw ProjectArchiveException(
                                "the archive holds ${entry.name} more than once",
                            )
                        }
                        val payload = readBounded(input, budget)
                        budget -= payload.size
                        when (entry.name) {
                            ManifestEntry -> manifestBytes = payload
                            EditedEntry -> edited = payload
                            SessionEntry -> session = payload
                        }
                    }
                    input.closeEntry()
                }
            }
        } catch (error: ProjectArchiveException) {
            // Already the right shape and the right message; the branch below would rewrite
            // "this archive inflates past the limit" as "not a readable zip".
            throw error
        } catch (error: Exception) {
            throw ProjectArchiveException("that file is not a readable zip: ${error.message}", error)
        }
        val encoded = manifestBytes ?: throw ProjectArchiveException(
            "that file holds no exported project",
        )
        val manifest = try {
            json.decodeFromString<ProjectManifest>(encoded.decodeToString())
        } catch (error: Exception) {
            throw ProjectArchiveException(
                "the exported project's details could not be read: ${error.message}",
                error,
            )
        }
        // Refused rather than read hopefully. `ignoreUnknownKeys` means a newer archive
        // decodes without complaint while quietly dropping whatever the new field was, and
        // the reader would find that out by looking at the face rather than by being told.
        if (manifest.schema !in 1..Schema) {
            throw ProjectArchiveException(
                "that project was exported by a newer version of this app",
            )
        }
        if (manifest.schema == 2 && (edited == null || session == null))
            throw ProjectArchiveException("that project's imported artwork or saved edit is missing")
        // A session with no edit describes removals from a container nothing removed
        // anything from. `pack` will not write that pair; an archive that carries it was
        // assembled by hand, and dropping the half that cannot apply is the honest reading.
        return ProjectArchiveContents(
            manifest = manifest,
            editedContainer = edited,
            sessionState = session?.takeIf { edited != null },
        )
    }

    private fun ZipOutputStream.write(name: String, payload: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(payload)
        closeEntry()
    }

    /**
     * One member, refusing to inflate past what is left of the budget.
     *
     * The same guard, for the same reason, as `Fit3Apk.readBounded`: `readBytes()` inflates
     * whatever the entry claims, so a file bounded only by its compressed size can exhaust
     * the heap — and `OutOfMemoryError` is an `Error`, which the `catch (Exception)` around
     * the loop would not have caught either.
     */
    private fun readBounded(input: InputStream, budget: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = budget
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            remaining -= count
            if (remaining < 0) {
                throw ProjectArchiveException(
                    "the archive inflates past the ${MaxArchiveBytes / (1024 * 1024)} MiB limit",
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
