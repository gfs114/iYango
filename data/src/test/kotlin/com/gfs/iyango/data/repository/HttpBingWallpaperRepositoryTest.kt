package com.gfs.iyango.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HttpBingWallpaperRepositoryTest {

    @Test fun `parseBingWallpaperJson builds UHD image url and fallback from relative fields`() {
        val raw = """
            {
              "images": [
                {
                  "startdate": "20261121",
                  "url": "/th?id=OHR.Autumn_ZH-CN_1920x1080.jpg",
                  "urlbase": "/th?id=OHR.Autumn_ZH-CN",
                  "copyright": "红叶谷风光 (© Photographer/Getty Images)",
                  "title": "深秋红叶谷"
                }
              ]
            }
        """.trimIndent()

        val parsed = parseBingWallpaperJson(raw, baseHost = "https://cn.bing.com")
        assertNotNull(parsed)
        assertEquals("20261121", parsed!!.date)
        assertEquals("深秋红叶谷", parsed.title)
        assertEquals("红叶谷风光 (© Photographer/Getty Images)", parsed.copyright)
        assertEquals("https://cn.bing.com/th?id=OHR.Autumn_ZH-CN_UHD.jpg", parsed.imageUrl)
        assertEquals("https://cn.bing.com/th?id=OHR.Autumn_ZH-CN_1920x1080.jpg", parsed.fallbackUrl)
    }

    @Test fun `parseBingWallpaperJson falls back to url when urlbase is absent and rejects invalid payloads`() {
        val urlOnly = """{"images":[{"startdate":"20261121","url":"https://img.example.com/bing.jpg"}]}"""
        val parsed = parseBingWallpaperJson(urlOnly)
        assertNotNull(parsed)
        assertEquals("https://img.example.com/bing.jpg", parsed!!.imageUrl)
        assertEquals("", parsed.fallbackUrl)

        assertNull(parseBingWallpaperJson(""))
        assertNull(parseBingWallpaperJson("{not json"))
        assertNull(parseBingWallpaperJson("""{"images":[]}"""))
        assertNull(parseBingWallpaperJson("""{"images":[{"startdate":"20261121"}]}"""))
    }
}
