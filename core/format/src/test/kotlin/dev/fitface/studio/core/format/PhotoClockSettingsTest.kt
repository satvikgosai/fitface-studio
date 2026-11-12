package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.PhotoClock
import dev.fitface.studio.core.model.PhotoClockColour
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Photos face's clock settings, read from the stock plugin's own copy of the face's
 * `info.json` (in the corpus as `SM_R390/SM-R390_00254_256x402/info.json`, plugin 2.2.14), and
 * the picture chosen for each clock type by language.
 */
class PhotoClockSettingsTest {
    private val root = Path.of(requireNotNull(System.getProperty("fit3.corpusRoot")))

    @Test
    fun thePluginsCopyListsTwoTypesAndFiftySevenColours() {
        val path = root.resolve("SM_R390/SM-R390_00254_256x402/info.json")
        assumeTrue("corpus is not available at $root", Files.isRegularFile(path))
        val parsed = requireNotNull(PhotoClockSettings.parse(Files.readAllBytes(path)))
        assertEquals(254, parsed.faceId)
        assertEquals(
            listOf(
                PhotoClockSettings.TypeOption(1, "clocktype/Photos_clocktype_1.png"),
                PhotoClockSettings.TypeOption(2, "clocktype/Photos_clocktype_2.png"),
            ),
            parsed.types,
        )
        assertEquals((1..57).toList(), parsed.colours.map { it.id })
        assertEquals(PhotoClockColour(1, 0xFFFFFFFF.toInt()), parsed.colours.first())
        assertEquals(PhotoClockColour(57, 0xFF4D3541.toInt()), parsed.colours.last())
        assertEquals(PhotoClock(1, 1), parsed.default)
    }

    @Test
    fun theStorePackagesBackgroundOnlyCopyHasNoClock() {
        assertNull(PhotoClockSettings.parse(info(clock = false)))
        assertEquals(254, PhotoClockSettings.faceId(info(clock = false)))
    }

    @Test
    fun aSettingsListIsReadWhateverItsOrderAndTheDefaultsComeFromSelected() {
        val parsed = requireNotNull(PhotoClockSettings.parse(info(typeSelected = "2", colourSelected = "3")))
        assertEquals(listOf(1, 2), parsed.types.map { it.id })
        assertEquals(listOf(1, 2, 3), parsed.colours.map { it.id })
        assertEquals(PhotoClock(2, 3), parsed.default)
        // `#RRGGBB` is opaque.
        assertEquals(0xFF112233.toInt(), parsed.colours[2].argb)
    }

    @Test
    fun badOptionsAreDroppedAndAnUnknownDefaultFallsBackToTheFirst() {
        val parsed = requireNotNull(
            PhotoClockSettings.parse(
                info(
                    extraTypes = """, {"id": "0", "image": "a.png"}, {"id": "x"}, {"id": "1", "image": "dup.png"},
                        {"id": "9", "image": "../../secret.png"}""",
                    extraColours = """, {"id": "4", "color": "red"}, {"id": "300", "color": "#FF000000"}""",
                    typeSelected = "7",
                    colourSelected = "nope",
                ),
            ),
        )
        assertEquals(listOf(1, 2, 9), parsed.types.map { it.id })
        assertEquals("clocktype/Photos_clocktype_1.png", parsed.types.first().picture)
        assertNull("a path out of the folder is no picture", parsed.types.last().picture)
        assertEquals(listOf(1, 2, 3), parsed.colours.map { it.id })
        assertEquals(PhotoClock(1, 1), parsed.default)
    }

    @Test
    fun anythingElseHasNoClock() {
        assertNull(PhotoClockSettings.parse("{ not json".toByteArray()))
        assertNull(PhotoClockSettings.parse(info(unique = "SM-R390_254")))
        assertNull(PhotoClockSettings.parse(info(colours = "")))
        assertNull(PhotoClockSettings.parse(ByteArray(PhotoClockSettings.MAX_JSON_BYTES + 1) { ' '.code.toByte() }))
    }

