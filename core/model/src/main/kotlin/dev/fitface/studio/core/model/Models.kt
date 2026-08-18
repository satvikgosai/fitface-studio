package dev.fitface.studio.core.model

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

enum class ImageFit {
    CONTAIN,
    COVER,
    STRETCH,
}

fun displayCoordinate(value: Int, extent: Int, canvasExtent: Int): Int =
    if (value < 0) canvasExtent + value - extent else value

fun encodeCoordinate(
    display: Int,
    extent: Int,
    canvasExtent: Int,
    anchoredFromEnd: Boolean,
): Int = if (anchoredFromEnd) {
    display + extent - canvasExtent
} else {
    display
}

/**
 * Left edge of the rectangle [WidgetGuide] draws in, in display space.
 *
 * Use this — never `displayCoordinate(x, …)` directly — anywhere a widget's rectangle
 * is drawn, hit-tested or cropped, so Badge endpoint ordering is handled once.
 */
fun WidgetGuide.drawLeft(canvasWidth: Int): Int =
    displayCoordinate(x, width, canvasWidth) + drawOffsetX

fun WidgetGuide.drawTop(canvasHeight: Int): Int =
    displayCoordinate(y, height, canvasHeight) + drawOffsetY

/**
 * Where the widget's rectangle was before the current edit.
 *
 * Measured with [WidgetGuide.originalWidth]/[WidgetGuide.originalHeight], never the
 * current extent: a resize moves the anchor *and* changes the size, and the pixels to
 * clear are the ones the old rectangle covered. [WidgetGuide.drawOffsetX] is either
 * zero or a whole width, so it is re-derived at the original extent too.
 */
fun WidgetGuide.originalDrawLeft(canvasWidth: Int): Int =
    displayCoordinate(originalX, originalWidth, canvasWidth) +
        if (drawOffsetX == 0) 0 else -originalWidth

fun WidgetGuide.originalDrawTop(canvasHeight: Int): Int =
    displayCoordinate(originalY, originalHeight, canvasHeight) +
        if (drawOffsetY == 0) 0 else -originalHeight

/**
 * How large a Sprite may be *grown past what it shipped at*, per side.
 *
 * This is not a hard maximum — see [spriteResizeLimit]. A sprite may always be taken back
 * to the extent the face shipped, however large that is, because that is the one size
 * whose bytes are known to work: resampling to the original dimensions returns the frame
 * records to their original length, so the container comes back to the size the store
 * shipped, and the watch has now been shown to redraw a resized sprite.
 *
 * An earlier attempt at that bound looked like a firmware refusal — the editor made a
 * 114×136 digit restorable, and the watch installed the result and carried on showing the
 * old face. Face `00022` is 4,117,664 bytes, 76,640 short of
 * [WATCH_CONTAINER_BYTE_CEILING], so growing its frames beyond what it shipped crossed
 * that line instead. Growth past the shipped extent is what has to stay bounded, and the
 * container ceiling is what makes it safe.
 */
const val SPRITE_RESIZE_CEILING = 128

/**
 * The largest a Sprite frame may be resized to: [SPRITE_RESIZE_CEILING], or the extent it
 * shipped at when the face ships something larger.
 *
 * One rule in one place, because the editor's ladder and `StructuralEditor.resizeSprite`
 * have to agree exactly — a rung the format layer would refuse is a button that fails.
 */
fun spriteResizeLimit(shippedExtent: Int): Int = maxOf(SPRITE_RESIZE_CEILING, shippedExtent)

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
 * bytes of the ceiling. See [SPRITE_RESIZE_CEILING].
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
)

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

enum class CatalogSort(val label: String) {
    RECENT("Newest"),
    NAME("Name A–Z"),
    NUMBER("Face number"),
    ;

