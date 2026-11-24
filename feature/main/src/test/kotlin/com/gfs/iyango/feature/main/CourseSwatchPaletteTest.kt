package com.gfs.iyango.feature.main

import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import org.junit.Assert.assertEquals
import org.junit.Test

/** 双色板（paletteLight/paletteDark）槽位解析测试。 */
class CourseSwatchPaletteTest {

    @Test
    fun `编辑槽位只影响对应模式的色板`() {
        val light = CourseSwatch.effectivePalette(
            paletteLight = listOf("#111111"),
            paletteDark = emptyList(),
            dark = false,
        )
        val dark = CourseSwatch.effectivePalette(
            paletteLight = listOf("#111111"),
            paletteDark = listOf("#EEEEEE"),
            dark = true,
        )
        assertEquals("#111111", light.first())
        assertEquals("#EEEEEE", dark.first())
        assertEquals(CourseSwatch.accents[1], light[1])
        assertEquals(CourseSwatch.accents[1], dark[1])
    }

    @Test
    fun `空槽回退内置预设`() {
        val pool = CourseSwatch.effectivePalette(
            paletteLight = listOf("", "not-a-color"),
            paletteDark = emptyList(),
            dark = false,
        )
        assertEquals(CourseSwatch.accents, pool)
    }

    @Test
    fun `内置预设与旧版色值都映射回原槽位`() {
        assertEquals(0, CourseSwatch.slotIndex(CourseSwatch.accents[0]))
        assertEquals(2, CourseSwatch.slotIndex("#ff8c00"))
        assertEquals(0, CourseSwatch.slotIndex("#0A59F7"))
    }

    @Test
    fun `编辑后的色板值仍然映射到自己的槽位`() {
        val palette = listOf("#123456", "#654321")
        assertEquals(1, CourseSwatch.slotIndex("#654321", paletteLight = palette))
        assertEquals(1, CourseSwatch.slotIndex("#654321", paletteDark = palette))
    }

    @Test
    fun `任意自定义色不属于任何槽位`() {
        assertEquals(-1, CourseSwatch.slotIndex("#ABCDEF"))
        assertEquals(-1, CourseSwatch.slotIndex(""))
    }
}
