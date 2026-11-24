package com.gfs.iyango.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CropGeometry] 纯 JVM 行为测试：Contain 适配、初始框、夹取、重摆与像素窗口换算。
 */
class CropGeometryTest {

    @Test
    fun `fitDisplay 按 Contain 居中适配`() {
        // 2000x1000 的图片放进 100x100 的预览区：宽向受限。
        val landscape = CropGeometry.fitDisplay(100f, 100f, 2000, 1000)!!
        assertEquals(0f, landscape.x, 1e-4f)
        assertEquals(25f, landscape.y, 1e-4f)
        assertEquals(100f, landscape.width, 1e-4f)
        assertEquals(50f, landscape.height, 1e-4f)

        // 1000x2000 的图片放进 100x100：高向受限。
        val portrait = CropGeometry.fitDisplay(100f, 100f, 1000, 2000)!!
        assertEquals(25f, portrait.x, 1e-4f)
        assertEquals(0f, portrait.y, 1e-4f)
        assertEquals(50f, portrait.width, 1e-4f)
        assertEquals(100f, portrait.height, 1e-4f)
    }

    @Test
    fun `fitDisplay 拒绝零尺寸与退化输入`() {
        assertNull(CropGeometry.fitDisplay(0f, 100f, 100, 100))
        assertNull(CropGeometry.fitDisplay(100f, 100f, 0, 100))
        assertNull(CropGeometry.fitDisplay(100f, -1f, 100, 100))
    }

    @Test
    fun `initialCrop 给出居中的最大锁定比例框`() {
        val display = CropRect(x = 0f, y = 0f, width = 100f, height = 100f)
        // 9:16 框放进正方形：宽受限，高 177.8 超出 → 实际高向受限，宽 = 100 * 9/16。
        val crop = CropGeometry.initialCrop(display, 9f / 16f)!!
        assertEquals(9f / 16f, crop.width / crop.height, 1e-4f)
        assertEquals(100f, crop.height, 1e-4f)
        assertEquals(56.25f, crop.width, 1e-3f)
        assertEquals(21.875f, crop.x, 1e-3f)
        assertEquals(0f, crop.y, 1e-4f)
    }

    @Test
    fun `initialCrop 在极窄图片上以图片为上限`() {
        // 图片显示区只有 40 宽：即使最小宽度 80 也必须缩到 40。
        val display = CropRect(x = 0f, y = 0f, width = 40f, height = 200f)
        val crop = CropGeometry.initialCrop(display, 9f / 16f)!!
        assertTrue(crop.width <= 40f)
        assertTrue(crop.x >= display.x && crop.x + crop.width <= display.x + display.width)
    }

    @Test
    fun `clampCrop 把拖出边界的框拉回图片内`() {
        val display = CropRect(x = 0f, y = 0f, width = 100f, height = 200f)
        val dragged = CropRect(x = -30f, y = 250f, width = 60f, height = 60f / (9f / 16f))
        val clamped = CropGeometry.clampCrop(dragged, display, 9f / 16f)!!
        assertEquals(0f, clamped.x, 1e-3f)
        assertEquals(200f - clamped.height, clamped.y, 1e-3f)
    }

    @Test
    fun `clampCrop 宽度收敛到合法区间`() {
        val display = CropRect(x = 0f, y = 0f, width = 100f, height = 200f)
        // 过窄 → 收敛到最小宽度（80 未超上限时生效）。
        val narrow = CropGeometry.clampCrop(
            CropRect(x = 0f, y = 0f, width = 10f, height = 10f), display, 1f,
        )!!
        assertEquals(MIN_CROP_WIDTH, narrow.width, 1e-3f)
        // 过宽 → 收敛到上限。
        val wide = CropGeometry.clampCrop(
            CropRect(x = 0f, y = 0f, width = 500f, height = 500f), display, 1f,
        )!!
        assertEquals(100f, wide.width, 1e-3f)
    }

