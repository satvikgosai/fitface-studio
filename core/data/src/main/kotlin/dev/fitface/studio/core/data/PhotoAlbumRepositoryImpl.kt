package dev.fitface.studio.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.format.Fit3Apk
import dev.fitface.studio.core.model.ALBUM_PHOTO_HEIGHT
import dev.fitface.studio.core.model.ALBUM_PHOTO_WIDTH
import dev.fitface.studio.core.model.AlbumPhoto
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.KeptPhoto
import dev.fitface.studio.core.model.PhotoAlbum
import dev.fitface.studio.core.model.PhotoAlbumRepository
import dev.fitface.studio.core.model.PhotoAlbumRequest
import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.SentPhoto
import dev.fitface.studio.core.model.WatchFaceException
import dev.fitface.studio.core.model.tinted
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dev.fitface.studio.core.model.DraftPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.photoAlbumPreferences by preferencesDataStore(name = "photo_album")

/**
 * The Photos face's album as this phone keeps it, under `filesDir/photo-album/<face>/`:
 * `album.json` (the photos in order, where each was placed, the one shown), each photo exactly
 * as sent (`<md5>.png`), and the reduced original it was drawn from (`<md5>.source.jpg`), so a
 * sent photo can be shown, sent again or placed again without picking it again.
 *
 * The names are also written to the `photo_album` preferences, the only record before copies
 * were kept, so an older build still sees the same album; a record there with no `album.json`
 * is read as photos sent before copies were kept.
 */
