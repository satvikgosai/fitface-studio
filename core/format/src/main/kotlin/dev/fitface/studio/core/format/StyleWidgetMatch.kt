package dev.fitface.studio.core.format

/**
 * Pairs the same widget across the variant entries of one container.
 *
 * Styles are independent colourways, not renderings of one shared layout, so a
 * widget present in the style being edited need not exist in its siblings at all.
 * Face `00001` is the plain case: `style0.bin` carries Value widgets for data
 * sources 17 and 18, and `style1.bin` has neither — it draws a Static and data
 * source 48 instead. Across the corpus that is 183 of 2,833 selectable widgets in
 * 20 of the 99 faces, and on face `00001` it is *every* selectable widget.
 *
 * So a cross-style edit resolves rather than asserts. The entry the user is
 * looking at must match — that edit is the one they asked for and it has to
 * happen or fail loudly — and every other variant is best effort: it is edited
 * where the same widget is unambiguously there, and left alone where it is not.
 */
internal object StyleWidgetMatch {
    private val STYLE_ENTRY = Regex("""style\d+\.bin""")
    private const val AOD_ENTRY = "aod.bin"

    fun isVariantEntry(basename: String): Boolean =
        basename == AOD_ENTRY || basename.matches(STYLE_ENTRY)

    fun requireVariantEntry(entry: ContainerEntry): ContainerEntry {
        if (!isVariantEntry(entry.basename)) {
            throw Fit3FormatException("${entry.basename} is not an editable variant entry")
        }
        return entry
    }

    /**
     * The record in [entry] that is the same widget as [source] in another variant,
     * or null when the variant does not carry it or carries it ambiguously.
     *
     * Global index is tried first because it is the identity the selected style
     * used. `aod.bin` numbers its own, much shorter table independently, so index
     * there would collide with an unrelated record; it is matched on the data
     * source and stored position only.
     */
    fun match(entry: ContainerEntry, source: WidgetRecord): WidgetRecord? {
        val records = FaceRecordParser.scanWidgets(entry)
        if (entry.basename != AOD_ENTRY) {
            records.singleOrNull {
                it.globalIndex == source.globalIndex &&
                    it.widgetType == source.widgetType &&
                    it.sequenceId == source.sequenceId
            }?.let { return it }
        }
        return records.singleOrNull {
            it.widgetType == source.widgetType &&
                it.sequenceId == source.sequenceId &&
                it.x == source.x &&
                it.y == source.y
        }
    }

    /**
     * [entryBasenames] resolved to the records a cross-style edit should rewrite.
     *
     * The first name is the selected variant and is strict; [selected] describes it
     * so callers can report exactly which part of the selection went stale.
     */
    fun resolve(
        source: Fit3Container,
        entryBasenames: List<String>,
        selected: (ContainerEntry, List<WidgetRecord>) -> WidgetRecord?,
    ): List<Pair<ContainerEntry, WidgetRecord>> {
        if (entryBasenames.isEmpty()) {
            throw Fit3FormatException("a cross-style edit requires at least one variant")
        }
        if (entryBasenames.distinct().size != entryBasenames.size) {
            throw Fit3FormatException("a cross-style edit contains duplicate variant entries")
        }
        val selectedEntry = requireVariantEntry(source.entryByBasename(entryBasenames.first()))
        val selectedRecord = selected(selectedEntry, FaceRecordParser.scanWidgets(selectedEntry))
            ?: throw Fit3FormatException(
                "${selectedEntry.basename}: selected widget schema changed or does not match",
            )
        return buildList {
            add(selectedEntry to selectedRecord)
            entryBasenames.drop(1).forEach { basename ->
                val entry = requireVariantEntry(source.entryByBasename(basename))
                match(entry, selectedRecord)?.let { add(entry to it) }
            }
        }
    }
}
