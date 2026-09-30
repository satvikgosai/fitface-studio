package dev.fitface.studio.core.model

import java.security.MessageDigest

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
 * Whether the export and import controls are on screen.
 *
 * They are debugging tools: they write a file outside app-private storage and read a
 * container another build produced, so the app ships with them absent rather than merely
 * disabled, and nothing anywhere hints at them. [isUnlockPhrase] answers one question and
 * is silent at every step — there is no counter, no toast and no target to find.
 *
 * **Only the phrase's SHA-256 is compiled in.** A plaintext constant survives into the APK,
 * where `strings` over the dex hands it to anyone who looks, and a token that reads as a
 * developer switch is exactly what such a dump is scanned for. A digest is 64 hex characters
 * that look like every other hash in the binary. Do not "simplify" this back to a string
 * comparison, and do not write the phrase into a comment, a resource or a document — each of
 * those puts back the thing the digest removes.
 *
 * This is obscurity, not security, and does not pretend otherwise: the check runs on the
 * device and the same dex can be patched. What it buys is that the tools cannot be reached
 * by accident, by a curious tap, or by someone following a screenshot. Nothing behind it is
 * privileged — every path it opens is one the app could already walk, and
 * `Session.validatedBytes()` still stands between all of it and the watch, so no correctness
 * anywhere may depend on this flag.
 */
object DeveloperGate {
    private const val PhraseDigest =
        "65e5fe2a2b5c8c3fc687d73e8777f6e7b1a2682b879a0e06307471d2319ac347"

    /**
     * Whether [query] is the phrase and nothing else.
     *
     * Trimmed because a soft keyboard's space suggestion lands after the last word, and
     * lower-cased because a keyboard that autocapitalises must not be the difference between
     * opening the tools and filtering the list to nothing. Exact — never a prefix or a
     * substring, or it would fire part-way through typing and again on the last character.
     */
    fun isUnlockPhrase(query: String): Boolean {
        val candidate = query.trim().lowercase()
        if (candidate.isEmpty()) return false
        return MessageDigest.getInstance("SHA-256")
            .digest(candidate.toByteArray())
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) } == PhraseDigest
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
