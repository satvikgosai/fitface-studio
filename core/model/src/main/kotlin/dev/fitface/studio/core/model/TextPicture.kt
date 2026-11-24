package dev.fitface.studio.core.model

/**
 * The fonts every Android phone carries, by the family names the platform resolves. Each
 * phone draws them in its own typeface, which is fine: the text is added as a picture, so
 * the watch shows exactly what this phone drew.
 */
enum class PhoneFont(val family: String, val bold: Boolean = false) {
    SANS("sans-serif"),
    SANS_BOLD("sans-serif", bold = true),
    SANS_LIGHT("sans-serif-light"),
    CONDENSED("sans-serif-condensed"),
    CONDENSED_BOLD("sans-serif-condensed", bold = true),
    SERIF("serif"),
    SERIF_BOLD("serif", bold = true),
    MONOSPACE("monospace"),
    TYPEWRITER("serif-monospace"),
    HANDWRITING("cursive"),
    CASUAL("casual"),
    SMALL_CAPITALS("sans-serif-smallcaps"),
}

/** What a text is drawn in: one of the phone's fonts, or a font file picked on the phone. */
sealed interface TextFont {
    data class Phone(val font: PhoneFont) : TextFont

    /** A TTF or OTF file, read each time the text is drawn and never kept; [name] is its file name. */
    data class File(val uri: String, val name: String) : TextFont
}

/**
 * Text to be drawn as a picture: [text] in [font], [sizePx] pixels to the em, centred line
 * by line. Drawn white, so a picture's colour control gives it any colour exactly.
 */
data class TextPicture(val text: String, val font: TextFont = TextFont.Phone(PhoneFont.SANS), val sizePx: Int = DEFAULT_SIZE_PX) {
    /** Why this cannot be drawn as it is, or null: nothing to draw, too long or too many lines. */
    val problem: TextProblem?
        get() = when {
            text.isBlank() -> TextProblem.EMPTY
            text.length > MAX_LENGTH -> TextProblem.TOO_LONG
            lines.size > MAX_LINES -> TextProblem.TOO_MANY_LINES
            else -> null
        }

    /** Its lines, without trailing blank ones. */
    val lines: List<String> get() = text.split('\n').dropLastWhile { it.isBlank() }

    companion object {
        const val MIN_SIZE_PX = 8
        const val MAX_SIZE_PX = 200
        const val DEFAULT_SIZE_PX = 32
        const val MAX_LENGTH = 80
        const val MAX_LINES = 4

        /** [sizePx] kept within [MIN_SIZE_PX]..[MAX_SIZE_PX]. */
        fun size(sizePx: Int) = sizePx.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
    }
}

/** [text] drawn: the [picture] to place, or the [problem] that stopped it, never both. */
data class PreparedText(val text: TextPicture, val picture: PreparedPicture?, val problem: TextProblem? = null) {
    init {
        require((picture == null) != (problem == null))
    }
}

enum class TextProblem {
    EMPTY,
    TOO_LONG,
    TOO_MANY_LINES,

    /** Drawn, it would be wider or taller than the face. */
    TOO_BIG,
}

/**
 * A fixed word a Value draws in the watch's own font — a label such as "steps" — which can be
 * drawn as a picture in another font instead: its [text] in the preview's language, at the
 * binding's [sizePx], set in its box by [alignment].
 */
data class WidgetLabel(val text: String, val sizePx: Int, val alignment: LabelAlignment = LabelAlignment.LEFT) {
    /**
     * Where the centre of a [width] × [height] picture of this word goes for it to sit where the
     * watch draws the word in a box at [left], [top], [boxWidth] wide: against the same side,
     * and on the line the watch writes from the box's top, about 0.6 em down.
     */
    fun centreFor(width: Int, height: Int, left: Int, top: Int, boxWidth: Int, boxHeight: Int): Pair<Float, Float> {
        val x = when (alignment) {
            LabelAlignment.LEFT -> left + width / 2f
            LabelAlignment.CENTRE -> left + boxWidth / 2f
            LabelAlignment.RIGHT -> left + boxWidth - width / 2f
        }
        val y = top + (sizePx * 0.6f).coerceAtMost(boxHeight.toFloat()).coerceAtLeast(height / 2f)
        return x to y
    }
}

/** How a label sits in its box, as the watch aligns its text. */
enum class LabelAlignment { LEFT, CENTRE, RIGHT }
