package dev.fitface.studio.core.model

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

enum class ImageFit {
    CONTAIN,
    COVER,
    STRETCH,
}

/**
 * Left edge of the rectangle [WidgetGuide] draws in, in display space.
 *
 * `origin + stored` is the whole calculation, and its inverse — `display − origin` — is
 * how an edit turns a position back into stored bytes. [WidgetGuide.originX] carries
 * whatever the widget's coordinates are measured from: the panel for most records, and
 * another widget's rectangle for the ones that are aligned to one.
 *
 * Use this — never `x` directly — anywhere a widget's rectangle is drawn, hit-tested or
 * cropped, so Rule endpoint ordering is handled once.
 *
 * This used to read the sign of the coordinate to decide whether the widget was anchored
 * to the far edge of the face. That is right for one alignment code and wrong for a
 * coordinate that is simply negative, which is why widgets on six catalogue faces were
 * drawn at the opposite edge from where the watch draws them.
 */
val WidgetGuide.drawLeft: Int get() = originX + x + drawOffsetX

val WidgetGuide.drawTop: Int get() = originY + y + drawOffsetY

/**
 * Where the widget's rectangle was before the current edit.
 *
 * Measured with [WidgetGuide.originalWidth]/[WidgetGuide.originalHeight], never the
 * current extent: a resize moves the anchor *and* changes the size, and the pixels to
 * clear are the ones the old rectangle covered. [WidgetGuide.drawOffsetX] is either
 * zero or a whole width, so it is re-derived at the original extent too.
 */
val WidgetGuide.originalDrawLeft: Int
    get() = originalOriginX + originalX + if (drawOffsetX == 0) 0 else -originalWidth

val WidgetGuide.originalDrawTop: Int
    get() = originalOriginY + originalY + if (drawOffsetY == 0) 0 else -originalHeight

/**
 * How a widget's size is stored, and therefore what bounds a resize of it.
 *
 * The editor's ladder needs the bound and cannot see `:core:format`, so the format layer
 * decides the kind from its schema table and the guide carries it. Only two answers
 * matter to the bound, and the difference between them is whether the edit writes pixels:
 * resampling a raster grows the container, and a widget that stores its own extent does
 * not add a byte.
 */
enum class WidgetResizeKind {
    /** This widget cannot be resized — see `WidgetGuide.supportMessage` for why. */
    NONE,

    /**
     * The size is the artwork's, so a resize rewrites raster bytes: Static, Sprite, Hand,
     * image Arc and LineBar. [RASTER_RESIZE_CEILING] bounds growth because those bytes
     * count against [WATCH_CONTAINER_BYTE_CEILING].
     */
    RASTER,

    /**
     * The size is stored in the record and the widget names no raster, so the resize is a
     * same-size patch: the vector arc's box and the Rule's second endpoint. The container
     * does not change length by one byte, so the only bound is what can be a widget on a
     * 256 × 402 panel — [WIDGET_EXTENT_CEILING].
     */
    FIELDS,
}

/**
 * How large a raster-backed widget may be *grown past what it shipped at*, per side.
 *
 * This is not a hard maximum — see [widgetResizeLimit]. Such a widget may always be taken
 * back to the extent the face shipped, however large that is, because that is the one size
 * whose bytes are known to work: resampling to the original dimensions returns the raster
 * records to their original length, so the container comes back to the size the store
 * shipped, and the watch has now been shown to redraw a resized sprite.
 *
 * An earlier attempt at that bound looked like a firmware refusal — the editor made a
 * 114×136 digit restorable, and the watch installed the result and carried on showing the
 * old face. Face `00022` is 4,117,664 bytes, 76,640 short of
 * [WATCH_CONTAINER_BYTE_CEILING], so growing its frames beyond what it shipped crossed
 * that line instead. Growth past the shipped extent is what has to stay bounded, and the
 * container ceiling is what makes it safe.
 *
 * It is named for the mechanism rather than for the Sprite it was discovered on, because
 * the same arithmetic now bounds a Static, a Hand, an image Arc and a LineBar.
 */
const val RASTER_RESIZE_CEILING = 128

/**
 * The largest extent this app will write for any widget, per side.
 *
 * The panel is 256 × 402, so nothing larger than this can be a widget on it — the
 * catalogue's largest is a 400 × 400 vector arc box, deliberately overhanging the panel.
 * It is both the sanity bound on every resize request and the growth ceiling for a
 * [WidgetResizeKind.FIELDS] widget, which adds no bytes and so has nothing else to fear.
 */
const val WIDGET_EXTENT_CEILING = 512

/**
 * The largest a widget may be resized to, per side.
 *
 * One rule in one place, because the editor's ladder and `StructuralEditor.resizeWidget`
 * have to agree exactly — a rung the format layer would refuse is a button that fails.
 */
fun widgetResizeLimit(shippedExtent: Int, kind: WidgetResizeKind): Int = when (kind) {
    WidgetResizeKind.NONE -> 0
    WidgetResizeKind.RASTER -> maxOf(RASTER_RESIZE_CEILING, shippedExtent)
    WidgetResizeKind.FIELDS -> maxOf(WIDGET_EXTENT_CEILING, shippedExtent)
}

/**
 * The largest container the watch accepts: **4 MiB exactly, confirmed on an SM-R390.**
 *
 * Nothing in the format asks for this — every size field in the container and in its
 * style entries is a `u32`, and the app's own edits parse, validate and round-trip well
 * past it. It is firmware policy, of the same kind as the "never change the image record
 * count" rule and with the same symptom: the container transfers, the install command is
 * accepted, and the watch carries on showing the old face.
 *
 * Two independent observations land on it:
 *
 * * **Every one of the 99 catalogue containers fits inside 4 MiB.** The largest, face
 *   `00072`, is 4,149,034 bytes — 98.9% of the limit and nothing above it.
 * * **Adding a full-panel background is the one edit big enough to cross it**, at
 *   205,880 bytes per style, and the faces tried on an SM-R390 split exactly here.
 *   `00008` (→ 2.22 MiB) and `00016` (→ 3.60 MiB) install and render the new
 *   background; `00019` (→ 4.16 MiB) and `00021` (→ 4.36 MiB) transfer, are accepted,
 *   and leave the watch on the old face. Their bytes are as sound as the two that
 *   work: same edit, same assertions, verified by the independent analyzer.
 *
 * The window those two close on is `4,149,034 .. 4,365,626` bytes, and the limit inside it
 * is 4 MiB — the size a flash slot would be. This KDoc used to stop at the window and call
 * 4 MiB "the only round number in it", which reads as a guess and invites the next reader
 * to try a larger bound on hardware; the value is settled, and only the evidence for it is
 * kept here.
 *
 * It also explains the one hardware result that used to look like a separate firmware
 * rule: a sprite grown past the extent its face shipped, on a face already within 76,640
 * bytes of the ceiling. See [RASTER_RESIZE_CEILING].
 */