    @Test
    fun `recenterCrop 保留中心相对位置与宽度占比`() {
        val oldDisplay = CropRect(x = 0f, y = 0f, width = 100f, height = 100f)
        val oldCrop = CropRect(x = 20f, y = 20f, width = 60f, height = 60f)
        // 新布局尺寸翻倍：占比不变 → 中心仍在 30% 处，宽度仍是上限的 60%。
        val newDisplay = CropRect(x = 0f, y = 0f, width = 200f, height = 200f)
        val moved = CropGeometry.recenterCrop(oldCrop, oldDisplay, 1f, newDisplay, 1f)!!
        assertEquals(120f, moved.width, 1e-3f)
        // 中心相对位置保持 50%：新布局下中心仍在 (100, 100)。
        assertEquals(100f, moved.x + moved.width / 2f, 1e-3f)
        assertEquals(100f, moved.y + moved.height / 2f, 1e-3f)
    }

    @Test
    fun `recenterCrop 无旧状态时回落初始框`() {
        val newDisplay = CropRect(x = 0f, y = 0f, width = 100f, height = 100f)
        val initial = CropGeometry.initialCrop(newDisplay, 1f)!!
        val moved = CropGeometry.recenterCrop(null, null, 1f, newDisplay, 1f)!!
        assertEquals(initial, moved)
    }

    @Test
    fun `scaleRegion 同尺寸为恒等 跨尺寸按比例并夹进目标位图`() {
        val region = CropPixelRegion(x = 10, y = 20, width = 30, height = 40)
        assertEquals(region, CropGeometry.scaleRegion(region, 100, 200, 100, 200))

        val scaled = CropGeometry.scaleRegion(region, 100, 200, 200, 400)!!
        assertEquals(20, scaled.x)
        assertEquals(40, scaled.y)
        assertEquals(60, scaled.width)
        assertEquals(80, scaled.height)

        // 越界窗口被完整夹进目标位图。
        val overflow = CropPixelRegion(x = 150, y = 350, width = 100, height = 100)
        val clamped = CropGeometry.scaleRegion(overflow, 100, 200, 200, 400)!!
        assertTrue(clamped.x >= 0 && clamped.x + clamped.width <= 200)
        assertTrue(clamped.y >= 0 && clamped.y + clamped.height <= 400)
    }

    @Test
    fun `scaleRegion 拒绝非法输入`() {
        assertNull(CropGeometry.scaleRegion(null, 100, 100, 100, 100))
        assertNull(
            CropGeometry.scaleRegion(CropPixelRegion(0, 0, 0, 0), 100, 100, 100, 100),
        )
        assertNull(CropGeometry.scaleRegion(CropPixelRegion(0, 0, 10, 10), 0, 100, 100, 100))
    }

    @Test
    fun `pixelRegion 由预览矩形换算像素窗口并夹进位图`() {
        // 1000x1000 像素的图在 100x100 预览区内铺满：比例 10。
        val display = CropRect(x = 0f, y = 0f, width = 100f, height = 100f)
        val region = CropGeometry.pixelRegion(
            CropRect(x = 10f, y = 20f, width = 30f, height = 30f), display, 1000, 1000,
        )!!
        assertEquals(100, region.x)
        assertEquals(200, region.y)
        assertEquals(300, region.width)
        assertEquals(300, region.height)
    }

    @Test
    fun `pixelRegion 拒绝未就绪的布局`() {
        val display = CropRect(x = 0f, y = 0f, width = 100f, height = 100f)
        assertNull(CropGeometry.pixelRegion(null, display, 1000, 1000))
        assertNull(
            CropGeometry.pixelRegion(
                CropRect(0f, 0f, 10f, 10f), null, 1000, 1000,
            ),
        )
        assertNull(
            CropGeometry.pixelRegion(
                CropRect(0f, 0f, 10f, 10f), display, 0, 100,
            ),
        )
        // 完全在预览区外的框：夹取后仍然在位图内，而不是抛错。
        val inside = CropGeometry.pixelRegion(
            CropRect(-50f, -50f, 10f, 10f), display, 1000, 1000,
        )
        assertNotNull(inside)
    }
}
