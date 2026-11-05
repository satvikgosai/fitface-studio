package dev.fitface.studio.core.data

import dev.fitface.studio.core.model.PreviewFrame
import dev.fitface.studio.core.model.VideoClip
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A video's read frames kept in a cache file, three bytes a pixel, rather than in memory: a
 * minute of cover-sized frames is a few hundred megabytes. A frame is read back when asked for
 * and the last few are held, which is all a preview stepping through them or an add taking
 * its kept frames one by one needs. Read frames are opaque, so no alpha is kept.
 */
internal class CachedVideoFrames private constructor(
    /** Where the frames are: what [VideoFrameFiles.releaseExcept] keeps while they are in use. */
    val file: File,
    override val size: Int,
    private val width: Int,
    private val height: Int,
) : AbstractList<PreviewFrame>() {
    private val frameBytes = width * height * VideoClip.CACHED_BYTES_PER_PIXEL
    private val recent = object : LinkedHashMap<Int, PreviewFrame>(RECENT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PreviewFrame>?) = size > RECENT
    }

    override fun get(index: Int): PreviewFrame {
        if (index !in 0 until size) throw IndexOutOfBoundsException("frame $index of $size")
        synchronized(recent) {
            recent[index]?.let { return it }
            val bytes = ByteArray(frameBytes)
            RandomAccessFile(file, "r").use { cache ->
                cache.seek(index.toLong() * frameBytes)
                cache.readFully(bytes)
            }
            val argb = IntArray(width * height) { pixel ->
                val at = pixel * 3
                (0xFF shl 24) or ((bytes[at].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() and 0xFF)
            }
            return PreviewFrame(width, height, argb).also { recent[index] = it }
        }
    }

    /** Writes [width] × [height] frames to [file] in order, then hands them back as [CachedVideoFrames]. */
    class Writer(private val file: File, private val width: Int, private val height: Int) : Closeable {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        private val row = ByteArray(width * VideoClip.CACHED_BYTES_PER_PIXEL)
        var count = 0
            private set

        fun add(frame: PreviewFrame) {
            require(frame.width == width && frame.height == height) { "every frame is ${width}x$height" }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val color = frame.argb[y * width + x]
                    row[x * 3] = (color ushr 16).toByte()
                    row[x * 3 + 1] = (color ushr 8).toByte()
                    row[x * 3 + 2] = color.toByte()
                }
                out.write(row)
            }
            count++
        }

        fun finish(): CachedVideoFrames {
            out.close()
            return CachedVideoFrames(file, count, width, height)
        }

        override fun close() = out.close()
    }

    private companion object {
        const val RECENT = 6
    }
}

/**
 * Where read videos' frames are cached, one file a read, and when they are deleted.
 *
 * The editor says which read it still uses ([releaseExcept], from
 * [WatchFaceRepository.releaseVideoFrames]) whenever that changes: a pick, a read of another
 * window, an add, a discard, leaving the editor. Everything else goes then. As a backstop a
 * new read deletes all but the latest finished one, so however a release is missed, no more
 * than two reads are ever cached, and a run's first read clears what an earlier run left. A
 * file still being written is never deleted from under its read; a read that fails or is
 * cancelled deletes its own.
 */
internal class VideoFrameFiles(private val directory: File) {
    private val writing = mutableSetOf<File>()
    private var latest: File? = null

    /** A new file for a read's frames, after deleting every file but the latest finished read's. */
    @Synchronized
    fun next(): File {
        directory.mkdirs()
        delete { it != latest }
        return File.createTempFile("frames", ".rgb", directory).also { writing += it }
    }

    /** [file]'s read is over: kept as the latest when it [succeeded], deleted when it did not. */
    @Synchronized
    fun finished(file: File, succeeded: Boolean) {
        writing -= file
        if (succeeded) latest = file else file.delete()
    }

    /**
     * Deletes every finished read's file but [keep], what is still being placed, and the latest
     * read, which may be about to replace it. Null keeps none: nothing is being placed.
     */
    @Synchronized
    fun releaseExcept(keep: File?) {
        delete { it != keep && (keep == null || it != latest) }
        if (latest?.exists() != true) latest = null
    }

    private fun delete(which: (File) -> Boolean) {
        directory.listFiles()?.forEach { file -> if (file !in writing && which(file)) file.delete() }
    }
}