const val WATCH_CONTAINER_BYTE_CEILING: Int = 4 * 1024 * 1024

/** Container sizes in the unit the watch's limit is quoted in, e.g. `4.16 MiB`. */
fun mebibytes(bytes: Int): String =
    String.format(java.util.Locale.US, "%.2f MiB", bytes / (1024.0 * 1024.0))

data class ImagePlacement(
    val fit: ImageFit = ImageFit.COVER,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

data class ReplacementImage(
    val uri: String,
    val preview: PreviewFrame,
)

data class ProjectSummary(
    val id: Long,
    val displayName: String,
    val sourceUri: String,
    val faceId: String,
    val faceName: String?,
    val importedAtEpochMillis: Long,
    /**
     * The package's own preview of this project's style, extracted to app-private
     * storage when the project was opened, or null when the package shipped none.
     *
     * Deliberately the vendor's image and not a render of the edit: the projects list
     * must not have to open a container, and opening every project's container to draw
     * one row would parse the whole library on the way into the screen.
     */
    val previewImagePath: String? = null,
    /**
     * What to call this project on screen — the name someone gave it, or the best one
     * that could be derived when it was created.
     *
     * Never blank, and the only field the UI should title a row with. [faceName] and
     * [displayName] are the *face's* names and are identical across every project started
     * on it, which is what made two projects on one face impossible to tell apart.
     */
    val name: String = faceName ?: displayName,
    val styleId: Int? = null,
    /**
     * The store version this project was built from, or null when it cannot be known.
     * Null must read as "say nothing", never as "out of date" — see [isOutdated].
     */
    val packageVersionCode: Long? = null,
    /** When a commit last changed this project. Zero on a row written before schema 5. */
    val updatedAtEpochMillis: Long = 0,
)

/** A project just copied from another one: enough to say so, and to open it. */
data class DuplicatedProject(val id: Long, val name: String)

/**
 * Whether the store has published a newer version of this project's face than the one it
 * was built from.
 *
 * Fail-quiet on an unknown version: a project imported before the app recorded one, or from
 * a source that is not the catalogue, has nothing to compare and must not be badged. The
 * comparison is `<` and not `!=` on purpose — a catalogue served from a stale cache can name
 * an older version than a project already holds, and that is not an update to offer.
 */
fun ProjectSummary.isOutdated(storeVersionCode: Long): Boolean =
    packageVersionCode != null && packageVersionCode < storeVersionCode

/** How a new project gets a name that is not already in use on the same face. */
object ProjectNaming {
    /** A name already ending in a counter, e.g. `Aurora 2` -> stem `Aurora`. */
    private val Numbered = Regex("""^(.*\S)\s+\d+$""")

    /**
     * [base] with `.apk` dropped, followed by the lowest free counter if that is taken.
     *
     * The counter starts at 2 and steps over what is already there, so a face holding
     * "Aurora" and "Aurora 2" names the next one "Aurora 3" rather than reusing a gap — a
     * name people have seen should not come back attached to different work.
     *
     * **A base that already ends in a counter is numbered from its stem.** Downloading only
     * ever passes a face's own name, so this never came up until duplication started passing
     * the name of an existing project: copying "Aurora 2" produced "Aurora 2 2", and a
     * second copy "Aurora 2 3", which is a different series from the one beside it. Only the
     * *taken* base takes this path — duplicating "Aurora 2" while no "Aurora 2" exists keeps
     * it, rather than quietly promoting the copy to "Aurora".
     *
     * Matching is exact, including case: "aurora" and "Aurora" are two names, because
     * deciding they are one would mean silently renaming what someone typed.
     *
     * @param base a non-blank name to build on. Blank is returned unchanged rather than
     *   numbered — " 2" is not a name — and callers guarantee it cannot happen.
     */
    fun defaultName(base: String, taken: Collection<String>): String {
        val root = base.trim().removeSuffix(".apk").trim()
        if (root.isEmpty()) return base
        if (root !in taken) return root
        val stem = Numbered.matchEntire(root)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
            ?: root
        var counter = 2
        while ("$stem $counter" in taken) counter++
        return "$stem $counter"
    }
}

/** One selectable colourway of a catalogue face; maps to a `styleN.bin` entry. */
data class FaceStyleOption(
    val id: Int,
    val previewUrl: String,
)

data class CatalogFace(
    val productId: String,
    val faceId: String,
    val name: String,
    val description: String,
    val appId: String,
    val versionName: String,
    val versionCode: Long,
    val packageSize: Long,
    val styles: List<FaceStyleOption>,
) {
    /** Numeric face id, used for sorting. Always parseable: the id is five digits. */
    val faceNumber: Int get() = faceId.toIntOrNull() ?: Int.MAX_VALUE
}

data class FaceCatalog(
    val faces: List<CatalogFace>,
    val styleCount: Int,
    val fetchedAtEpochMillis: Long = 0,
    /** True when these faces came from the on-disk cache rather than the network. */
    val fromCache: Boolean = false,
)

/**
 * How the catalogue grid is ordered.
 *
 * The labels are **not** here. They used to be, as a `label` property, which made them the
 * only user-facing copy in the app outside a `strings.xml` — and a reversible sort needs two
 * labels per entry, in the reader's language. `:core:model` has no resources, so the wording
 * belongs to the screen that draws the chips.
 */
enum class CatalogSort {
    RECENT,
    NAME,
    NUMBER,
    ;

    /**
     * @param reversed flips the order this entry names. `RECENT` reversed is oldest first,
     *   which means genuinely reversing the catalogue's own order — unreversed it is a no-op
     *   because the store already serves newest first, and reading that as "nothing to do"
     *   in both directions is what would leave the chip inert.
     */
    fun apply(faces: List<CatalogFace>, reversed: Boolean = false): List<CatalogFace> =
        when (this) {
            // Not `sortedWith(...)`: there is no key to sort on, only the order it arrived
            // in, so the reversal is of the list itself.
            RECENT -> if (reversed) faces.reversed() else faces
            // Only the *name* comparator is reversed, and the tiebreak is appended after.
            // Reversing the whole thing — or the sorted list — would flip the tiebreak with
            // it, so two faces sharing a name would swap places for a reason nothing on
            // screen explains.
            NAME -> faces.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, CatalogFace::name)
                    .maybeReversed(reversed)
                    .thenBy(CatalogFace::faceNumber),
            )
            NUMBER -> faces.sortedWith(
                compareBy(CatalogFace::faceNumber).maybeReversed(reversed),
            )
        }
}

