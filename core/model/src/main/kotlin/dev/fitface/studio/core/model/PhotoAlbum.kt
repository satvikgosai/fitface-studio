package dev.fitface.studio.core.model

import java.security.MessageDigest
import java.util.zip.CRC32

/** The watch's own Photos face, the one face the stock plugin fills with the user's photos. */
const val PHOTO_ALBUM_FACE_ID = 254

/** How many of the user's photos the stock plugin lets that face hold. */
const val PHOTO_ALBUM_LIMIT = 7

const val ALBUM_PHOTO_WIDTH = 256
const val ALBUM_PHOTO_HEIGHT = 402

/** A 256 × 402 PNG of a photo is about 300 KB at worst; anything far past that is not one. */
const val MAX_ALBUM_PHOTO_BYTES = 512 * 1024

/**
 * The stock watch plugin's package. Found by this name alone, whatever its version: what the
 * app reads from it is looked for, never assumed to be where one version kept it.
 */
const val FIT3_PLUGIN_PACKAGE = "com.samsung.wearable.fit3plugin"

/**
 * The Photos face's clock as the watch takes it: the option IDs of its clock type and colour,
 * from the stock plugin's own copy of the face's settings.
 */
data class PhotoClock(val type: Int, val colour: Int) {
    init {
        require(type in 1..255 && colour in 1..255) { "Clock options are 1 to 255" }
    }

    companion object {
        /**
         * The face's own clock, as its `info.json` selects it: type 1, colour 1 (white). Sent when
         * nothing else is known, because a send without a clock leaves the watch drawing none.
         */
        val FACE_DEFAULT = PhotoClock(1, 1)
    }
}

/**
 * What a send leaves the Photos face's clock as: [Drawn] in a type and colour, or [None]. A
 * request for no clock leaves the clock's settings out, and the watch then draws none.
 */
sealed interface PhotoClockSetting {
    /** The clock to put in a request: the one drawn, or null for none. */
    val clock: PhotoClock?

    data object None : PhotoClockSetting {
        override val clock: PhotoClock? get() = null
    }

    data class Drawn(override val clock: PhotoClock) : PhotoClockSetting
}

/**
 * One clock type, with the picture the stock plugin shows for it in the phone's language:
 * the clock drawn white on clear at the face's proportions, or null when none could be read.
 */
class PhotoClockType(val id: Int, val picture: PreviewFrame?)

/** One clock colour the watch offers, as `0xAARRGGBB`. */
data class PhotoClockColour(val id: Int, val argb: Int)

/**
 * The clock options the Photos face offers, as the stock plugin installed on this phone lists
 * them, and the ones it starts from. Null wherever they could not be read: the clock is then
 * left as the watch has it.
 */
class PhotoClockOptions(
    val types: List<PhotoClockType>,
    val colours: List<PhotoClockColour>,
    val default: PhotoClock,
) {
    init {
        require(types.isNotEmpty() && colours.isNotEmpty()) { "No clock options" }
        require(types.map { it.id }.distinct().size == types.size && colours.map { it.id }.distinct().size == colours.size) {
            "A clock option is listed twice"
        }
        require(accepts(default)) { "The default clock is not among the options" }
    }

    fun accepts(clock: PhotoClock): Boolean =
        types.any { it.id == clock.type } && colours.any { it.id == clock.colour }

    fun type(id: Int): PhotoClockType? = types.firstOrNull { it.id == id }

    fun colour(id: Int): PhotoClockColour? = colours.firstOrNull { it.id == id }
}

/** Reads the Photos face's clock options from the stock plugin installed on this phone. */
interface PhotoClockSource {
    /** The options for [faceId], or null when the plugin is missing or does not list them. */
    suspend fun options(faceId: Int): PhotoClockOptions?
}

/**
 * The Photos face as its package declares it: its ID, and its own backgrounds, which every
 * background list sent to it starts with.
 */
data class PhotoAlbumFace(val faceId: Int, val preloaded: List<String>)

