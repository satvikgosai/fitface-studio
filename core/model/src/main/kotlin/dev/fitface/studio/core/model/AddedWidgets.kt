package dev.fitface.studio.core.model

/**
 * How the editor made a widget it added. Every kind is one appended record with its own
 * artwork and a saved baseline to resize and turn from; only where that artwork came from
 * differs. Shapes are pictures made elsewhere: there is no separate kind for them.
 */
enum class AddedWidgetKind {
    /** Copied from another face. */
    DONOR,

    /** A picture picked on the phone. */
    PICTURE,

    /**
     * A GIF or video clip picked on the phone, played by an Animation. Never saved with the
     * import origins: it is recorded as an added widget with no original, as a moving
     * background is, and recognised from its record.
     */
    MOVING_PICTURE,
}

enum class PictureSizePreset { ORIGINAL, SMALL, MEDIUM, LARGE, FULL }

/** One size a new picture can be added at: a rung of its own resize ladder. */
data class PictureSize(val preset: PictureSizePreset, val percent: Int, val width: Int, val height: Int)

/**
 * A picked picture, decoded once and kept by the repository under [handle] while it is
 * placed.
 *
 * [width] × [height] is its baseline, the size every later resize and turn is drawn from,
 * and [preview] its pixels at that size, which the canvas draws while it is placed.
 */
data class PreparedPicture(
    val handle: String,
    val width: Int,
    val height: Int,
    /** No pixel is even partly transparent, so it is stored without alpha. */
    val opaque: Boolean,
    val sizes: List<PictureSize>,
    val preview: PreviewFrame,
) {
    /**
     * What fits within 128 px: the picture's own size when it already does, since anything
     * else would resample it, and otherwise Medium.
     */
    val defaultSize: PictureSize?
        get() = sizes.firstOrNull { it.preset == PictureSizePreset.ORIGINAL && it.width <= 128 && it.height <= 128 }
            ?: sizes.firstOrNull { it.preset == PictureSizePreset.MEDIUM }
            ?: sizes.firstOrNull { it.preset == PictureSizePreset.ORIGINAL }
            ?: sizes.lastOrNull()

    /** Every size it can be added at, smallest first: its resize ladder. */
    val ladder: List<WidgetSize> get() = widgetResizeLadder(width, height)
}

/**
 * The size a picture of [sourceWidth] × [sourceHeight] is kept at: fitted within the panel,
 * never enlarged.
 *
 * Always a pixel short of the panel's full height. Artwork exactly the panel's size is a
 * background layer to the editor — it can be neither selected nor resized — and the first
 * panel-sized raster is what the Background page replaces. A picture must never become one.
 */
fun pictureBaselineSize(sourceWidth: Int, sourceHeight: Int, panelWidth: Int, panelHeight: Int): Pair<Int, Int> {
    require(sourceWidth > 0 && sourceHeight > 0 && panelWidth > 1 && panelHeight > 1)
    val boxHeight = panelHeight - 1
    if (sourceWidth <= panelWidth && sourceHeight <= boxHeight) return sourceWidth to sourceHeight
    val byWidth = sourceHeight.toLong() * panelWidth <= boxHeight.toLong() * sourceWidth
    return if (byWidth) {
        panelWidth to ((sourceHeight.toLong() * panelWidth + sourceWidth / 2) / sourceWidth).toInt().coerceIn(1, boxHeight)
    } else {
        ((sourceWidth.toLong() * boxHeight + sourceHeight / 2) / sourceHeight).toInt().coerceIn(1, panelWidth) to boxHeight
    }
}

/**
 * The sizes offered for a new picture with a [width] × [height] baseline, smallest first:
 * within 64, 128 and 192 px, the whole panel, and the picture's own size when it was small
 * enough to keep whole ([kept]).
 *
 * Each is a rung of [widgetResizeLadder], the ladder Smaller and Larger step along once it
 * is added, so the size chosen is one they can come back to. Presets that land on the same
 * rung are offered once.
 */
fun pictureSizes(width: Int, height: Int, panelWidth: Int, panelHeight: Int, kept: Boolean): List<PictureSize> {
    val ladder = widgetResizeLadder(width, height)
    fun within(boxWidth: Int, boxHeight: Int, preset: PictureSizePreset) =
        ladder.filter { it.width <= boxWidth && it.height <= boxHeight }.maxByOrNull { it.area }
            ?.let { PictureSize(preset, it.percentOfOriginal, it.width, it.height) }
    return buildList {
        if (kept) ladder.firstOrNull { it.percentOfOriginal == 100 }
            ?.let { add(PictureSize(PictureSizePreset.ORIGINAL, 100, it.width, it.height)) }
        within(64, 64, PictureSizePreset.SMALL)?.let(::add)
        within(128, 128, PictureSizePreset.MEDIUM)?.let(::add)
        within(192, 192, PictureSizePreset.LARGE)?.let(::add)
        within(panelWidth, panelHeight - 1, PictureSizePreset.FULL)?.let(::add)
    }.distinctBy { it.width to it.height }.sortedBy { it.width.toLong() * it.height }
}

/**
 * What adding a [width] × [height] picture costs the container: its 40-byte Static and its
 * image record — 12-byte header, two bytes a pixel, or three with alpha, and the 4-byte
 * trailer. Exactly the growth the insert produces, which is why the editor may show it.
 */
fun pictureAddedBytes(width: Int, height: Int, opaque: Boolean): Int =
    40 + 12 + width * height * (if (opaque) 2 else 3) + 4

/**
 * [color] recoloured to [tint] by its own brightness, its alpha unchanged: white becomes the
 * tint, the shades between keep their steps, and near-black stays as it is. The rule a
 * background tint has always used, so one colour control means the same on a background
 * and on a picture. A picture made white elsewhere takes any colour here.
 */
fun tintedColor(color: Int, tint: Int): Int {
    val red = color ushr 16 and 0xFF
    val green = color ushr 8 and 0xFF
    val blue = color and 0xFF
    val luminance = (77 * red + 150 * green + 29 * blue + 128) ushr 8
    if (luminance <= TintBlackThreshold) return color
    fun channel(shift: Int) = ((tint ushr shift and 0xFF) * luminance + 127) / 255
    return (color and 0xFF00_0000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

fun tinted(argb: IntArray, tint: Int): IntArray = IntArray(argb.size) { tintedColor(argb[it], tint) }

/** Below this brightness a pixel is black and keeps its own value, as `FaceEditor.tintBackgrounds` does. */
const val TintBlackThreshold = 16