/** How the projects list is ordered. Labels live with the screen, as for [CatalogSort]. */
enum class ProjectSort {
    RECENT,
    NAME,
    NUMBER,
    ;

    fun apply(projects: List<ProjectSummary>, reversed: Boolean = false): List<ProjectSummary> =
        when (this) {
            // `updatedAtEpochMillis`, not `importedAtEpochMillis`: the latter is bumped by
            // merely opening a project, so sorting on it made two projects on one face
            // trade places every time either was looked at.
            //
            // Every entry ends `.thenBy(id)`, outside the reversal. Two projects can hold
            // the same name and the same timestamps — legacy rows the schema 5 backfill
            // could not name, most of all — and without a stable last resort they would
            // swap places between recompositions.
            RECENT -> projects.sortedWith(
                compareByDescending(ProjectSummary::updatedAtEpochMillis)
                    .maybeReversed(reversed)
                    .thenByDescending(ProjectSummary::importedAtEpochMillis)
                    .thenBy(ProjectSummary::id),
            )
            NAME -> projects.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, ProjectSummary::name)
                    .maybeReversed(reversed)
                    .thenBy(ProjectSummary::id),
            )
            NUMBER -> projects.sortedWith(
                compareBy<ProjectSummary> { it.faceId.toIntOrNull() ?: Int.MAX_VALUE }
                    .maybeReversed(reversed)
                    .thenBy(ProjectSummary::id),
            )
        }
}

private fun <T> Comparator<T>.maybeReversed(reversed: Boolean): Comparator<T> =
    if (reversed) reversed() else this

/**
 * The face a custom face is built from — Info_4, whose clock carries alpha and whose other
 * widgets are anchored to nothing, so stripping them leaves a clean canvas. A custom face
 * installs into this face's slot on the watch, which the screen offering it has to say.
 */
const val CUSTOM_FACE_TEMPLATE_FACE_ID = "00006"

class FacePackage(
    val sourceKey: String,
    val displayName: String,
    val expectedFaceId: String,
    val selectedStyleId: Int,
    val versionCode: Long,
    bytes: ByteArray,
) {
    private val payload = bytes.copyOf()

    val size: Int
        get() = payload.size

    init {
        require(sourceKey.startsWith(SOURCE_SCHEME)) { "Package source key is invalid" }
        require(expectedFaceId.matches(Regex("\\d{5}"))) { "Package face ID is invalid" }
        require(selectedStyleId in 0..255) { "Style ID must fit in one byte" }
        require(payload.isNotEmpty()) { "Downloaded package is empty" }
    }

    fun copyBytes(): ByteArray = payload.copyOf()

    /** The three parts of [sourceKey], for the project row this package will become. */
    val source: FaceSource? get() = parseSourceKey(sourceKey)

    companion object {
        const val SOURCE_SCHEME = "fit3-catalog://"

        fun sourceKey(productId: String, versionCode: Long, styleId: Int): String =
            "$SOURCE_SCHEME$productId/$versionCode/$styleId"

        /**
         * [sourceKey] read back, or null for anything that is not one.
         *
         * Kept beside the writer because the two have to agree by construction. Null is a
         * real case and not an error: rows written before this app minted its own keys hold
         * a `content://` document URI from the file picker, and schema 5's backfill has to
         * leave those alone rather than throw halfway through a migration.
         */
        fun parseSourceKey(key: String): FaceSource? {
            if (!key.startsWith(SOURCE_SCHEME)) return null
            val parts = key.removePrefix(SOURCE_SCHEME).split('/')
            if (parts.size != 3) return null
            val productId = parts[0].ifEmpty { return null }
            val versionCode = parts[1].toLongOrNull() ?: return null
            val styleId = parts[2].toIntOrNull() ?: return null
            return FaceSource(productId, versionCode, styleId)
        }
    }
}

/** What a project was opened from: which catalogue product, at which version, which style. */
data class FaceSource(
    val productId: String,
    val versionCode: Long,
    val styleId: Int,
)

data class UserMessage(
    val id: Long,
    val text: String,
)

class WatchFaceException(
    val userMessage: String,
    val technicalDetail: String? = null,
    cause: Throwable? = null,
    /**
     * The package parsed but holds no editable watch-face container. Permanent for
     * that package, so callers can stop offering it rather than retrying.
     */
    val isUneditablePackage: Boolean = false,
) : Exception(userMessage, cause)

/** Where a widget record can be seen on the canvas. */
enum class WidgetPlacement {
    /** Drawn smaller than the canvas: selectable and draggable. */
    CANVAS,

    /** Covers the whole canvas — the background layer. */
    BACKGROUND,

    /** No drawable extent (clock hands and similar): editable, not previewable. */
    HIDDEN,
    ;

    val isVisibleOnCanvas: Boolean get() = this == CANVAS
}

/**
 * What a widget record draws, named from its type word.
 *
 * The type ids are documented in `docs/bin-format.md` §7; these labels exist so
 * the widget list can say "Sprite" or "Clock hand" instead of only "type 3".
 * [UNKNOWN] is not a parse failure — the record is still preserved verbatim and its
 * position is still editable.
 */
enum class WidgetCategory(val label: String, val detail: String) {
    IMAGE("Image", "A still image at a fixed position."),
    SPRITE("Sprite", "Images that change with a live reading."),
    ANIMATION("Animation", "An image sequence played by the watch."),
    // What it is, not why it has no outline: the panel above this one already says that,
    // in the support message, and the two together said "rotated about a pivot" twice on
    // one screen.
    HAND("Clock hand", "The hour, minute or second hand."),
    VALUE("Value", "A live reading drawn with the watch’s font."),
    RULE("Rule", "A straight line."),
    COMPOSITE("Composite", "Text and readings combined, such as a date."),
    ARC("Image arc", "A curved gauge drawn from its own artwork."),

    /**
     * The other arc, and the reason the two are named apart.
     *
     * It draws from a stored colour, thickness and angle range instead of a raster, so
     * it has no artwork to replace and no pointer to relocate. 75 records across nine
     * catalogue faces are this type, and every one of them used to be labelled "Other".
     */
    VECTOR_ARC("Vector arc", "A curved gauge drawn from stored colour and thickness."),
    BAR("Bar", "A straight gauge."),
    GROUP("Complication group", "A group of live readings the watch lays out itself."),

