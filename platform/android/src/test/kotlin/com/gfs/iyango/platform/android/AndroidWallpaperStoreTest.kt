package com.gfs.iyango.platform.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.gfs.iyango.core.domain.WallpaperStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidWallpaperStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = AndroidWallpaperStore(context)

    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }

    @Test fun smallPngUriCanBePreparedForCropping() = runBlocking {
        val input = File(context.cacheDir, "small.png").apply { writeBytes(png()) }
        val prepared = store.prepare(Uri.fromFile(input).toString())
        assertNotNull(prepared)
        assertEquals(64, prepared!!.width)
        assertEquals(128, prepared.height)
        assertTrue(File(prepared.tempPath).isFile)
    }

    @Test fun replacingLightOrDarkKeepsTheOtherVariantAndScene() = runBlocking {
        val saved = mutableListOf<String>()
        for (scene in listOf(WallpaperStore.SLOT_GLOBAL, WallpaperStore.SLOT_TIMETABLE)) {
            val legacy = File(context.filesDir, "wallpapers/wallpaper_${scene}_123.jpg")
            legacy.parentFile!!.mkdirs()
            legacy.writeBytes(png())
            suspend fun save(slot: String) = store.commit(store.prepareFromBytes(png())!!.tempPath, slot)!!
            val light = save(scene)
            val dark = save("${scene}_dark")
            assertNotNull(store.resolve(light))
            val nextLight = save(scene)
            assertNotNull(store.resolve(dark))
            val nextDark = save("${scene}_dark")
            assertNotNull(store.resolve(nextLight))
            assertNotNull(store.resolve(nextDark))
            assertTrue("Legacy names may still be referenced by either theme", legacy.isFile)
            saved += listOf(nextLight, nextDark)
        }
        saved.forEach { assertNotNull(store.resolve(it)) }
    }
}
