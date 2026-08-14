package dev.fitface.studio.core.format

/**
 * Encoder for [IMAGE_INDEXED8] rasters: a fixed 256-entry BGRA palette followed
 * by one index byte per pixel.
 *
 * Because the palette length is fixed, re-encoding an indexed background keeps
 * the record byte-for-byte the same length as the one it replaces. That is what
 * lets background replacement stay a same-size patch on indexed faces instead of
 * a structural relocation.
 */
internal object IndexedImage {
    /**
     * Median-cut quantisation of [argb] into at most 256 opaque colours.
     * Returns palette bytes followed by the index plane.
     */
    fun quantize(argb: IntArray): ByteArray {
        require(argb.isNotEmpty()) { "cannot quantize an empty image" }
        val palette = buildPalette(argb)
        val output = ByteArray(INDEXED_PALETTE_BYTES + argb.size)
        palette.forEachIndexed { index, color ->
            val base = index * 4
            output[base] = (color and 0xFF).toByte()
            output[base + 1] = (color ushr 8 and 0xFF).toByte()
            output[base + 2] = (color ushr 16 and 0xFF).toByte()
            output[base + 3] = 0xFF.toByte()
        }
        // Exact-match cache first; most watch-face art has far fewer than 256
        // distinct colours after quantisation, so this resolves nearly every pixel.
        val exact = HashMap<Int, Int>(palette.size * 2)
        palette.forEachIndexed { index, color -> exact.putIfAbsent(color, index) }
        argb.forEachIndexed { pixel, color ->
            val rgb = color and 0x00FF_FFFF
            val index = exact[rgb] ?: nearest(palette, rgb).also { exact[rgb] = it }
            output[INDEXED_PALETTE_BYTES + pixel] = index.toByte()
        }
        return output
    }

    private fun buildPalette(argb: IntArray): IntArray {
        val distinct = LinkedHashSet<Int>()
        for (color in argb) {
            distinct += color and 0x00FF_FFFF
            if (distinct.size > INDEXED_PALETTE_ENTRIES) break
        }
        if (distinct.size <= INDEXED_PALETTE_ENTRIES) {
            val palette = IntArray(INDEXED_PALETTE_ENTRIES)
            distinct.forEachIndexed { index, color -> palette[index] = color }
            // Unused slots repeat the last real colour so no index can decode to
            // an accidental black.
            val fill = distinct.lastOrNull() ?: 0
            for (index in distinct.size until INDEXED_PALETTE_ENTRIES) palette[index] = fill
            return palette
        }

        var boxes = listOf(argb.map { it and 0x00FF_FFFF }.toIntArray())
        while (boxes.size < INDEXED_PALETTE_ENTRIES) {
            val target = boxes.filter { it.size > 1 }.maxByOrNull(::spread) ?: break
            val split = split(target) ?: break
            boxes = boxes.flatMap { if (it === target) split.toList() else listOf(it) }
        }
        val palette = IntArray(INDEXED_PALETTE_ENTRIES)
        boxes.forEachIndexed { index, box -> palette[index] = average(box) }
        val fill = palette[(boxes.size - 1).coerceAtLeast(0)]
        for (index in boxes.size until INDEXED_PALETTE_ENTRIES) palette[index] = fill
        return palette
    }

    private fun spread(box: IntArray): Int {
        var minRed = 255
        var maxRed = 0
        var minGreen = 255
        var maxGreen = 0
        var minBlue = 255
        var maxBlue = 0
        for (color in box) {
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            if (red < minRed) minRed = red
            if (red > maxRed) maxRed = red
            if (green < minGreen) minGreen = green
            if (green > maxGreen) maxGreen = green
            if (blue < minBlue) minBlue = blue
            if (blue > maxBlue) maxBlue = blue
        }
        return maxOf(maxRed - minRed, maxGreen - minGreen, maxBlue - minBlue)
    }

    private fun split(box: IntArray): Array<IntArray>? {
        var minRed = 255
        var maxRed = 0
        var minGreen = 255
        var maxGreen = 0
        var minBlue = 255
        var maxBlue = 0
        for (color in box) {
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            if (red < minRed) minRed = red
            if (red > maxRed) maxRed = red
            if (green < minGreen) minGreen = green
            if (green > maxGreen) maxGreen = green
            if (blue < minBlue) minBlue = blue
            if (blue > maxBlue) maxBlue = blue
        }
        val redRange = maxRed - minRed
        val greenRange = maxGreen - minGreen
        val blueRange = maxBlue - minBlue
        val shift = when (maxOf(redRange, greenRange, blueRange)) {
            redRange -> 16
            greenRange -> 8
            else -> 0
        }
        val sorted = box.sortedBy { (it ushr shift) and 0xFF }.toIntArray()
        val middle = sorted.size / 2
        if (middle == 0 || middle == sorted.size) return null
        return arrayOf(sorted.copyOfRange(0, middle), sorted.copyOfRange(middle, sorted.size))
    }

    private fun average(box: IntArray): Int {
        if (box.isEmpty()) return 0
        var red = 0L
        var green = 0L
        var blue = 0L
        for (color in box) {
            red += color ushr 16 and 0xFF
            green += color ushr 8 and 0xFF
            blue += color and 0xFF
        }
        val count = box.size
        return (((red / count).toInt()) shl 16) or
            (((green / count).toInt()) shl 8) or
            ((blue / count).toInt())
    }

    private fun nearest(palette: IntArray, rgb: Int): Int {
        val red = rgb ushr 16 and 0xFF
        val green = rgb ushr 8 and 0xFF
        val blue = rgb and 0xFF
        var best = 0
        var bestDistance = Int.MAX_VALUE
        palette.forEachIndexed { index, color ->
            val deltaRed = (color ushr 16 and 0xFF) - red
            val deltaGreen = (color ushr 8 and 0xFF) - green
            val deltaBlue = (color and 0xFF) - blue
            val distance = deltaRed * deltaRed + deltaGreen * deltaGreen + deltaBlue * deltaBlue
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }
}