    /**
     * A type the watch accepts and then ignores: it builds nothing and updates nothing.
     * No catalogue face carries one. Preserved byte for byte, never offered as editable.
     */
    RESERVED("Reserved", "Accepted by the watch but drawn as nothing."),
    UNKNOWN("Other", "Type preserved verbatim; only its position is interpreted."),
    ;

    companion object {
        fun forWidgetType(type: Int): WidgetCategory = when (type) {
            1 -> IMAGE
            2 -> HAND
            3 -> SPRITE
            4 -> ANIMATION
            5 -> VALUE
            6 -> VECTOR_ARC
            7 -> RULE
            9 -> GROUP
            13 -> COMPOSITE
            16 -> ARC
            17 -> BAR
            8, 10, 11, 12, 14, 15 -> RESERVED
            else -> UNKNOWN
        }
    }
}

/**
 * What a widget's live-data source number means.
 *
 * These are the watch's own readings, not the container's: a face selects among them and
 * cannot introduce one. The map is what a later analysis pass established, joined against
 * the rendered previews shipped in the packages themselves — a face whose steps widget
 * previews `3457` beside a label reading `steps` settles that number.
 *
 * Where no name is available the number is shown as itself. Guessing a label from a
 * plausible-looking sample value is how source 41 was once read as calories and 48 as
 * battery, and both were wrong.
 */
object DataSourceLabels {
    private val labels = mapOf(
        0 to "constant zero",
        1 to "hour", 2 to "hour tens", 3 to "hour units",
        5 to "AM/PM",
        9 to "minute", 10 to "minute tens", 11 to "minute units",
        13 to "second", 14 to "second tens", 15 to "second units",
        17 to "weekday",
        18 to "day of month", 19 to "day tens", 20 to "day units",
        21 to "month", 22 to "month tens", 23 to "month units",
        24 to "year", 25 to "year thousands", 26 to "year hundreds",
        27 to "year tens", 28 to "year units",
        29 to "steps",
        37 to "battery",
        41 to "heart rate",
        48 to "calories",
        55 to "distance",
        62 to "temperature",
        69 to "weather icon",
        71 to "active minutes",
        72 to "floors",
        75 to "complication group",
        102 to "blood oxygen",
        104 to "sleep",
        106 to "second clock hour tens", 107 to "second clock hour units",
        109 to "second clock minute tens", 110 to "second clock minute units",
        115 to "water",
        116 to "second time zone",
        120 to "weather description",
        122 to "second zone month", 123 to "second zone weekday", 124 to "second zone day",
        125 to "second zone AM/PM",
    )

    /** The reading [source] selects, or null when this format has no name for it. */
    fun labelOrNull(source: Int): String? = labels[source]

    /** `steps`, or `source 70` where no name is established. */
    fun label(source: Int): String = labels[source] ?: "source $source"
}

data class WidgetGuide(
    val ordinal: Int,
    val globalIndex: Int,
    val type: Int,
    val sequenceId: Int,
    val x: Int,
    val y: Int,
    val originalX: Int = x,
    val originalY: Int = y,
    val width: Int,
    val height: Int,
    /**
     * The extent before the current edit.
     *
     * A resize rewrites the record or its rasters, so [width]/[height] follow the current
     * resources. The original extent anchors the resize ladder and pristine resampling;
     * rendering no longer uses it to clear pixels from a vendor preview.
     */
    val originalWidth: Int = width,
    val originalHeight: Int = height,
    val recordSize: Int,
    val isFinal: Boolean,
    val canEditPosition: Boolean,
    /**
     * How this widget resizes, or [WidgetResizeKind.NONE] when it cannot.
     *
     * Carried rather than derived from [type], because whether a *particular* record can
     * be resized depends on the container around it — a Static that draws the panel
     * background, a raster pool whose members disagree about their pixel format — and the
     * format layer is the only thing that can see that.
     */
    val resizeKind: WidgetResizeKind = WidgetResizeKind.NONE,
    val placement: WidgetPlacement = WidgetPlacement.CANVAS,
    /**
     * The display position [x] is measured from — zero for a widget positioned against
     * the panel, and another widget's edge for one aligned to it.
     *
     * Every Image, Clock hand, Value and Composite record in the catalogue is aligned to
     * something, so this is the normal case rather than the exception.
     */
    val originX: Int = 0,
    val originY: Int = 0,
    /** The origin before the current edit, for the same reason as [originalWidth]. */
    val originalOriginX: Int = originX,
    val originalOriginY: Int = originY,
    /** The widget this one is positioned against, when its reference resolved to one. */
    val alignedToGlobalIndex: Int? = null,
    /** The live reading this widget follows, where the format names one. */
    val sourceLabel: String? = null,
    /**
     * Whether this widget follows a live reading at all.
     *
     * Separate from [sourceLabel] being null, which only means the reading has no
     * established name. A Composite gives each of its parts its own reading and ignores
     * the record's, so calling it "reading 0" would be noise.
     */
    val followsReading: Boolean = false,
    /**
     * Offset from the stored coordinate to the left edge of the drawn rectangle.
     *
     * Zero for almost everything: the stored `x` *is* the left edge. A Badge is the
     * exception — it stores two endpoints and either may be the larger, so when the
     * stored one is the far end its rectangle begins a whole width earlier. 52 of the
     * 84 Badges in the catalogue are stored that way round, and without this they were
     * drawn off the panel entirely and could not be selected.
     */
    val drawOffsetX: Int = 0,
    val drawOffsetY: Int = 0,
    val category: WidgetCategory = WidgetCategory.forWidgetType(type),
    /** Frames a Sprite indexes, from its `+0x20` count. Null for every other type. */
    val frameCount: Int? = null,
    /** The watch paints this widget's full rectangle, hiding whatever is behind it. */
    val hasOpaqueBackdrop: Boolean = false,
    val colorArgb: Int?,
    val originalColorArgb: Int? = colorArgb,
    /** Raw native angle; null when this type has no supported rotation field. */
    val rotationTenths: Int? = null,
    val originalRotationTenths: Int? = rotationTenths,
    val duplicateSourceGlobalIndex: Int? = null,
    /** Imports are edited only in the variant where they were added. */
    val importedFromFaceId: String? = null,
    val supportMessage: String,
) {
    /** Whether the editor may offer to resize this widget at all. */
    val canResize: Boolean get() = resizeKind != WidgetResizeKind.NONE
}