/**
 * One photo this app has sent the Photos face, as the phone keeps it.
 *
 * @param preview the photo exactly as sent, or null for one sent before copies were kept: the
 *   watch has it, this phone only its name.
 * @param placement where it sits on its kept, reduced original and in what colour; null when
 *   no original is kept, so it cannot be placed again without picking it again.
 */
data class SentPhoto(val name: String, val preview: PreviewFrame?, val placement: ImagePlacement?) {
    val kept: Boolean get() = preview != null
    val changeable: Boolean get() = placement != null
}

/**
 * The Photos face's album as this app can know it: the face, the photos this app has sent it
 * and not removed, in the order the face lists them, and which one it was last asked to show.
 * Photos sent from Galaxy Wearable cannot be listed here; the watch's reply that would name
 * them goes to the stock plugin.
 *
 * @param clock the clock this app last sent, [PhotoClockSetting.None] if it sent the face with
 *   no clock, or null if it never sent one: the watch's own is then unknown here, as Galaxy
 *   Wearable may have changed it.
 * @param clockOptions what the clock can be, or null when the stock plugin could not be read.
 */
data class PhotoAlbum(
    val face: PhotoAlbumFace,
    val sent: List<SentPhoto>,
    val shown: String? = null,
    val clock: PhotoClockSetting? = null,
    val clockOptions: PhotoClockOptions? = null,
) {
    val names: List<String> get() = sent.map { it.name }
    val room: Int get() = (PHOTO_ALBUM_LIMIT - sent.size).coerceAtLeast(0)
}

/**
 * A photo picked, or brought back to be placed again, and not sent yet: kept as a draft so
 * leaving the page, or Android closing the app while the setup sends you to its settings,
 * loses nothing.
 *
 * @param key names its files; it outlives the page that made it.
 * @param source the upright, reduced original it is placed on.
 * @param replaces the sent photo it takes the place of, or null for a new one.
 */
class DraftPhoto(val key: String, val source: PreviewFrame, val placement: ImagePlacement, val replaces: String?)

/**
 * The album as the user has arranged it and not sent: [photos] picked or placed again, the sent
 * photos [takenOut] of it, and the one to be [shown], a sent photo's name or a draft photo's key.
 */
class PhotoDraft(
    val photos: List<DraftPhoto> = emptyList(),
    val takenOut: List<String> = emptyList(),
    val shown: String? = null,
) {
    val isEmpty: Boolean get() = photos.isEmpty() && takenOut.isEmpty() && shown == null
}

/**
 * A photo to keep once the request that adds it has been handed over: the PNG as sent, the
 * reduced original it was drawn from, and where it was placed on that original.
 */
class KeptPhoto(val photo: AlbumPhoto, val source: PreviewFrame, val placement: ImagePlacement)

interface PhotoAlbumRepository {
    /**
     * The Photos face read from its own catalogue package, which is downloaded first if it is
     * not already on the phone, with the photos this app has sent it.
     */
    suspend fun album(face: CatalogFace, onProgress: (DownloadProgress) -> Unit = {}): PhotoAlbum

    /** A picked photo, upright and scaled down for placing it on screen. */
    suspend fun preview(uri: String): PreviewFrame

    /**
     * The photo placed on the 256 × 402 face as [placement] says, in its colour if it has one,
     * encoded as the watch takes it.
     */
    suspend fun encode(uri: String, placement: ImagePlacement): AlbumPhoto

    /** [encode] for a photo placed again, drawn from its kept, reduced original. */
    suspend fun encode(source: PreviewFrame, placement: ImagePlacement): AlbumPhoto

    /** The reduced original kept for a sent photo, or null if none is kept. */
    suspend fun keptSource(faceId: Int, name: String): PreviewFrame?

    /** A sent photo exactly as it was sent, to send again, or null if no copy is kept. */
    suspend fun keptPhoto(faceId: Int, name: String): AlbumPhoto?

