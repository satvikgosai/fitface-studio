package dev.fitface.studio.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogXmlParserTest {
    @Test
    fun catalogKeepsOnlyWatchSizedScreenshotsAsStyles() {
        val page = CatalogXmlParser.parseCatalogPage(
            """
            <result>
              <resultCode>0</resultCode>
              <resultMsg>Success</resultMsg>
              <isEndOfList>true</isEndOfList>
              <appInfo>
                <productID>000007255362</productID>
                <productName><![CDATA[Black and white]]></productName>
                <appId>com.samsung.fit3watchface.sm_r390_0112</appId>
                <screenShotImgURL>https://img.samsungapps.com/face.png</screenShotImgURL>
                <screenShotCount>4</screenShotCount>
                <screenShotIndex>1|2|3|4</screenShotIndex>
                <screenShotResolution>256x402|256x402|512x512|256x402</screenShotResolution>
                <versionName>4.0.1</versionName>
                <versionCode>40001</versionCode>
                <realContentSize>2773817</realContentSize>
                <description>A refined analog face.</description>
              </appInfo>
              <appInfo>
                <productID>ignored</productID>
                <productName>Not a Fit3 face</productName>
                <appId>some.other.app</appId>
              </appInfo>
            </result>
            """.trimIndent(),
        )

        assertEquals(1, page.faces.size)
        assertTrue(page.endOfList)
        val face = page.faces.single()
        assertEquals("00112", face.faceId)
        assertEquals("Black and white", face.name)
        // The 512x512 shot is promotional art, not a style. Skipping it rather than
        // truncating there keeps the remaining style aligned with style2.bin.
        assertEquals(listOf(0, 1, 2), face.styles.map { it.id })
        assertEquals(
            "https://img.samsungapps.com/face_256_402_4.png",
            face.styles.last().previewUrl,
        )
    }

    @Test
    fun emptyFollowUpPageEndsPaginationInsteadOfFailing() {
        val page = CatalogXmlParser.parseCatalogPage(
            """
            <result>
              <resultCode>1007</resultCode>
              <resultMsg>No Items</resultMsg>
            </result>
            """.trimIndent(),
            allowEmpty = true,
        )

        assertTrue(page.faces.isEmpty())
        assertTrue(page.endOfList)
        assertEquals(0, page.rawCount)
    }

    @Test
    fun firstPageFailureIsAnError() {
        val failure = runCatching {
            CatalogXmlParser.parseCatalogPage(
                "<result><resultCode>1007</resultCode></result>",
                allowEmpty = false,
            )
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun updateAndDownloadResponsesReadNestedResultAndCdataUri() {
        val update = CatalogXmlParser.parseUpdateCheck(
            """
            <result>
              <resultCode>0</resultCode>
              <appInfo><resultCode>1</resultCode></appInfo>
            </result>
            """.trimIndent(),
        )
        val download = CatalogXmlParser.parseDownload(
            """
            <result>
              <appInfo>
                <resultCode>1</resultCode>
                <downloadURI><![CDATA[https://aka-dn.gw.samsungapps.com/face.apk?a=1&b=2]]></downloadURI>
                <contentSize>2773817</contentSize>
              </appInfo>
            </result>
            """.trimIndent(),
        )

        assertEquals(1, update)
        assertEquals(1, download.resultCode)
        assertEquals(2_773_817, download.contentSize)
        assertTrue(download.downloadUri.endsWith("face.apk?a=1&b=2"))
        assertFalse(download.downloadUri.contains("amp;"))
    }
}