/**
 * Which of a container's editable face entries a variant is.
 *
 * A numbered style is installable and carries a sampler id; `aod.bin` is not — it is
 * always-on-display artwork the watch shows on its own, never selected for install. See
 * [EditorSnapshot.selectedVariant] and [EditorSnapshot.activeStyleName].
 */
enum class VariantKind { STYLE, AOD }

/**
 * The always-on display's entry name.
 *
 * Here rather than in `:core:format` — where it used to live — because it is a *name*, and
 * the modules that have to recognise it reach further down than the format layer does:
 * `:core:model` tells a removed record's scope apart from it and `:feature:editor` cannot
 * see `:core:format` at all. Every module above this one can now spell it one way. The
 * literal was written out 24 times across four modules once already; do not start a
 * twenty-fifth.
 */
const val AOD_ENTRY_NAME = "aod.bin"

/**
 * One editable face entry: a numbered style or the single always-on-display entry.
 *
 * [styleIndex] is the entry's position among [EditorSnapshot.styleNames], null for AOD —
 * AOD has no sampler id and no preview-frame index of its own.
 */
data class EditorVariant(
    val basename: String,
    val kind: VariantKind,
    val styleIndex: Int? = null,
) {
    /** This entry's own style number, or null for one that is not a numbered style. */
    val styleNumber: Int? get() = styleNumberOf(basename)

    companion object {
        /**
         * The `N` in `styleN.bin`, counted from **zero** the way the container counts it.
         *
         * This is the format's number, not the reader's — `:core:ui`'s `styleLabel` is
         * where the one-based offset and the wording are applied, once, for every screen
         * that shows one.
         *
         * Read out of the basename rather than off [styleIndex], which is a *position*:
         * it indexes `preview.bin`'s frames and the package's extracted PNGs, so it is
         * the same number only while a face numbers its styles contiguously from zero.
         * The catalogue's `FaceStyleOption.id` is this same number, which is what lets
         * the library and the editor name one colourway identically.
         *
         * Null for anything that is not a numbered style — [AOD_ENTRY_NAME], and any
         * entry name this app has not seen; the caller names those in words.
         */
        fun styleNumberOf(basename: String): Int? =
            StyleEntryName.matchEntire(basename)?.groupValues?.get(1)?.toIntOrNull()

        private val StyleEntryName = Regex("""style(\d+)\.bin""")
    }
}

/**
 * A widget record that was removed from the container and can be appended back.
 * [recordsByVariant] holds the exact bytes that were cut out of each face entry.
 *
 * It carries the *facts* the Removed list shows and no assembled copy: this module is
 * framework-free and cannot reach a resource table, so a label built here is a string no
 * translator can see and no screen can reword. It used to hold `label = "Widget #12"` for
 * exactly that reason, spelled differently from the `Widget #%1$d` two lines away in the
 * editor's own resources.
 */
data class RemovedWidget(
    val id: Long,
    /**
     * Where the record sat when it was cut, which is a historical marker rather than a
     * live address: `removeWidget` renumbers everything after the record it cuts, so this
     * names the widget the reader removed and must not be used to resolve it. Negative
     * when it is not known, which is only a session written before this field existed.
     */
    val globalIndex: Int,
    val widgetType: Int,
    val sequenceId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /**
     * The live reading it followed, named, or null where the format names none — the same
     * two fields [WidgetGuide] carries, copied at removal because the guide is gone once
     * the record is out of the container.
     */
    val sourceLabel: String? = null,
    val followsReading: Boolean = false,
    val recordsByVariant: Map<String, ByteArray>,
    val importOriginId: String? = null,
    val nativeIdentityRecorded: Boolean = false,
    val nativeSourceIndices: Map<String, Int> = emptyMap(),
    val duplicateSourceVariants: Set<String> = emptySet(),
) {
    /** What it draws, named — the same label the widget list shows for a live record. */
    val category: WidgetCategory get() = WidgetCategory.forWidgetType(widgetType)

    /**
     * How many **numbered styles** the record was cut from.
     *
     * Counted rather than taken from `recordsByVariant.size`, which counts *entries*: an
     * always-on removal writes one entry that is not a style, and reporting it as "1
     * styles" is the same mistake `editor_audit_detail_aod` exists to avoid. AOD is never
     * joined to a style edit, so in practice this is either the style count or zero.
     */
    val styleCount: Int
        get() = recordsByVariant.keys.count { EditorVariant.styleNumberOf(it) != null }

    /** Whether the record was cut from the always-on display. */
    val touchedAod: Boolean get() = AOD_ENTRY_NAME in recordsByVariant

    override fun equals(other: Any?): Boolean = this === other ||
        (other is RemovedWidget && other.id == id)

    override fun hashCode(): Int = id.hashCode()
}

data class PreviewFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
)

data class WidgetImageLayer(
    val globalIndex: Int,
    val frame: PreviewFrame,
    /** The source raster has no alpha channel; the watch paints every pixel. */
    val isOpaque: Boolean = false,
    /** Artwork origin relative to the guide, including rotation/stroke overhang. */
    val offsetX: Int = 0,
    val offsetY: Int = 0,
)

data class EditAuditSummary(
    val changedPayloadBytes: Int,
    val changedStyles: List<String>,
    val operation: String = "Edit",
    val sizeDelta: Int = 0,
)