    /** The album as arranged and not sent, as last saved; empty when there is none. */
    suspend fun draft(faceId: Int): PhotoDraft

    /**
     * Saves the draft as it now stands, replacing the last one; empty clears it. Returns at once
     * and writes in the background, latest last, so a page that is closing still gets its last
     * change saved.
     */
    fun saveDraft(faceId: Int, draft: PhotoDraft)

    /**
     * Records the album a request has been handed over for: its photos in order, the one shown,
     * the clock it left the face with, and copies of the photos it added. Copies of photos no
     * longer listed are deleted; a null [clock] keeps the one recorded.
     *
     * @return the album's photos as now kept.
     */
    suspend fun recordAlbum(
        faceId: Int,
        sent: List<String>,
        shown: String?,
        keep: List<KeptPhoto> = emptyList(),
        clock: PhotoClockSetting? = null,
    ): List<SentPhoto>
}

/**
 * One file the watch takes over the transfer channel: a face's container, or a photo for the
 * Photos face. Sealed so the transfer knows which descriptor each one gets, and so nothing
 * else can be handed to it.
 */
sealed interface WatchFile {
    val fileName: String
    val size: Int
    val sha256: String

    fun copyBytes(): ByteArray

    /** See [DirectInstallPayload.endsOnWindowBoundary]. */
    val endsOnWindowBoundary: Boolean
        get() = size % DirectInstallPayload.TRANSFER_WINDOW_BYTES == 0
}

/**
 * One photo for the Photos face, prepared the way the stock plugin prepares one: a
 * 256 × 402 PNG named by the MD5 of its own bytes. The checks are here, so a photo of any
 * other shape cannot reach the transfer.
 */
class AlbumPhoto(bytes: ByteArray) : WatchFile {
    private val payload = bytes.copyOf()

    override val size: Int
        get() = payload.size

    init {
        require(payload.size in 1..MAX_ALBUM_PHOTO_BYTES) {
            "A photo for the watch must be 1 to $MAX_ALBUM_PHOTO_BYTES bytes"
        }
        AlbumPhotoFormat.refusal(payload)?.let { throw IllegalArgumentException(it) }
    }

    override val sha256: String = payload.digest("SHA-256")

    /** `<md5>.png`: what the watch stores it as, and what the album commands name. */
    override val fileName: String = payload.digest("MD5") + ".png"

    override fun copyBytes(): ByteArray = payload.copyOf()
}

/**
 * What the watch is asked to do with the Photos face's album, checked before anything is
 * sent. A sibling of `validatedBytes()`, not a use of it: a container never travels this way
 * and a photo never travels that way.
 *
 * [photos] are added and [removed] names deleted; a photo placed again is both, its new PNG
 * added and its old one deleted. With neither, the request only changes which photo the face
 * shows, or its [clock]. Afterwards the face lists [preloaded] then [album] as its backgrounds
 * and shows [selected], with [clock] if one is given and no clock otherwise.
 */
