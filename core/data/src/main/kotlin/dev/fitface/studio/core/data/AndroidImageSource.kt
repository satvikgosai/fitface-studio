package dev.fitface.studio.core.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import dev.fitface.studio.core.model.ImageFit
import dev.fitface.studio.core.model.ImagePlacement
import dev.fitface.studio.core.model.PreviewFrame
import javax.inject.Inject

class AndroidImageSource @Inject constructor(
    private val contentResolver: ContentResolver,
) {
    fun preview(uri: String): PreviewFrame {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(parsed)
            ?: throw IllegalArgumentException("The selected image could not be opened")
        boundsStream.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("The selected image dimensions are invalid")
        }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
            sample *= 2
        }
        val decoded = contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: throw IllegalArgumentException("The selected image could not be decoded")
        val bitmap = upright(decoded, orientation(parsed))
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PreviewFrame(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    fun decode(
        uri: String,
        width: Int,
        height: Int,
        placement: ImagePlacement,
    ): IntArray {
        require(width > 0 && height > 0) { "Target dimensions must be positive" }
        val parsed = Uri.parse(uri)
        val orientation = orientation(parsed)
        // Sampled before it is turned, so a quarter-turned photo is measured on its stored
        // axes: its height on screen is its stored width.
        val bounds = readBounds(parsed).let { stored ->
            if (orientation.swapsAxes) {
                BitmapFactory.Options().apply {
                    outWidth = stored.outHeight
                    outHeight = stored.outWidth
                }
            } else {
                stored
            }
        }
        val maximumDecodeWidth = (width * placement.zoom.coerceIn(1f, 8f))
            .toInt()
            .coerceAtLeast(width)
        val maximumDecodeHeight = (height * placement.zoom.coerceIn(1f, 8f))
            .toInt()
            .coerceAtLeast(height)
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= maximumDecodeWidth &&
            bounds.outHeight / (sample * 2) >= maximumDecodeHeight
        ) {
            sample *= 2
        }
        val decoded = contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: throw IllegalArgumentException("The selected image could not be decoded")
        val source = upright(decoded, orientation)
        try {
            return placed(source, width, height, placement)
        } finally {
            source.recycle()
        }
    }

    /**
     * [decode] for a frame already in memory: a photo's kept, reduced original, placed again.
     * The same drawing, so a photo placed twice the same way comes out the same.
     */
    fun decode(frame: PreviewFrame, width: Int, height: Int, placement: ImagePlacement): IntArray {
        require(width > 0 && height > 0) { "Target dimensions must be positive" }
        val source = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            return placed(source, width, height, placement)
        } finally {
            source.recycle()
        }
    }

    /** [source] drawn onto a black [width] × [height] frame as [placement] says. */
    private fun placed(source: Bitmap, width: Int, height: Int, placement: ImagePlacement): IntArray {
        var output: Bitmap? = null
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val sourceRect = Rect(0, 0, source.width, source.height)
            val targetRect = destinationRect(
                source.width,
                source.height,
                width,
                height,
                placement.fit,
            )
            val zoom = placement.zoom.coerceIn(0.25f, 8f)
            val centerX = targetRect.centerX() + placement.offsetX.coerceIn(-2f, 2f) * width
            val centerY = targetRect.centerY() + placement.offsetY.coerceIn(-2f, 2f) * height
            val transformed = RectF(
                centerX - targetRect.width() * zoom / 2f,
                centerY - targetRect.height() * zoom / 2f,
                centerX + targetRect.width() * zoom / 2f,
                centerY + targetRect.height() * zoom / 2f,
            )
            canvas.drawBitmap(
                source,
                sourceRect,
                transformed,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)
            return pixels
        } finally {
            output?.recycle()
        }
    }

    /** The picture's size as it is meant to be seen, with its EXIF quarter turn applied. */
    fun uprightSize(uri: String): Pair<Int, Int> {
        val parsed = Uri.parse(uri)
        val bounds = readBounds(parsed)
        return if (orientation(parsed).swapsAxes) bounds.outHeight to bounds.outWidth
        else bounds.outWidth to bounds.outHeight
    }

    /**
     * The whole picture scaled to exactly [width] × [height], upright, with its transparency
     * kept: a picture added as a widget is drawn over the face, where a background is
     * flattened onto black by [decode]. Decoded at the coarsest power-of-two sample still at
     * least that size, then filtered down to it.
     */
    fun decodeScaled(uri: String, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "Target dimensions must be positive" }
        val parsed = Uri.parse(uri)
        val orientation = orientation(parsed)
        val stored = readBounds(parsed)
        val (storedWidth, storedHeight) = if (orientation.swapsAxes) height to width else width to height
        var sample = 1
        while (stored.outWidth / (sample * 2) >= storedWidth && stored.outHeight / (sample * 2) >= storedHeight) {
            sample *= 2
        }
        val decoded = contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("The selected image could not be decoded")
        val source = upright(decoded, orientation)
        var output: Bitmap? = null
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(
                source,
                Rect(0, 0, source.width, source.height),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)
            return pixels
        } finally {
            output?.recycle()
            source.recycle()
        }
    }

    /**
     * [frame] scaled to exactly [width] × [height] with its transparency kept, filtered as
     * [decodeScaled] is: a frame of a moving picture, drawn over the face.
     */
    fun scaled(frame: PreviewFrame, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "Target dimensions must be positive" }
        if (frame.width == width && frame.height == height) return frame.argb.copyOf()
        val source = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        var output: Bitmap? = null
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(
                source,
                Rect(0, 0, source.width, source.height),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = IntArray(width * height)
            output.getPixels(pixels, 0, width, 0, 0, width, height)
            return pixels
        } finally {
            output?.recycle()
            source.recycle()
        }
    }

    /**
     * The EXIF orientation of the picked file, or [ExifInterface.ORIENTATION_NORMAL].
     *
     * `BitmapFactory` ignores this tag, and phone cameras commonly store a portrait photo
     * on its side with orientation 6, so a camera photo used to arrive as a sideways
     * background. Any failure to read it means "as stored", which is what it was before.
     */
    private fun orientation(uri: Uri): Int = runCatching {
        contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private val Int.swapsAxes: Boolean
        get() = this in QuarterTurnOrientations

    /** [bitmap] turned and mirrored as its [orientation] tag says, recycling the original. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private fun readBounds(uri: Uri): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("The selected image could not be opened")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("The selected image dimensions are invalid")
        }
        return bounds
    }

    fun resize(frame: PreviewFrame, width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Target dimensions must be positive")
        }
        val output = IntArray(width * height)
        repeat(height) { y ->
            val sourceY = minOf(frame.height - 1, y * frame.height / height)
            repeat(width) { x ->
                val sourceX = minOf(frame.width - 1, x * frame.width / width)
                output[y * width + x] = frame.argb[sourceY * frame.width + sourceX]
            }
        }
        return output
    }

    private fun destinationRect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        fit: ImageFit,
    ): RectF {
        if (fit == ImageFit.STRETCH) {
            return RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        }
        val widthScale = targetWidth.toFloat() / sourceWidth
        val heightScale = targetHeight.toFloat() / sourceHeight
        val scale = if (fit == ImageFit.CONTAIN) {
            minOf(widthScale, heightScale)
        } else {
            maxOf(widthScale, heightScale)
        }
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = (targetWidth - width) / 2f
        val top = (targetHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }
}

private val QuarterTurnOrientations = setOf(
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_TRANSVERSE,
    ExifInterface.ORIENTATION_ROTATE_270,
)