@Singleton
class PhotoAlbumRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: FaceCatalogRepository,
    private val imageSource: AndroidImageSource,
) : PhotoAlbumRepository {
    private val files = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Draft writes outlive the page that asks for them; see [saveDraft]. */
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The latest draft write, for tests to wait on. */
    @Volatile
    internal var draftWrite: Job? = null

    override suspend fun album(face: CatalogFace, onProgress: (DownloadProgress) -> Unit): PhotoAlbum {
        val downloaded = catalog.downloadPackage(face, face.styles.firstOrNull()?.id ?: 0, onProgress)
        val declared = withContext(Dispatchers.Default) {
            runCatching { Fit3Apk.photoAlbumFace(downloaded.copyBytes()) }.getOrNull()
        } ?: throw WatchFaceException(
            "This face's package does not say it takes photos, so none can be sent to it.",
            "no layer_background with gallery_menu in ${face.faceId}",
        )
        val stored = files.withLock { stored(declared.faceId) }
        val sent = withContext(Dispatchers.IO) { sentPhotos(declared.faceId, stored) }
        return PhotoAlbum(declared, sent, stored.shown?.takeIf { shown -> sent.any { it.name == shown } })
    }

    /** The photos [stored] lists, with each kept copy read back. */
    private fun sentPhotos(faceId: Int, stored: StoredAlbum): List<SentPhoto> = stored.sent.map { photo ->
        SentPhoto(
            name = photo.name,
            preview = runCatching { frame(photoFile(faceId, photo.name)) }.getOrNull(),
            placement = photo.placement()?.takeIf { sourceFile(faceId, photo.name).isFile },
        )
    }

    override suspend fun preview(uri: String): PreviewFrame = withContext(Dispatchers.IO) {
        readable { imageSource.preview(uri) }
    }

    override suspend fun encode(uri: String, placement: ImagePlacement): AlbumPhoto =
        withContext(Dispatchers.IO) {
            // Drawn onto black and coloured once, as a background is: the watch shows the photo
            // full-face, and the colour is the Background page's rule.
            val pixels = readable { imageSource.decode(uri, ALBUM_PHOTO_WIDTH, ALBUM_PHOTO_HEIGHT, placement) }
            AlbumPhoto(png(placement.tint?.let { tinted(pixels, it) } ?: pixels))
        }

    override suspend fun encode(source: PreviewFrame, placement: ImagePlacement): AlbumPhoto =
        withContext(Dispatchers.Default) {
            val pixels = imageSource.decode(source, ALBUM_PHOTO_WIDTH, ALBUM_PHOTO_HEIGHT, placement)
            AlbumPhoto(png(placement.tint?.let { tinted(pixels, it) } ?: pixels))
        }

    override suspend fun keptSource(faceId: Int, name: String): PreviewFrame? = withContext(Dispatchers.IO) {
        if (!PhotoAlbumRequest.isPhotoName(name)) return@withContext null
        runCatching { frame(sourceFile(faceId, name)) }.getOrNull()
    }

    override suspend fun keptPhoto(faceId: Int, name: String): AlbumPhoto? = withContext(Dispatchers.IO) {
        if (!PhotoAlbumRequest.isPhotoName(name)) return@withContext null
        // Read back through the same checks a fresh photo passes, and only as the name it was
        // sent under: a damaged or swapped file is not sent as that photo.
        runCatching { AlbumPhoto(photoFile(faceId, name).readBytes()) }.getOrNull()?.takeIf { it.fileName == name }
    }

    override suspend fun recordAlbum(faceId: Int, sent: List<String>, shown: String?, keep: List<KeptPhoto>): List<SentPhoto> {
        require(sent.all(PhotoAlbumRequest::isPhotoName) && sent.distinct().size == sent.size)
        require(keep.all { it.photo.fileName in sent })
        return withContext(Dispatchers.IO) {
            val album = files.withLock {
                val folder = folder(faceId).apply { mkdirs() }
                val before = stored(faceId).sent.associateBy { it.name }
                // Copies first, then the list that names them, so the list never names a file
                // that is not there yet.
                keep.forEach { kept ->
                    atomically(photoFile(faceId, kept.photo.fileName)) { it.writeBytes(kept.photo.copyBytes()) }
                    atomically(sourceFile(faceId, kept.photo.fileName)) { it.writeBytes(jpeg(kept.source)) }
                }
                val placed = keep.associate { it.photo.fileName to it.placement }
                val album = StoredAlbum(
                    sent = sent.map { name ->
                        placed[name]?.let { StoredPhoto.of(name, it) } ?: before[name] ?: StoredPhoto(name)
                    },
                    shown = shown?.takeIf { it in sent },
                )
                atomically(File(folder, ALBUM_FILE)) { it.writeText(json.encodeToString(StoredAlbum.serializer(), album)) }
                // Whatever the list no longer names goes: removed photos, replaced versions and
                // scratch files a killed write left behind.
                val wanted = sent.flatMap { listOf(it, sourceName(it)) }.toSet() + ALBUM_FILE + DRAFT_DIR
                folder.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
                album
            }
            context.photoAlbumPreferences.edit { it[sentKey(faceId)] = sent.joinToString(",") }
            sentPhotos(faceId, album)
        }
    }

    override suspend fun draft(faceId: Int): List<DraftPhoto> = withContext(Dispatchers.IO) {
        files.withLock {
            val folder = draftFolder(faceId)
            val stored = File(folder, DRAFT_FILE).takeIf { it.isFile }
                ?.let { runCatching { json.decodeFromString(StoredDraft.serializer(), it.readText()) }.getOrNull() }
                ?: return@withLock emptyList()
            stored.photos.filter { it.key.matches(DraftKey) }.mapNotNull { photo ->
                val source = runCatching { frame(File(folder, photo.key + ".jpg")) }.getOrNull() ?: return@mapNotNull null
                val placement = photo.placement.placement() ?: return@mapNotNull null
                DraftPhoto(photo.key, source, placement, photo.replaces?.takeIf(PhotoAlbumRequest::isPhotoName))
            }
        }
    }

    override fun saveDraft(faceId: Int, photos: List<DraftPhoto>) {
        require(photos.all { it.key.matches(DraftKey) }) { "A draft key is not a UUID" }
        draftWrite = writes.launch {
            runCatching {
                files.withLock {
                    val folder = draftFolder(faceId)
                    if (photos.isEmpty()) {
                        folder.deleteRecursively()
                        return@withLock
                    }
                    folder.mkdirs()
                    // Each original is written once, under its key; a change of place or colour
                    // rewrites only the list.
                    photos.forEach { photo ->
                        val file = File(folder, photo.key + ".jpg")
                        if (!file.isFile) atomically(file) { it.writeBytes(jpeg(photo.source)) }
                    }
                    val draft = StoredDraft(photos.map { StoredDraftPhoto(it.key, StoredPhoto.of("", it.placement), it.replaces) })
                    atomically(File(folder, DRAFT_FILE)) { it.writeText(json.encodeToString(StoredDraft.serializer(), draft)) }
                    val wanted = photos.map { it.key + ".jpg" }.toSet() + DRAFT_FILE
                    folder.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
                }
            }
        }
    }

    /** The album file, or the names-only record an earlier build kept, or nothing. */
    private suspend fun stored(faceId: Int): StoredAlbum {
        val file = File(folder(faceId), ALBUM_FILE)
        if (file.isFile) {
            runCatching { json.decodeFromString(StoredAlbum.serializer(), file.readText()) }.getOrNull()
                ?.let { album -> return album.copy(sent = album.sent.filter { PhotoAlbumRequest.isPhotoName(it.name) }.distinctBy { it.name }) }
        }
        val names = context.photoAlbumPreferences.data.first()[sentKey(faceId)].orEmpty()
            .split(',').filter(PhotoAlbumRequest::isPhotoName).distinct()
        return StoredAlbum(names.map(::StoredPhoto))
    }

    private fun folder(faceId: Int) = File(context.filesDir, "photo-album/$faceId")

    private fun draftFolder(faceId: Int) = File(folder(faceId), DRAFT_DIR)

    private fun photoFile(faceId: Int, name: String) = File(folder(faceId), name)

    private fun sourceFile(faceId: Int, name: String) = File(folder(faceId), sourceName(name))

    private fun sourceName(name: String) = name.removeSuffix(".png") + ".source.jpg"

    /** Written beside its target under a unique name, then renamed over it. */
    private inline fun atomically(target: File, write: (File) -> Unit) {
        val scratch = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            write(scratch)
            if (!scratch.renameTo(target)) throw java.io.IOException("Could not save ${target.name}")
        } finally {
            scratch.delete()
        }
    }

    private fun frame(file: File): PreviewFrame {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: throw java.io.IOException("Unreadable ${file.name}")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return PreviewFrame(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    /** The reduced original, as a JPEG: it is drawn onto black when placed, so nothing is lost. */
    private fun jpeg(frame: PreviewFrame): ByteArray {
        val bitmap = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Opaque 8-bit RGB, which is what Android writes for a bitmap that says it has no alpha
     * and what the stock plugin sends.
     */
    private fun png(argb: IntArray): ByteArray {
        val bitmap = Bitmap.createBitmap(ALBUM_PHOTO_WIDTH, ALBUM_PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(argb, 0, ALBUM_PHOTO_WIDTH, 0, 0, ALBUM_PHOTO_WIDTH, ALBUM_PHOTO_HEIGHT)
            bitmap.setHasAlpha(false)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private inline fun <T> readable(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        throw WatchFaceException("This photo could not be read. Choose a PNG or JPEG.", "photo decode failed", error)
    }

    private fun sentKey(faceId: Int) = stringPreferencesKey("sent_$faceId")

    private companion object {
        const val ALBUM_FILE = "album.json"
        const val DRAFT_DIR = "draft"
        const val DRAFT_FILE = "draft.json"
        val DraftKey = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

@Serializable
private data class StoredDraft(val photos: List<StoredDraftPhoto>)

/** One draft photo: its key names `<key>.jpg`, its placement as an album entry's. */
@Serializable
private data class StoredDraftPhoto(val key: String, val placement: StoredPhoto, val replaces: String? = null)

@Serializable
private data class StoredAlbum(val sent: List<StoredPhoto>, val shown: String? = null)

/** One photo in `album.json`; no fit means no placement was kept for it. */
@Serializable
private data class StoredPhoto(
    val name: String,
    val fit: String? = null,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val tint: Int? = null,
) {
    fun placement(): ImagePlacement? = fit?.let { name ->
        ImageFit.entries.firstOrNull { it.name == name }
            ?.let { ImagePlacement(fit = it, zoom = zoom, offsetX = offsetX, offsetY = offsetY, tint = tint) }
    }

    companion object {
        fun of(name: String, placement: ImagePlacement) = StoredPhoto(name, placement.fit.name,
            placement.zoom, placement.offsetX, placement.offsetY, placement.tint)
    }
}