data class EditorSnapshot(
    val projectId: Long,
    val faceId: String,
    val faceName: String?,
    val sourceName: String,
    /**
     * What this project is called, which is not what the *face* is called.
     *
     * [faceName] and [sourceName] come from the package and read identically on every
     * project started from it. This one is the reader's, and it is why the editor can say
     * which of two projects on one face is open.
     */
    val projectName: String = sourceName,
    val styleNames: List<String>,
    /**
     * Every editable face entry, numbered styles first, `aod.bin` last when the
     * container carries one — the Styles page's row order. See [VariantKind].
     */
    val variants: List<EditorVariant> = styleNames.mapIndexed { index, name ->
        EditorVariant(name, VariantKind.STYLE, index)
    },
    /** What the canvas currently shows and edits — a numbered style or AOD. */
    val selectedVariant: EditorVariant = variants.first(),
    /**
     * The style that installs and supplies the sampler id.
     *
     * Deliberately separate from [selectedVariant]: looking at the always-on display
     * must never change which style is queued to install, so this is never `"aod.bin"`
     * and only ever moves when a numbered style is selected. Install and the Validate
     * page's "active style" wording must read this, never [selectedVariant].
     */
    val activeStyleName: String,
    /** Face entries whose payload actually differs from the pristine container. */
    val editedVariantNames: Set<String> = emptySet(),
    /**
     * The always-on display's own generated thumbnail, for its Styles-page row when it
     * is not [selectedVariant] — there is no packaged AOD preview to show instead, the
     * package never ships one. Null when the container carries no `aod.bin`.
     */
    val aodThumbnail: PreviewFrame? = null,
    /**
     * Whether [composedPreview] approximates part of [selectedVariant] rather than
     * rendering it exactly: firmware fonts are approximated and firmware-only resources
     * or unknown readings may be unavailable. All variants use the same resource renderer.
     */
    val selectedVariantApproximate: Boolean = false,
    val preview: PreviewFrame,
    val composedPreview: PreviewFrame,
    val widgetOverlay: PreviewFrame,
    val widgetImageLayers: List<WidgetImageLayer>,
    val widgets: List<WidgetGuide>,
    val removedWidgets: List<RemovedWidget> = emptyList(),
    /**
     * Style name → the package's own preview image for that style, on disk.
     *
     * The Styles page shows what a variant looks like rather than only its name, and
     * these are the vendor's renders of the *unedited* face: nothing in the container
     * is a per-style picture the app could draw cheaply for every variant at once.
     * The selected style is drawn from [composedPreview] instead, so the one variant
     * whose edits are known is shown with them.
     */
    val stylePreviewPaths: Map<String, String> = emptyMap(),
    /**
     * The styles that carry a full-panel background raster, which are exactly the ones
     * a background replacement or a tint can rewrite.
     *
     * Not every style has one: face `00022` opens all three of its styles with a 37×28
     * icon and paints the rest straight onto the watch's black panel, and `00108` does
     * the same for styles 0–3. Such a style can be *given* one instead — see
     * [backgroundAddTargets], which is a different edit and not always affordable — so
     * this is what the Background page offers a replacement against, rather than letting
     * an image be positioned against a style that has nothing to replace.
     */
    val backgroundStyles: List<String> = emptyList(),
    /**
     * The styles an *added* background would actually be written to, in the order it
     * would write them, or empty when the face cannot take one.
     *
     * Not simply "every style with none": a full-panel raster costs 205,880 bytes per
     * style, and the watch ignores a container past [WATCH_CONTAINER_BYTE_CEILING]. Six
     * of the fourteen backgroundless faces cannot take one in every style, so the edit
     * writes the selected style first and adds siblings only while they fit. Face
     * `00022` has no room for even one, which is what an empty list on a backgroundless
     * face means.
     */
    val backgroundAddTargets: List<String> = emptyList(),
    /**
     * The same two facts for the always-on display, which the style lists above say
     * nothing about: 32 of the corpus's 99 carry a panel raster and the rest compose over
     * black, independently of what their styles do.
     *
     * Separate fields rather than folded into [backgroundStyles] because a background
     * edit on AOD writes that one entry and a background edit on the styles writes the
     * group — see [canReplaceBackground], which is what the page reads.
     */
    val aodHasBackground: Boolean = false,
    /** Whether AOD has room under the ceiling to be *given* a background. */
    val aodCanTakeBackground: Boolean = false,
    /** Size of the container as it stands, measured against the watch's ceiling. */
    val containerBytes: Int = 0,
    val imageCount: Int,
    val validationErrors: List<String>,
    val validationWarnings: List<String>,
    val isDirty: Boolean,
    val thumbnailRefreshed: Boolean = false,
    /**
     * The face-picker thumbnail of [activeStyleName] as it is stored now: one frame of
     * `preview.bin`, which is what the watch's carousel and the companion app show for
     * this style. Null where the face has none this app can read.
     */
    val pickerThumbnail: PreviewFrame? = null,
    /**
     * Exactly what **Update thumbnail** would store instead — box-filtered and quantised
     * the way the update writes it. Null wherever the update is not on offer, so a screen
     * showing it is showing a promise the button keeps.
     */
    val pickerThumbnailAfter: PreviewFrame? = null,
    val audit: EditAuditSummary?,
) {
    /**
     * Whether re-rendering the face-picker thumbnail would achieve anything.
     *
     * False once it already matches the edit: the widget pixels come from the
     * vendor's smaller `preview.bin` render, so resampling them again only softens
     * the result. Also false for an unedited face, whose stock thumbnail is already
     * correct, and for one that does not validate, where re-rendering would just bake
     * in a broken layout.
     */
    val canRefreshThumbnail: Boolean
        get() = isDirty && !thumbnailRefreshed && validationErrors.isEmpty() &&
            selectedVariant.kind == VariantKind.STYLE

    /**
     * Whether this container carries an always-on display at all.
     *
     * Read from [variants], which comes from the container's own entry table — never
     * inferred from whether [aodThumbnail] rendered, or a face would lose its AOD row
     * the moment a preview failed rather than because it has none.
     */
    val hasAod: Boolean
        get() = variants.any { it.kind == VariantKind.AOD }

    /** Whether the always-on display is what the canvas currently shows and edits. */
    val isAodSelected: Boolean
        get() = selectedVariant.kind == VariantKind.AOD

    /** Whether [selectedVariant]'s own payload differs from the pristine container. */
    val isSelectedVariantDirty: Boolean
        get() = selectedVariant.basename in editedVariantNames

    /**
     * Whether the last committed edit reached the always-on display and nothing else.
     *
     * Decided here rather than by a screen comparing [EditAuditSummary.changedStyles] to
     * an entry name it would have to know: "n of m styles" is the wrong sentence for an
     * AOD-only edit, because AOD is neither counted among the styles nor one of them.
     */
    val auditTouchedOnlyAod: Boolean
        get() = variants.firstOrNull { it.kind == VariantKind.AOD }?.let { aod ->
            audit?.changedStyles == listOf(aod.basename)
        } ?: false

    /**
     * Whether what is on the canvas can take a replacement background.
     *
     * Two questions in one, because a background edit has two scopes: with a style
     * selected it writes every style that carries a panel raster, so the answer is about
     * the group; with AOD selected it writes that one entry, so the answer is about it
     * alone. Answering the style question while AOD is on the canvas is what would offer
     * a replacement the repository then has to refuse.
     */
    val canReplaceBackground: Boolean
        get() = if (isAodSelected) aodHasBackground else backgroundStyles.isNotEmpty()

    /**
     * Whether this face can be *given* its first background: no style has one, so there
     * is nothing to replace and nothing to overwrite, and at least one style still has
     * room for the raster under [WATCH_CONTAINER_BYTE_CEILING].
     *
     * A face with a background in even one style is served by the same-size replacement
     * instead — adding a second panel raster there would be a new layer, not a
     * background. See [WatchFaceRepository.addBackground].
     */
    val canAddBackground: Boolean
        get() = if (isAodSelected) {
            !aodHasBackground && aodCanTakeBackground
        } else {
            backgroundStyles.isEmpty() && backgroundAddTargets.isNotEmpty()
        }

    /**
     * A backgroundless face with no room left for one. Distinct from
     * [canReplaceBackground] being false: there is nothing wrong with the face, the
     * container is simply too close to the watch's size ceiling already.
     */
    val backgroundWouldNotFit: Boolean
        get() = if (isAodSelected) {
            !aodHasBackground && !aodCanTakeBackground
        } else {
            backgroundStyles.isEmpty() && styleNames.isNotEmpty() &&
                backgroundAddTargets.isEmpty()
        }

    /**
     * Styles an added background would have to skip to stay under the ceiling.
     *
     * Empty with AOD selected: it was the only candidate, so the styles beside it were
     * never skipped for want of room and saying they were would be a different claim.
     */
    val backgroundAddSkipped: List<String>
        get() = if (isAodSelected || backgroundAddTargets.isEmpty()) {
            emptyList()
        } else {
            styleNames - backgroundAddTargets.toSet()
        }

    /**
     * Whether the variant on the canvas carries a panel raster of its own.
     *
     * For a style, false means a replacement still applies — to the siblings that do
     * carry a background — but nothing about *this* canvas would change, so the page says
     * so instead of looking broken. For AOD there are no siblings: false means the edit
     * has nothing to replace at all, which is why [canReplaceBackground] asks this
     * instead of the style group.
     */
    val selectedVariantHasBackground: Boolean
        get() = if (isAodSelected) aodHasBackground else selectedVariant.basename in backgroundStyles

    /** Widgets the canvas can draw and the user can drag. */
    val canvasWidgets: List<WidgetGuide>
        get() = widgets.filter { it.placement.isVisibleOnCanvas }

    /** Records that exist in the container but have no draggable rectangle. */
    val offCanvasWidgets: List<WidgetGuide>
        get() = widgets.filterNot { it.placement.isVisibleOnCanvas }
}

