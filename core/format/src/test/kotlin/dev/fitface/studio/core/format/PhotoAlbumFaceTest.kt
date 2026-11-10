package dev.fitface.studio.core.format

import dev.fitface.studio.core.model.PhotoAlbumFace
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Photos face is recognised by what its package declares. The store package for `00254`
 * carries only `assets/bandface_info_myPhoto.json`, whose `Settings` is a single
 * `layer_background` object with `gallery_menu: yes` and one preloaded background; the
 * plugin's own copy writes `Settings` as a list. The metadata below has that shape, cut down.
 */
class PhotoAlbumFaceTest {
    @Test
    fun theStorePackagesSingleBackgroundSettingIsAnAlbum() {
        assertEquals(
            PhotoAlbumFace(254, listOf("photo_clock_default_bg.png")),
            Fit3Apk.photoAlbumFace(apk("assets/bandface_info_myPhoto.json" to metadata(listed = false))),
        )
    }

    @Test
    fun aSettingsListWithClockSettingsBesideItIsReadToo() {
        assertEquals(
            PhotoAlbumFace(254, listOf("photo_clock_default_bg.png")),
            Fit3Apk.photoAlbumFace(apk("assets/bandface_info.json" to metadata(listed = true))),
        )
    }

    @Test
    fun anythingElseIsNotAnAlbum() {
        // A face with a container is edited, not filled with photos.
        assertNull(
            Fit3Apk.photoAlbumFace(
                apk(
                    "assets/bandface_info.json" to metadata(listed = false),
                    "assets/SM-R390_00254_256x402.bin" to "x",
                ),
            ),
        )
        assertNull(Fit3Apk.photoAlbumFace(apk("assets/bandface_info.json" to metadata(listed = false, gallery = "no"))))
        assertNull(Fit3Apk.photoAlbumFace(apk("assets/bandface_info.json" to metadata(listed = false, unique = "SM-R390_254"))))
        assertNull(Fit3Apk.photoAlbumFace(apk("assets/bandface_info.json" to metadata(listed = false, image = "../bg.png"))))
        assertNull(Fit3Apk.photoAlbumFace(apk("assets/bandface_info.json" to "{ not json")))
        assertNull(Fit3Apk.photoAlbumFace(apk("assets/other.json" to metadata(listed = false))))
    }

    private fun metadata(
        listed: Boolean,
        gallery: String = "yes",
        unique: String = "SM-R390_00254",
        image: String = "photo_clock_default_bg.png",
    ): String {
        val background = """
            {"name": "background", "setting_type": "layer_background",
             "BackgroundImageSelections": {"id": "background", "gallery_menu": "$gallery",
               "BackgroundImageSelection": [{"ImageName": "$image", "type": "preloaded", "id": "bg", "isDefault": "true"}]}}
        """.trimIndent()
        val clock = """{"name": "clock_type", "setting_type": "layer_bigicon", "selected": "1"}"""
        val settings = if (listed) "[$background, $clock]" else background
        return """{"info": {"wf_unique": "$unique", "Settings": $settings}}"""
    }

    private fun apk(vararg members: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                members.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
