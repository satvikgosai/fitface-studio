package dev.fitface.studio.core.format

/** Bounded access to named fields; no searching opaque words for plausible references. */
class WidgetFields(private val entry: ContainerEntry, private val record: WidgetRecord) {
    fun byte(offset: Int): Int {
        require(offset in 0 until record.recordSize)
        return entry.data[record.fieldOffset(offset)].toInt() and 255
    }
    fun short(offset: Int): Int = byte(offset) or (byte(offset + 1) shl 8)
    fun signed(offset: Int): Int = short(offset).toShort().toInt()
    fun word(offset: Int): Int = short(offset) or (short(offset + 2) shl 16)
}

/** Deterministic preview only: 28 Dec 2024, 10:08, and illustrative sensor readings. */
object WidgetPreviewSample {
    fun handAngle(startDegrees: Int, endDegrees: Int, fraction: Double): Double =
        startDegrees + fraction * (endDegrees - startDegrees)
    fun value(source: Int): Int? = when (source) {
        0 -> 0
        1 -> 10; 2 -> 1; 3 -> 0; 5 -> 0
        9 -> 8; 10 -> 0; 11 -> 8; 13, 14, 15 -> 0
        17 -> 5; 18 -> 28; 19 -> 2; 20 -> 8
        21 -> 12; 22 -> 1; 23 -> 2
        24 -> 2024; 25 -> 2; 26 -> 0; 27 -> 2; 28 -> 4
        29 -> 7500; 37 -> 75; 41 -> 72; 48 -> 350; 55 -> 5
        62 -> 25; 69 -> 0; 70 -> 50; 71 -> 30; 72 -> 6
        102 -> 98; 104 -> 8; 115 -> 4
        106 -> 1; 107 -> 3; 109 -> 0; 110 -> 8
        122 -> 12; 123 -> 5; 124 -> 28; 125 -> 1
        117 -> 0; 118 -> 2; 119 -> 4
        // Secondary-zone and service strings have no configuration in a face package.
        else -> null
    }

    fun fraction(source: Int): Double? = when (source) {
        1 -> (10.0 + 8.0 / 60) / 12
        9 -> 8.0 / 60; 13 -> 0.0
        17 -> 6.0 / 7; 21 -> 11.0 / 12
        29 -> 0.75; 37 -> 0.75; 41 -> 0.36; 48 -> 0.7
        70, 71, 115 -> 0.5; 104 -> 0.8
        else -> null
    }

    fun spriteFrame(source: Int, count: Int): Int {
        if (count <= 0) return 0
        val frame = when (source) {
            21 -> 11
            29, 37, 41, 48, 70, 71, 115 ->
                ((fraction(source) ?: 0.0) * (count - 1)).toInt()
            else -> value(source) ?: 0
        }
        return Math.floorMod(frame, count)
    }
}

data class WidgetText(
    val text: String,
    val font: FontBinding,
    val color: Int,
    val letterSpacing: Int = 0,
    val rotationDegrees: Double = 0.0,
    /** 0 left, 1 centre, 2 right; distinct from the widget's placement alignment. */
    val alignment: Int = 0,
)

/** Package dictionaries and numbered bindings; glyphs themselves live in watch ROM. */
class WidgetTextResources(entries: List<ContainerEntry>, private val locale: String = "en") {
    private val byName = entries.associateBy { it.basename }
    private val dictionary = (byName["font_$locale.bin"] ?: byName["font_en.bin"])
        ?.let { runCatching { LocaleDictionary.parse(it).items }.getOrNull() }
    private val fonts = mutableMapOf<Int, FontBinding?>()

    fun text(entry: ContainerEntry, record: WidgetRecord): WidgetText? {
        val binding = record.fontBindingIndex ?: return null
        val bindingFont = fonts.getOrPut(binding) {
            byName["font_$binding.bin"]?.let { runCatching { FontBinding.parse(it) }.getOrNull() }
        } ?: return null
        val language = when (locale) {
            "cn0" -> 0; "cn2" -> 3; "fr" -> 22; "ko" -> 28
            "pt_rPT" -> 46; "ja" -> 47; "it" -> 65; else -> 1
        }
        val font = if (bindingFont.family != 0) bindingFont else bindingFont.copy(
            familyByLanguage = bindingFont.familyByLanguage.copyOf().apply {
                this[1] = this[language]
            },
        )
        if (font.pixelSize !in 1..512) return null
        val fields = WidgetFields(entry, record)
        fun item(index: Int): String? = if (index == 0xFFFF) "" else dictionary?.getOrNull(index)
        fun dynamic(source: Int, base: Int, numeric: Boolean, selector: Int, max: Int): String? {
            val value = WidgetPreviewSample.value(source) ?: return null
            if (!numeric) {
                if (base == 0xFFFF) return ""
                // Calendar word tables are zero-based; numeric months remain 1..12.
                return item(base + if (source == 21) value - 1 else value)
            }
            val width = if (selector in 2..max) selector else 1
            return if (value < 0) "-" + (-value).toString().padStart((width - 1).coerceAtLeast(0), '0')
                else value.toString().padStart(width, '0')
        }
        val text = when (record.widgetType) {
            WIDGET_PAIR -> {
                val base = fields.short(0x2C)
                dynamic(record.sourceId, base, base == 0xFFFF, fields.byte(0x2A), 8)
                    ?: return null
            }
            WIDGET_COMP -> {
                val parts = (0..3).map { index ->
                    val b = 0x24 + index * 12
                    val source = fields.short(b)
                    val a = item(fields.short(b + 2)) ?: return null
                    val c = item(fields.short(b + 4)) ?: return null
                    val base = fields.short(b + 6)
                    // 0x2C10795E: both sentinels gate the dynamic fragment, even numeric
                    // mode. Fixed prefix/suffix are independent of the source sentinel.
                    val d = if (source == 0xFFFF || base == 0xFFFF) "" else
                        dynamic(source, base, fields.byte(b + 8) != 0,
                            fields.byte(b + 9), 6) ?: return null
                    // 0x2C107CAE..CB6 passes slots 0, 2, 1 to snprintf: prefix, value, suffix.
                    a + d + c
                }
                val orderIndex = fields.short(0x62)
                val order = if (orderIndex == 0 || orderIndex == 0xFFFF) "1234"
                    else item(orderIndex) ?: return null
                if (order.length != 4 || order.any { it !in '1'..'4' } ||
                    order.toSet().size != order.length) return null
                order.map { parts[it - '1'] }.joinToString("")
            }
            else -> return null
        }
        return WidgetText(text, font, (record.textColorArgb ?: 0xFFFFFF).toInt() or
            0xFF000000.toInt(),
            if (record.widgetType == WIDGET_COMP) fields.byte(0x60).toByte().toInt() else 0,
            if (record.widgetType == WIDGET_COMP) fields.short(0x5C) / 10.0 else 0.0,
            when (fields.short(0x20)) {
                1, 4, 7, 10, 13, 16, 17, 18, 0xFFFF -> 0
                3, 6, 8, 12, 15, 19, 20, 21 -> 2
                else -> 1
            })
    }
}
