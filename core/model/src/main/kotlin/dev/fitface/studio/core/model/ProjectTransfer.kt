package dev.fitface.studio.core.model

/**
 * What a project archive is called and what MIME type it carries.
 *
 * Here rather than beside the packer in `:core:format` for the reason [AOD_ENTRY_NAME] is
 * here: the UI needs both — a name to suggest in the create-document picker, a type to
 * filter the open-document picker with — and the UI modules cannot see `:core:format` at
 * all. A local copy of either string is how the picker ends up offering `.zip` for a file
 * the packer writes under another name.
 */
object ProjectArchiveNaming {
    /**
     * `application/zip`, because that is what the file is.
     *
     * Not a private type of the app's own: the archive is deliberately a plain zip so it can
     * be opened and inspected with anything, and a made-up MIME type would only stop the
     * system picker from offering the file back. Some providers report a zip as
     * `application/octet-stream` regardless — one that arrived by chat or sits on a USB
     * volume routinely does — which is why the import picker asks for both. Filtering to the
     * exact type greys out the file the reader is looking straight at.
     */
    const val MimeType = "application/zip"

    val ImportMimeTypes = arrayOf(MimeType, "application/octet-stream")

    private const val Extension = ".zip"
    private const val SlugLimit = 40
    private val Unsafe = Regex("""[^A-Za-z0-9]+""")

    /**
     * `SM-R390_00046_Aurora-2.zip` — the package's naming convention, then the project's
     * name, so a folder of exports sorts by face and still says which project each one is.
     *
     * The face alone would collide the moment a second project on one face was exported, and
     * a file a picker silently overwrites is a lost edit. The slug is ASCII-only because this
     * file goes somewhere this app does not control, and it is cosmetic: nothing on import
     * reads it, the manifest carries the real name.
     */
    fun fileName(faceId: String, projectName: String): String {
        val slug = projectName.replace(Unsafe, "-").trim('-').take(SlugLimit).trim('-')
        val base = "SM-R390_$faceId"
        return if (slug.isEmpty()) "$base$Extension" else "${base}_$slug$Extension"
    }
}

/**
 * A project just written out to a file the reader chose.
 *
 * Carries the size because it is the one thing about an export a reader cannot check from
 * inside the app: the file landed somewhere they picked, under a name the picker may have
 * changed.
 */
data class ExportedProject(val name: String, val byteCount: Long)

/** A project just read in from an archive: enough to say so, and to open it. */
data class ImportedProject(val id: Long, val name: String)