class DirectInstallPayload(
    val faceId: Int,
    val samplerId: Int,
    val fileName: String,
    val sha256: String,
    bytes: ByteArray,
) {
    private val payload = bytes.copyOf()

    val size: Int
        get() = payload.size

    init {
        require(faceId in 0..255) { "Face ID must fit in the Fit3 protocol byte" }
        require(samplerId in 0..255) { "Sampler ID must fit in the Fit3 protocol byte" }
        require(
            fileName == "SM-R390_${faceId.toString().padStart(5, '0')}_256x402.bin",
        ) { "Binary filename does not match face ID $faceId" }
        require(payload.isNotEmpty()) { "Watch-face binary is empty" }
        require(payload.size <= MAX_DIRECT_INSTALL_BYTES) {
            "Watch-face binary exceeds the 16 MiB direct-install limit"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "SHA-256 must be lowercase hexadecimal"
        }
        require(payload.sha256() == sha256) {
            "Watch-face binary does not match its frozen SHA-256"
        }
    }

    fun copyBytes(): ByteArray = payload.copyOf()

    /**
     * Whether this payload ends exactly on a transfer window boundary.
     *
     * The transfer sends [TRANSFER_WINDOW_BYTES] at a time and the watch acknowledges
     * each window, with the last one normally short. A payload that divides exactly
     * leaves no short window at all, and that is the one shape of transfer this project
     * has never been able to observe end to end. It is not refused — the bytes are as
     * sound as any other, and refusing an install that would probably work is worse than
     * the risk — but it is worth having in a bug report if a transfer ever stalls right
     * at the end.
     */
    val endsOnWindowBoundary: Boolean
        get() = payload.size % TRANSFER_WINDOW_BYTES == 0

    companion object {
        const val MAX_DIRECT_INSTALL_BYTES: Int = 16 * 1024 * 1024

        /** Data bytes per acknowledged transfer window. */
        const val TRANSFER_WINDOW_BYTES: Int = 39_600

        fun create(
            faceId: Int,
            samplerId: Int,
            fileName: String,
            bytes: ByteArray,
        ): DirectInstallPayload = DirectInstallPayload(
            faceId = faceId,
            samplerId = samplerId,
            fileName = fileName,
            sha256 = bytes.sha256(),
            bytes = bytes,
        )
    }
}

interface WatchFaceRepository {
    /** Loads an independent catalogue package without creating/opening a donor project. */
    suspend fun inspectWidgetDonor(download: FacePackage): WidgetDonor = throw UnsupportedOperationException()
    suspend fun widgetDonorVariant(handle: String, variant: String): WidgetDonorVariant = throw UnsupportedOperationException()
    suspend fun previewWidgetImport(handle: String, donorVariant: String, index: Int,
        projectId: Long, targetVariant: String): WidgetImportPreview = throw UnsupportedOperationException()
    suspend fun importWidget(ticket: String): EditorSnapshot = throw UnsupportedOperationException()
    suspend fun releaseWidgetDonor(handle: String) {}
    suspend fun backgroundDonorVariant(handle: String, variant: String): BackgroundDonorVariant =
        throw UnsupportedOperationException()
    suspend fun previewBackgroundImport(handle: String, donorVariant: String,
        projectId: Long, targetVariant: String): BackgroundImportPreview = throw UnsupportedOperationException()
    suspend fun importBackground(ticket: String): EditorSnapshot = throw UnsupportedOperationException()
    fun observeProjects(): Flow<List<ProjectSummary>>

    fun observeImageFit(): Flow<ImageFit>

    suspend fun setImageFit(value: ImageFit)

    suspend fun openPackage(download: FacePackage): EditorSnapshot

    /**
     * Starts a custom face from [download], which has to be the template's own face
     * ([CUSTOM_FACE_TEMPLATE_FACE_ID]): stripped to its clock, given previews of what it now
     * is, and saved as a new project called [name], numbered if that is taken.
     *
     * Made here, from the package the store served, rather than shipped: no watch-face
     * content is bundled with this app.
     */
    suspend fun openTemplate(download: FacePackage, name: String): EditorSnapshot =
        throw UnsupportedOperationException()

