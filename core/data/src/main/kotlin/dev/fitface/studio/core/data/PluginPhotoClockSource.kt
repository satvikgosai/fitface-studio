package dev.fitface.studio.core.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.format.PhotoClockSettings
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.FIT3_PLUGIN_PACKAGE
import dev.fitface.studio.core.model.PhotoClockOptions
import dev.fitface.studio.core.model.PhotoClockSource
import dev.fitface.studio.core.model.PhotoClockType
import dev.fitface.studio.core.model.PreviewFrame
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Photos face's clock options, read from the stock plugin installed on this phone: its APK
 * carries its own copy of the face's settings and a picture of each clock type, which no store
 * package does. Read at run time through the plugin's package context, as any app may read
 * another's APK assets; nothing of it is bundled or stored here.
 *
 * Found by the plugin's package name alone, whatever its version, and nothing is looked for by
 * a fixed path: the face's `info.json` is whichever folder named after the face holds one that
 * declares it, and each picture is looked for by the folder its `info.json` names. A plugin that
 * moves them still works; one that drops them gives no options, and the watch's clock is then
 * left as it is.
 */
@Singleton
class PluginPhotoClockSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: DiagnosticsLog,
) : PhotoClockSource {
    override suspend fun options(faceId: Int): PhotoClockOptions? = withContext(Dispatchers.IO) {
        try {
            read(faceId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            diagnostics.warn(TAG, "Photos clock options unreadable", error.javaClass.simpleName, error)
            null
        }
    }

    private fun read(faceId: Int): PhotoClockOptions? {
        val assets = try {
            context.createPackageContext(FIT3_PLUGIN_PACKAGE, 0).assets
        } catch (_: PackageManager.NameNotFoundException) {
            diagnostics.info(TAG, "Photos clock options: no plugin")
            return null
        }
        val tree = AssetTree(assets)
        val (folder, parsed) = tree.directories()
            .filter { PhotoClockSettings.isFaceFolder(it.substringAfterLast('/'), faceId) }
            .firstNotNullOfOrNull { folder ->
                val json = tree.read("$folder/info.json", PhotoClockSettings.MAX_JSON_BYTES) ?: return@firstNotNullOfOrNull null
                PhotoClockSettings.parse(json)?.takeIf { it.faceId == faceId }?.let { folder to it }
            }
            ?: run {
                diagnostics.info(TAG, "Photos clock options: none listed", "face=$faceId")
                return null
            }
        val locale = Locale.getDefault()
        val types = parsed.types.map { type ->
            PhotoClockType(type.id, type.picture?.let { picture(tree, folder, it, locale) })
        }
        diagnostics.info(TAG, "Photos clock options read",
            "folder=$folder types=${types.size} pictures=${types.count { it.picture != null }} colours=${parsed.colours.size}")
        return PhotoClockOptions(types, parsed.colours, parsed.default)
    }

    /**
     * The picture [name] names (`clocktype/Photos_clocktype_1.png`), in the phone's language:
     * from the folder of that name nearest the face's own, since the plugin keeps its pictures
     * apart from the face's files.
     */
    private fun picture(tree: AssetTree, faceFolder: String, name: String, locale: Locale): PreviewFrame? {
        val sub = name.substringBeforeLast('/', missingDelimiterValue = "")
        val candidates = if (sub.isEmpty()) {
            listOf(faceFolder)
        } else {
            tree.directories().filter { it == sub || it.endsWith("/$sub") }
                .sortedByDescending { commonPrefix(it, faceFolder) }
        }
        for (folder in candidates) {
            val base = folder.removeSuffix(sub).removeSuffix("/")
            val chosen = PhotoClockSettings.pictureFor(name, locale.language, locale.country, tree.list(folder))
                ?: continue
            val path = if (base.isEmpty()) chosen else "$base/$chosen"
            return tree.read(path, MAX_PICTURE_BYTES)?.let(::frame) ?: continue
        }
        return null
    }

    private fun frame(bytes: ByteArray): PreviewFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_PICTURE_SIDE || bounds.outHeight !in 1..MAX_PICTURE_SIDE) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PreviewFrame(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private fun commonPrefix(a: String, b: String): Int = a.commonPrefixWith(b).length

    /**
     * The plugin's asset folders, walked once and no further than a few levels, listing at most
     * a few hundred: enough for any layout the plugin has used, and bounded if it grows. Names
     * with a dot are taken for files and not walked into.
     */
    private class AssetTree(private val assets: AssetManager) {
        private val listings = mutableMapOf<String, List<String>>()
        private var lists = 0

        fun list(folder: String): List<String> = listings.getOrPut(folder) {
            if (lists++ >= MAX_LISTS) emptyList() else runCatching { assets.list(folder)?.toList() }.getOrNull().orEmpty()
        }

        private val folders: List<String> by lazy {
            val found = mutableListOf<String>()
            // The face files have always been under `watchface`; looked at first, not only there.
            val queue = ArrayDeque(
                list("").filter(::isFolderName).sortedBy { if (it == "watchface") 0 else 1 }.map { it to 1 },
            )
            while (queue.isNotEmpty()) {
                val (folder, depth) = queue.removeFirst()
                val children = list(folder)
                if (children.isEmpty()) continue
                found += folder
                if (depth < MAX_DEPTH) {
                    children.filter(::isFolderName).forEach { queue.addLast("$folder/$it" to depth + 1) }
                }
            }
            found
        }

        fun directories(): List<String> = folders

        fun read(path: String, limit: Int): ByteArray? = runCatching {
            assets.open(path, AssetManager.ACCESS_STREAMING).use { it.readAtMost(limit) }
        }.getOrNull()

        private fun isFolderName(name: String) = '.' !in name
    }

    private companion object {
        const val TAG = "PhotoClock"
        const val MAX_DEPTH = 4
        const val MAX_LISTS = 600
        const val MAX_PICTURE_BYTES = 1024 * 1024
        const val MAX_PICTURE_SIDE = 1024
    }
}

/** The whole stream if it is at most [limit] bytes, or null if it is longer. */
private fun InputStream.readAtMost(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > limit) return null
    }
}
