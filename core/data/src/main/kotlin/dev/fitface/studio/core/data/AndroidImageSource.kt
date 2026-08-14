package dev.fitface.studio.core.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
        val bitmap = contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: throw IllegalArgumentException("The selected image could not be decoded")
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
        val bounds = readBounds(parsed)
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
        val source = contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: throw IllegalArgumentException("The selected image could not be decoded")
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
            source.recycle()
        }
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