    fun apply(faces: List<CatalogFace>): List<CatalogFace> = when (this) {
        RECENT -> faces
        NAME -> faces.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, CatalogFace::name)
                .thenBy(CatalogFace::faceNumber),
        )
        NUMBER -> faces.sortedBy(CatalogFace::faceNumber)
    }
}

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

    companion object {
        const val SOURCE_SCHEME = "fit3-catalog://"

        fun sourceKey(productId: String, versionCode: Long, styleId: Int): String =
            "$SOURCE_SCHEME$productId/$versionCode/$styleId"
    }
}

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
    IMAGE("Image", "One static raster blitted at a fixed position."),
    SPRITE("Sprite", "A table of frames the watch indexes with a live value."),
    HAND("Clock hand", "A hand rotated about a pivot, so it has no fixed rectangle."),
    VALUE("Value", "A live reading the watch draws with its own glyphs."),
    RULE("Rule", "A straight line between two stored endpoints."),
    COMPOSITE("Composite", "Several sub-fields laid out together, such as a date."),
    ARC("Arc", "A curved gauge."),
    BAR("Bar", "A straight gauge."),
    UNKNOWN("Other", "Type preserved verbatim; only its position is interpreted."),
    ;

    companion object {
        fun forWidgetType(type: Int): WidgetCategory = when (type) {
            1 -> IMAGE
            2 -> HAND
            3 -> SPRITE
            5 -> VALUE
            7 -> RULE
            13 -> COMPOSITE
            16 -> ARC
            17 -> BAR
            else -> UNKNOWN
        }
    }
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
     * A Sprite resize rewrites every referenced frame, so [width]/[height] follow the
     * new raster the moment it commits while the vendor's `preview.bin` still renders
     * the old one. Both rectangles are needed: the new one says where to draw, the
     * original one says which reference pixels the edit has to clear.
     */
    val originalWidth: Int = width,
    val originalHeight: Int = height,
    val recordSize: Int,
    val isFinal: Boolean,
    val canEditPosition: Boolean,
    val canResize: Boolean = false,
    val placement: WidgetPlacement = WidgetPlacement.CANVAS,
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
    val duplicateSourceGlobalIndex: Int? = null,
    val supportMessage: String,
)

/**
 * A widget record that was removed from the container and can be appended back.
 * [recordsByStyle] holds the exact bytes that were cut out of each style entry.
 */
data class RemovedWidget(
    val id: Long,
    val label: String,
    val widgetType: Int,
    val sequenceId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val recordsByStyle: Map<String, ByteArray>,
) {
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
    val styleNames: List<String>,
    val selectedStyle: String,
    val preview: PreviewFrame,
    val referencePreview: PreviewFrame?,
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
    /** Size of the container as it stands, measured against the watch's ceiling. */
    val containerBytes: Int = 0,
    val imageCount: Int,
    val validationErrors: List<String>,
    val validationWarnings: List<String>,
    val isDirty: Boolean,
    val thumbnailRefreshed: Boolean = false,
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
        get() = isDirty && !thumbnailRefreshed && validationErrors.isEmpty()

    /** Whether any style of this face can take a replacement background at all. */
    val canReplaceBackground: Boolean
        get() = backgroundStyles.isNotEmpty()

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
        get() = backgroundStyles.isEmpty() && backgroundAddTargets.isNotEmpty()

    /**
     * A backgroundless face with no room left for one. Distinct from
     * [canReplaceBackground] being false: there is nothing wrong with the face, the
     * container is simply too close to the watch's size ceiling already.
     */
    val backgroundWouldNotFit: Boolean
        get() = backgroundStyles.isEmpty() && styleNames.isNotEmpty() &&
            backgroundAddTargets.isEmpty()

    /** Styles an added background would have to skip to stay under the ceiling. */
    val backgroundAddSkipped: List<String>
        get() = if (backgroundAddTargets.isEmpty()) {
            emptyList()
        } else {
            styleNames - backgroundAddTargets.toSet()
        }

    /**
     * Whether the style on the canvas is one of [backgroundStyles].
     *
     * False means a replacement still applies — to the siblings that do carry a
     * background — but nothing about *this* canvas would change, so the page says so
     * instead of looking broken.
     */
    val selectedStyleHasBackground: Boolean
        get() = selectedStyle in backgroundStyles

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

    companion object {
        const val MAX_DIRECT_INSTALL_BYTES: Int = 16 * 1024 * 1024

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
    fun observeProjects(): Flow<List<ProjectSummary>>

    fun observeImageFit(): Flow<ImageFit>

    suspend fun setImageFit(value: ImageFit)

    suspend fun openPackage(download: FacePackage): EditorSnapshot

    suspend fun openProject(projectId: Long): EditorSnapshot

    suspend fun deleteProject(projectId: Long)

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

    suspend fun resizeBackground(width: Int, height: Int): EditorSnapshot

    suspend fun resizeSprite(
        styleName: String,
        sequenceId: Int,
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