    @Test
    fun theClockPictureFollowsThePhonesLanguageAsThePluginDoes() {
        val folder = listOf("_EN", "_KR", "_PR", "_CN_Simplified", "_CN_Traditional")
            .map { "Photos_clocktype_1$it.png" }
        fun pick(language: String, country: String = "") =
            PhotoClockSettings.pictureFor("clocktype/Photos_clocktype_1.png", language, country, folder)
        assertEquals("clocktype/Photos_clocktype_1_EN.png", pick("en", "US"))
        assertEquals("clocktype/Photos_clocktype_1_EN.png", pick("de", "DE"))
        assertEquals("clocktype/Photos_clocktype_1_KR.png", pick("ko", "KR"))
        assertEquals("clocktype/Photos_clocktype_1_PR.png", pick("pt", "BR"))
        assertEquals("clocktype/Photos_clocktype_1_CN_Simplified.png", pick("zh", "CN"))
        assertEquals("clocktype/Photos_clocktype_1_CN_Traditional.png", pick("zh", "TW"))
        assertEquals("clocktype/Photos_clocktype_1_CN_Traditional.png", pick("zh", "HK"))
    }

    @Test
    fun aPluginThatChangesItsLanguagesStillShowsAPicture() {
        // Korean asked for, none in Korean: English.
        assertEquals("Photos_clocktype_1_EN.png",
            PhotoClockSettings.pictureFor("Photos_clocktype_1.png", "ko", "KR", listOf("Photos_clocktype_1_EN.png")))
        // No language copies: the name as given.
        assertEquals("c/Photos_clocktype_1.png",
            PhotoClockSettings.pictureFor("c/Photos_clocktype_1.png", "fr", "FR", listOf("Photos_clocktype_1.png")))
        // Neither: any language's copy, the same one every time.
        assertEquals("Photos_clocktype_1_DE.png",
            PhotoClockSettings.pictureFor("Photos_clocktype_1.png", "fr", "FR",
                listOf("Photos_clocktype_1_FR_x.txt", "Photos_clocktype_1_JA.png", "Photos_clocktype_1_DE.png")))
        // Another type's pictures are not this one's.
        assertNull(PhotoClockSettings.pictureFor("Photos_clocktype_1.png", "en", "US", listOf("Photos_clocktype_12_EN.png")))
    }

    @Test
    fun theFacesFolderIsFoundByItsIdWhateverFollows() {
        assertTrue(PhotoClockSettings.isFaceFolder("SM-R390_00254_256x402", 254))
        assertTrue(PhotoClockSettings.isFaceFolder("SM-R390_00254", 254))
        assertTrue(PhotoClockSettings.isFaceFolder("SM-R390_00254_402x256", 254))
        assertFalse(PhotoClockSettings.isFaceFolder("SM-R390_002540", 254))
        assertFalse(PhotoClockSettings.isFaceFolder("SM-R390_00106_256x402", 254))
    }

    private fun info(
        clock: Boolean = true,
        unique: String = "SM-R390_00254",
        typeSelected: String = "1",
        colourSelected: String = "1",
        extraTypes: String = "",
        colours: String = """{"id": "1", "color": "#FFFFFFFF"}, {"id": "2", "color": "#FFF4ABD4"},
            {"id": "3", "color": "#112233"}""",
        extraColours: String = "",
    ): ByteArray {
        val background = """{"name": "background", "setting_type": "layer_background",
            "BackgroundImageSelections": {"gallery_menu": "yes", "BackgroundImageSelection":
            [{"ImageName": "photo_clock_default_bg.png", "type": "preloaded"}]}}"""
        val settings = if (!clock) background else """[
            {"name": "clock_color", "setting_type": "layer_coloricon", "selected": "$colourSelected",
             "Selection": [$colours$extraColours]},
            $background,
            {"name": "clock_type", "setting_type": "layer_bigicon", "selected": "$typeSelected",
             "Selection": [{"type": "icon_image", "image": "clocktype/Photos_clocktype_1.png", "id": "1"},
                           {"type": "icon_image", "image": "clocktype/Photos_clocktype_2.png", "id": "2"}$extraTypes]}
        ]"""
        return """{"info": {"wf_unique": "$unique", "Settings": $settings}}""".toByteArray()
    }
}
