package dev.fitface.studio.core.model

/**
 * A part of the clock a face draws as pictures of characters, one picture per digit or per
 * day, which a font can redraw and which still shows the time (Stage 9b).
 *
 * Only parts some catalogue face draws this way: the date, month and AM/PM are written by the
 * watch itself on every one of them.
 */
enum class ClockPart {
    /** Hours and minutes. */
    TIME,
    SECONDS,
    SECOND_ZONE,
    /** Seven pictures, Monday first. */
    WEEKDAY,
}

/** The font a clock part was redrawn in: one of the phone's, or a picked file's name. */
data class ClockFontName(val phone: PhoneFont? = null, val file: String? = null)

/**
 * What the Clock font page asks for: [parts] of the selected style — or of every style — in
 * [font], each part in its own colour when [tint] is null, otherwise all in [tint].
 */
data class ClockFontRequest(
    val font: TextFont,
    val parts: Set<ClockPart>,
    val tint: Int? = null,
    val applyToAllStyles: Boolean = false,
)

/** The face with the clock redrawn, ready to apply with its [ticket]. */
data class ClockFontPreview(
    val ticket: String,
    val preview: PreviewFrame,
    val changedVariants: List<String>,
    /** The sets of pictures redrawn, across [changedVariants]. */
    val sets: Int,
)