    suspend fun openProject(projectId: Long): EditorSnapshot

    /**
     * Renames a project. A blank name is ignored rather than refused: a rename dialog is
     * not a place to fail, and an empty title would leave a row nothing could identify.
     */
    suspend fun renameProject(projectId: Long, name: String)

    /**
     * Copies a project — its package, its edits, its removed-widget records and its style
     * previews — into a new one beside it, named against the face's other projects.
     *
     * The copy is **independent**: editing or deleting either one leaves the other alone.
     * That is the whole contract, and it is why this cannot be a plain row copy — a project
     * row holds absolute paths into its own directory, so a duplicate that kept them would
     * read the original's edits and write over the original's files.
     */
    suspend fun duplicateProject(projectId: Long): DuplicatedProject

    suspend fun deleteProject(projectId: Long)

    /**
     * Writes [projectId] to [destinationUri] as a project archive, and says how big it came
     * out.
     *
     * The archive is a package the app can open: the members `Fit3Apk` reads under the names
     * the package gave them, plus the edit and the project's own details. So an import is an
     * [openProject] away from being indistinguishable from a download, and this had to grow
     * no second container writer to manage it.
     *
     * [destinationUri] is a document the reader chose in the system picker, and it is the one
     * place this app writes outside its private storage — see invariant 5 in
     * `docs/architecture.md`. Behind [DeveloperGate], because it is a debugging tool.
     */
    suspend fun exportProject(projectId: Long, destinationUri: String): ExportedProject

    /**
     * Reads an archive at [sourceUri] into a **new** project, named against the face's
     * existing ones.
     *
     * Always new, never a merge into a project already on the face, for [openPackage]'s
     * reason: two projects on one face are the normal case now, and quietly re-entering one
     * is what that change existed to stop. So importing the same archive twice gives two
     * projects, which is also what makes an archive usable as a checkpoint.
     */
    suspend fun importProject(sourceUri: String): ImportedProject

    /**
     * Whether the export and import controls are on screen. Off on a fresh install, and
     * turned on only by the phrase [DeveloperGate] holds.
     */
    fun observeDeveloperTools(): Flow<Boolean>

    suspend fun setDeveloperTools(enabled: Boolean)

    suspend fun currentSnapshot(styleName: String? = null): EditorSnapshot

    suspend fun prepareReplacementImage(imageUri: String): ReplacementImage

    suspend fun replaceBackground(
        imageUri: String,
        placement: ImagePlacement,
    ): EditorSnapshot

    /**
     * Gives a face that carries no full-panel raster one, by adding an image record and
     * the Static that draws it to every style.
     *
     * Separate from [replaceBackground] because it is a different edit: that one patches
     * pixels in place, this one grows the container. Both end with the same picture on
     * the watch, and which applies is decided by [EditorSnapshot.canAddBackground].
     */
    suspend fun addBackground(
        imageUri: String,
        placement: ImagePlacement,
    ): EditorSnapshot

    suspend fun tintBackground(red: Int, green: Int, blue: Int): EditorSnapshot

    suspend fun editPairWidget(
        styleName: String,
        globalIndex: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        colorArgb: Int,
    ): EditorSnapshot

    suspend fun recolorPairWidget(
        styleName: String,
        globalIndex: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        colorArgb: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot

    suspend fun moveWidget(
        styleName: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot

    suspend fun rotateWidget(styleName: String, globalIndex: Int, sequenceId: Int,
        x: Int, y: Int, angleTenths: Int, applyToAllStyles: Boolean): EditorSnapshot =
        throw UnsupportedOperationException("Widget rotation is unavailable")

    suspend fun resizeBackground(width: Int, height: Int): EditorSnapshot

    /**
     * Resizes one widget to [width] × [height] in the extent terms [WidgetGuide] reports,
     * whatever its type stores that extent in.
     *
     * The selection is the same tuple every other widget edit takes, and for the same
     * reason: a widget's global index is not an identity across a structural edit, and a
     * **Static's data source is `0` in 678 of the catalogue's 681 records**, so neither
     * alone can name the record to rewrite.
     */
    suspend fun resizeWidget(
        styleName: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot

    suspend fun removeWidget(
        styleName: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        requireFinal: Boolean,
        applyToAllStyles: Boolean,
    ): EditorSnapshot

    suspend fun duplicateWidget(
        styleName: String,
        globalIndex: Int,
        widgetType: Int,
        sequenceId: Int,
        x: Int,
        y: Int,
        applyToAllStyles: Boolean,
    ): EditorSnapshot

    /** Re-appends a widget that [removeWidget] took out, at the end of the table. */
    suspend fun restoreWidget(removedId: Long): EditorSnapshot

    /** Re-renders the face-picker thumbnail for the selected style. */
    suspend fun refreshThumbnail(): EditorSnapshot

    suspend fun resetEdits(): EditorSnapshot

    suspend fun prepareDirectInstall(): DirectInstallPayload

    /**
     * What the open editing session can safely say about itself in a bug report, or null
     * when nothing is open.
     *
     * Lives here rather than being assembled from [EditorSnapshot] because the ordered
     * edit history is the part that matters and the snapshot does not carry it — and
     * because a face that draws wrong usually threw nothing, so the sequence of
     * operations is the whole account of what happened.
     */
    suspend fun diagnosticsSection(): DiagnosticsSection?
}

/** Progress reported while a catalogue package downloads. */
data class DownloadProgress(val receivedBytes: Long, val totalBytes: Long) {
    val fraction: Float
        get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

interface FaceCatalogRepository {
    /** Cached catalogue, if one was stored by an earlier session. */
    suspend fun cachedCatalog(): FaceCatalog?

    suspend fun loadCatalog(forceRefresh: Boolean = false): FaceCatalog

    /**
     * App IDs whose package turned out to contain no editable container. Remembered
     * so the catalogue can say so before the user pays for another download.
     */
    suspend fun uneditableAppIds(): Set<String>

    suspend fun markUneditable(appId: String)

    /**
     * Whether this face's *current* package is already on disk, so opening it needs no
     * network. Cheap: a file check, not a read.
     */
    suspend fun isPackageCached(face: CatalogFace): Boolean

    /**
     * Returns the signed package for [face]. A package already cached for the same
     * `versionCode` is reused; anything else is downloaded and then cached.
     */
    suspend fun downloadPackage(
        face: CatalogFace,
        styleId: Int,
        onProgress: (DownloadProgress) -> Unit = {},
    ): FacePackage
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { value -> "%02x".format(value.toInt() and 0xff) }
