package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.PhotoClock
import dev.fitface.studio.core.model.PhotoClockColour
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The Photos face's clock settings, as the stock plugin's own copy of the face's `info.json`
 * lists them. The store package for `00254` lists only the background; the plugin's copy adds
 * `clock_type` (a `Selection` of IDs, each with a picture) and `clock_color` (a `Selection` of
 * IDs, each with an `#AARRGGBB` colour), each with the ID it starts on as `selected`. Those IDs
 * are what set-edit-info carries (`docs/direct-install.md`).
 *
 * Nothing here knows where the plugin keeps the file or its pictures: the reader looks for
 * them, and this only reads what it is given, so a plugin that moves them still works.
 */
object PhotoClockSettings {
    /** The settings read from one `info.json`. [default] is the plugin's own starting choice. */
    class Parsed(
        val faceId: Int,
        val types: List<TypeOption>,
        val colours: List<PhotoClockColour>,
        val default: PhotoClock,
    )

    /** A clock type and the picture the plugin names for it, relative to its pictures' folder. */
    data class TypeOption(val id: Int, val picture: String?)

    /** Larger than any plugin's `info.json`; a reader stops before this. */
    const val MAX_JSON_BYTES = 256 * 1024

    private const val MAX_OPTIONS = 255
    private val faceUnique = Regex("^SM-R390_(\\d{5})$")
    private val pictureName = Regex("^[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.png$", RegexOption.IGNORE_CASE)
    private val colourHex = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    /** The face ID [json] declares, or null if it declares none; read before [parse]. */
    fun faceId(json: ByteArray): Int? = runCatching { info(json)?.let(::faceIdOf) }.getOrNull()

    /** The clock settings in [json], or null unless it lists both a clock type and a colour. */
    fun parse(json: ByteArray): Parsed? = runCatching {
        if (json.size > MAX_JSON_BYTES) return null
        val info = info(json) ?: return null
        val faceId = faceIdOf(info) ?: return null
        val settings = info["Settings"]?.objects().orEmpty()
        val type = settings.firstOrNull { it.text("name") == "clock_type" } ?: return null
        val colour = settings.firstOrNull { it.text("name") == "clock_color" } ?: return null
        val types = type.selection { id, option ->
            TypeOption(id, option.text("image")?.takeIf(pictureName::matches)?.takeUnless { ".." in it })
        }
        val colours = colour.selection { id, option ->
            option.text("color")?.let(::argbOf)?.let { PhotoClockColour(id, it) }
        }
        if (types.isEmpty() || colours.isEmpty()) return null
        val default = PhotoClock(
            type.text("selected")?.toIntOrNull()?.takeIf { id -> types.any { it.id == id } } ?: types.first().id,
            colour.text("selected")?.toIntOrNull()?.takeIf { id -> colours.any { it.id == id } } ?: colours.first().id,
        )
        Parsed(faceId, types, colours, default)
    }.getOrNull()

    /**
     * The picture for a clock type in the phone's language, among [available] (the names in the
     * folder [picture] is in). The plugin names one picture, `Photos_clocktype_1.png`, and keeps
     * a copy per language beside it (`…_EN.png`, `…_KR.png`, `…_PR.png`, `…_CN_Simplified.png`,
     * `…_CN_Traditional.png`), choosing as `BandFaceUtils.getCountryImageFilepath` does: Korean,
     * Portuguese, Chinese by region, English for the rest. Here the same, then English, then the
     * name as given, then any language's copy, so a plugin that adds or drops a language still
     * shows one. Returns the path relative to the pictures' folder, or null if none is there.
     */
    fun pictureFor(picture: String, language: String, country: String, available: Collection<String>): String? {
        val folder = picture.substringBeforeLast('/', missingDelimiterValue = "")
        val file = picture.substringAfterLast('/')
        val base = file.removeSuffix(".png").removeSuffix(".PNG")
        val wanted = listOf("${base}_${languageSuffix(language, country)}.png", "${base}_EN.png", file).distinct()
        val name = wanted.firstOrNull { it in available }
            ?: available.filter { it.startsWith("${base}_") && it.endsWith(".png", ignoreCase = true) }.minOrNull()
            ?: return null
        return if (folder.isEmpty()) name else "$folder/$name"
    }

    /** Whether a folder named [name] holds face [faceId]'s files: `SM-R390_00254`, then anything. */
    fun isFaceFolder(name: String, faceId: Int): Boolean {
        val stem = "SM-R390_%05d".format(faceId)
        return name == stem || name.startsWith("${stem}_")
    }

    private fun languageSuffix(language: String, country: String): String = when (language.lowercase()) {
        "ko" -> "KR"
        "pt" -> "PR"
        "zh" -> if (country.uppercase() in setOf("HK", "TW")) "CN_Traditional" else "CN_Simplified"
        else -> "EN"
    }

    private fun info(json: ByteArray): JsonObject? =
        Json.parseToJsonElement(json.decodeToString()).jsonObject["info"]?.jsonObject

    private fun faceIdOf(info: JsonObject): Int? = info.text("wf_unique")
        ?.let(faceUnique::matchEntire)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..255 }

    /** The setting's `Selection` options with an ID of 1 to 255, each once, in its order. */
    private fun <T : Any> JsonObject.selection(read: (Int, JsonObject) -> T?): List<T> =
        this["Selection"]?.objects().orEmpty().take(MAX_OPTIONS)
            .mapNotNull { option ->
                val id = option.text("id")?.toIntOrNull()?.takeIf { it in 1..255 } ?: return@mapNotNull null
                read(id, option)?.let { id to it }
            }
            .distinctBy { it.first }
            .map { it.second }

    /** `#RRGGBB` or `#AARRGGBB` as `0xAARRGGBB`, opaque when no alpha is given. */
    private fun argbOf(hex: String): Int? {
        if (!colourHex.matches(hex)) return null
        val value = hex.substring(1).toLong(16).toInt()
        return if (hex.length == 7) value or (0xFF shl 24) else value
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    /** A metadata field the vendor writes as one object or as a list of them. */
    private fun JsonElement.objects(): List<JsonObject> = when (this) {
        is JsonObject -> listOf(this)
        is JsonArray -> mapNotNull { it as? JsonObject }
        else -> emptyList()
    }
}