class PhotoAlbumRequest(
    val faceId: Int,
    /** The face's own backgrounds, from its package, which no request adds or deletes. */
    val preloaded: List<String>,
    /** This app's photos on the watch once the request is done, in the order they were sent. */
    val album: List<String>,
    /** Photos to transfer, each of which is in [album]. */
    val photos: List<AlbumPhoto> = emptyList(),
    /** Photos to delete, none of which is in [album]. */
    val removed: List<String> = emptyList(),
    val selected: String,
    /** The clock to set, or null for none: without its settings the watch draws no clock. */
    val clock: PhotoClock? = null,
) {
    init {
        require(faceId in 1..255) { "Face ID must be 1 to 255" }
        require(album.size <= PHOTO_ALBUM_LIMIT) {
            "The Photos face holds at most $PHOTO_ALBUM_LIMIT photos"
        }
        require(preloaded.isNotEmpty() && preloaded.all(::isPreloadedName)) {
            "The face's own backgrounds are not named like ones"
        }
        require((album + removed).all(::isPhotoName)) { "A photo name is not <md5>.png" }
        require((preloaded + album).distinct().size == preloaded.size + album.size) {
            "A background is listed twice"
        }
        require(removed.distinct().size == removed.size && removed.none { it in album }) {
            "A removed photo is still listed"
        }
        val sent = photos.map { it.fileName }
        require(sent.distinct().size == sent.size && album.containsAll(sent)) {
            "Every photo sent is listed once"
        }
        require(selected in preloaded || selected in album) { "The shown background is not listed" }
    }

    /** Every background the face lists afterwards, its own first. */
    val backgrounds: List<String>
        get() = preloaded + album

    /** Only deletes photos: nothing to transfer, and the face is not made the current one. */
    val isRemoval: Boolean
        get() = removed.isNotEmpty() && photos.isEmpty()

    /** Only changes which photo is shown. */
    val isSelection: Boolean
        get() = removed.isEmpty() && photos.isEmpty()

    companion object {
        private val photoName = Regex("[0-9a-f]{32}\\.png")
        private val preloadedName = Regex("[A-Za-z0-9_-]{1,60}\\.png")

        fun isPhotoName(name: String): Boolean = photoName.matches(name)

        fun isPreloadedName(name: String): Boolean = preloadedName.matches(name) && !isPhotoName(name)
    }
}

/**
 * The PNG shape a photo for the watch must have: signature, an IHDR of exactly 256 × 402
 * with 8-bit RGB or RGBA and no interlace, well-formed chunks with correct CRCs, and IEND
 * last. What Android's encoder writes for an opaque bitmap, which is also what the stock
 * plugin sends.
 */
object AlbumPhotoFormat {
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Why [bytes] is not a photo the watch takes, or null if it is one. */
    fun refusal(bytes: ByteArray): String? {
        if (bytes.size < signature.size || !signature.indices.all { bytes[it] == signature[it] }) {
            return "Not a PNG"
        }
        var offset = signature.size
        var chunks = 0
        while (true) {
            if (offset + 12 > bytes.size) return "The PNG is truncated"
            val length = bytes.int(offset)
            if (length < 0 || length > bytes.size - offset - 12) return "A PNG chunk runs past the file"
            val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            val crc = CRC32().apply { update(bytes, offset + 4, length + 4) }.value.toInt()
            if (crc != bytes.int(offset + 8 + length)) return "A PNG chunk's CRC is wrong"
            if (chunks == 0) {
                if (type != "IHDR" || length != 13) return "The PNG does not start with its header"
                val width = bytes.int(offset + 8)
                val height = bytes.int(offset + 12)
                if (width != ALBUM_PHOTO_WIDTH || height != ALBUM_PHOTO_HEIGHT) {
                    return "The PNG is ${width}x$height, not ${ALBUM_PHOTO_WIDTH}x$ALBUM_PHOTO_HEIGHT"
                }
                val depth = bytes[offset + 16].toInt()
                val colour = bytes[offset + 17].toInt()
                if (depth != 8 || colour !in setOf(2, 6)) return "The PNG is not 8-bit RGB or RGBA"
                if (bytes[offset + 18].toInt() != 0 || bytes[offset + 19].toInt() != 0 ||
                    bytes[offset + 20].toInt() != 0
                ) {
                    return "The PNG uses an unsupported compression, filter or interlace"
                }
            }
            chunks++
            offset += 12 + length
            if (type == "IEND") {
                return if (length == 0 && offset == bytes.size) null else "The PNG does not end at IEND"
            }
        }
    }

    private fun ByteArray.int(at: Int): Int =
        (this[at].toInt() and 0xFF shl 24) or (this[at + 1].toInt() and 0xFF shl 16) or
            (this[at + 2].toInt() and 0xFF shl 8) or (this[at + 3].toInt() and 0xFF)
}

private fun ByteArray.digest(algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(this)
        .joinToString(separator = "") { value -> "%02x".format(value.toInt() and 0xff) }
