package dev.fitface.studio.core.delivery

import dev.fitface.studio.core.model.PhotoAlbumRequest
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * The watch-face service commands that fill and empty the Photos face's album.
 *
 * Every layout is the stock plugin's own (`AddImageListRequestBuilder`,
 * `SetEditInfoRequestBuilder`, `DeleteImageListRequestBuilder`, `GetEditInfoRequestBuilder`,
 * `CancelEditInfoRequestBuilder` and `SetCurrentBandFaceRequestBuilder`, plugin 2.2.14), read
 * from the code. None of it has been seen on a watch yet; `docs/direct-install.md` keeps the
 * evidence level. The first byte is the command, with `0x80` for the plugin's variable
 * format and `0x40` for a reply; parameters follow as `<id> <value>` or `<id> <length> <bytes>`.
 */
internal object PhotoAlbumProtocol {
    private const val SET_CURRENT_FACE = 0x03
    private const val GET_EDIT_INFO = 0x06
    private const val SET_EDIT_INFO = 0x07
    private const val CANCEL_EDIT_INFO = 0x08
    private const val ADD_IMAGE_LIST = 0x0B
    private const val DELETE_IMAGE_LIST = 0x0C
    private const val VARIABLE_FORMAT = 0x80
    private const val REPLY = 0x40

    // Parameter IDs, from the plugin's MessageParameterValue.
    private const val WF_ID = 0x04
    private const val BACKGROUND = 0x13
    private const val NUM_OF_PARAMS = 0x18
    private const val RESULT_STATUS = 0x1A
    private const val IMAGE_COUNT = 0x1B
    private const val IMAGE_NAME = 0x1C
    private const val WF_SAMPLER_ID = 0x1D
    private const val IS_SELECTED = 0x1F

    /** The Photos face's one style, as its package's thumbnail `SM-R390_00254_2_0.png` names it. */
    private const val SAMPLER = 0

    /**
     * Sent before any photo, as the plugin does when its Photos settings open. Whether the watch
     * needs it before [setEditInfo] is unknown; sending it keeps to the plugin's order.
     */
    fun opening(request: PhotoAlbumRequest): List<ByteArray> = listOf(getEditInfo(request.faceId))

    /**
     * Sent once every photo has transferred: the photos added, then those deleted (a photo placed
     * again is both), then the face's background list with the shown one marked, then, unless the
     * request only deletes, the face made current so the photo is on the wrist.
     */
    fun closing(request: PhotoAlbumRequest): List<ByteArray> = buildList {
        if (request.photos.isNotEmpty()) add(addImageList(request.faceId, request.photos.map { it.fileName }))
        if (request.removed.isNotEmpty()) add(deleteImageList(request.faceId, request.removed))
        add(setEditInfo(request.faceId, request.backgrounds, request.selected))
        if (!request.isRemoval) add(setCurrentFace(request.faceId))
    }

    /** What the plugin sends when its Photos settings close without saving. */
    fun abandon(request: PhotoAlbumRequest): ByteArray = command(CANCEL_EDIT_INFO, request.faceId)

    fun getEditInfo(faceId: Int): ByteArray = command(GET_EDIT_INFO, faceId)

    /** `0B 04 <face> 1B <n> (1C <length> <name>)…` */
    fun addImageList(faceId: Int, names: List<String>): ByteArray =
        imageList(ADD_IMAGE_LIST, faceId, names)

    /** `8C 04 <face> 1B <n> (1C <length> <name>)…`, in the plugin's variable format. */
    fun deleteImageList(faceId: Int, names: List<String>): ByteArray =
        imageList(VARIABLE_FORMAT or DELETE_IMAGE_LIST, faceId, names)

    /**
     * `07 18 02 04 <face> 1B <n> (13 <length> <name> 1F <selected>)…`
     *
     * Only the background setting. The plugin also sends `0D <clock type>` and `1E <clock
     * colour>` after it, because its own copy of the face's settings lists them; the face's
     * store package lists the background alone, so this app has no clock values to send and
     * leaves the watch's as they are. `18` counts the face ID plus each setting.
     */
    fun setEditInfo(faceId: Int, backgrounds: List<String>, selected: String): ByteArray {
        require(backgrounds.size in 1..255 && selected in backgrounds) { "No background to show" }
        return ByteArrayOutputStream().apply {
            write(SET_EDIT_INFO)
            write(NUM_OF_PARAMS)
            write(2)
            write(WF_ID)
            write(faceId)
            write(IMAGE_COUNT)
            write(backgrounds.size)
            backgrounds.forEach { name ->
                val bytes = name.ascii()
                write(BACKGROUND)
                write(bytes.size)
                write(bytes)
                write(IS_SELECTED)
                write(if (name == selected) 1 else 0)
            }
        }.toByteArray()
    }

    /** `03 04 <face> 1D <sampler>`, the command that makes a face the current one. */
    fun setCurrentFace(faceId: Int): ByteArray =
        byteArrayOf(SET_CURRENT_FACE.toByte(), WF_ID.toByte(), faceId.toByte(), WF_SAMPLER_ID.toByte(), SAMPLER.toByte())

    /**
     * Why the watch refused an album command, or null for success or anything not understood.
     *
     * Replies go to the plugin's component, as install replies do, so this may never run. The
     * layouts are the plugin's parsers': add-image-list `4B 04 <face> 1A <status> …` (1 success,
     * 2 too many photos, 3 a wrong file name), set-edit-info `47 1A <result> …`, and
     * delete-image-list `… 04 <face> 1B <n> (1C <length> <name> 1A <deleted>)…`.
     */
    fun refusal(reply: ByteArray): String? {
        if (reply.size < 3 || reply[0].toInt() and REPLY == 0) return null
        return when (reply[0].toInt() and 0x3F) {
            ADD_IMAGE_LIST -> if (reply.size >= 5 && reply[1].toInt() == WF_ID &&
                reply[3].toInt() == RESULT_STATUS
            ) {
                when (reply[4].toInt()) {
                    2 -> "The watch's Photos face is full. Remove photos, then send again."
                    3 -> "The watch refused a photo's file name."
                    else -> null
                }
            } else {
                null
            }
            SET_EDIT_INFO -> if (reply[1].toInt() == RESULT_STATUS && reply[2].toInt() != 1) {
                "The watch refused the Photos face's new settings."
            } else {
                null
            }
            else -> null
        }
    }

    private fun command(id: Int, faceId: Int): ByteArray =
        byteArrayOf(id.toByte(), WF_ID.toByte(), faceId.toByte())

    private fun imageList(id: Int, faceId: Int, names: List<String>): ByteArray {
        require(names.size in 1..255) { "No photo named" }
        return ByteArrayOutputStream().apply {
            write(id)
            write(WF_ID)
            write(faceId)
            write(IMAGE_COUNT)
            write(names.size)
            names.forEach { name ->
                val bytes = name.ascii()
                write(IMAGE_NAME)
                write(bytes.size)
                write(bytes)
            }
        }.toByteArray()
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII).also {
        require(it.size in 1..255 && all { char -> char.code in 0x21..0x7E }) { "Bad name $this" }
    }
}
