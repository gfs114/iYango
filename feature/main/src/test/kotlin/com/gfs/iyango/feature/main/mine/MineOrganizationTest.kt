package com.gfs.iyango.feature.main.mine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.ThemeMode
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MineOrganizationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `root keeps two shortcuts and moves individual settings behind categories`() {
        compose.setContent { IYangoThemePreview { Box(Modifier.size(393.dp, 760.dp)) { MineScreen() } } }
        compose.onNodeWithText("课程管理").assertIsDisplayed()
        compose.onNodeWithText("教室查询").assertIsDisplayed()
        compose.onNodeWithText("课程与课表").assertExists()
        compose.onNodeWithText("外观与布局").assertExists()
        compose.onNodeWithText("数据与备份").assertExists()
        compose.onNodeWithText("常用功能").assertDoesNotExist()
        compose.onNodeWithText("iYango").assertDoesNotExist()
        (MineCategory.entries.map { it.summary } + listOf(MineDestination.REMINDER.summary, MineDestination.ABOUT.summary)).forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        listOf("我的课表", "学期管理", "同步记录", "自定义 RRGGBB", "默认打开页面", "玻璃底栏遮罩").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun `shortcuts account and category callbacks keep distinct destinations`() {
        val visited = mutableListOf<MineDestination>()
        val categories = mutableListOf<MineCategory>()
        compose.setContent { IYangoThemePreview { MineScreen(onNavigate = visited::add, onOpenCategory = categories::add) } }
        compose.onNodeWithText("课程管理").performClick()
        compose.onNodeWithText("教室查询").performClick()
        compose.onNodeWithText("登录教务账号").performClick()
        compose.onNodeWithText("外观与布局").performScrollTo().performClick()
        compose.onNodeWithText("提醒与日历").performScrollTo().performClick()
        compose.onNodeWithText("关于与反馈").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(MineDestination.COURSES, MineDestination.CLASSROOM, MineDestination.SCHOOL,
                MineDestination.REMINDER, MineDestination.ABOUT), visited)
            assertEquals(listOf(MineCategory.APPEARANCE), categories)
        }
    }

    @Test fun `account card follows login changes without placeholder identity or initial avatar`() {
        val connection = mutableStateOf<SchoolConnection?>(null)
        compose.setContent { IYangoThemePreview { MineScreen(connection = connection.value) } }
        compose.onNodeWithText("登录教务账号").assertIsDisplayed()
        compose.onNodeWithText("同学").assertDoesNotExist()
        compose.onNodeWithText("同").assertDoesNotExist()

        val account = SchoolConnection(
            status = SchoolConnectionStatus.ACTIVE,
            maskedAccount = "20****23",
            profile = SchoolProfile(displayName = "测试姓名", className = "测试班级", studentNo = "2024000123"),
        )
        compose.runOnIdle { connection.value = account }
        compose.onNodeWithText("测试姓名").assertIsDisplayed()
        compose.onNodeWithText("测试班级\n2024000123").assertIsDisplayed()
        compose.onNodeWithText("20****23", substring = true).assertDoesNotExist()
        compose.onNodeWithText("测").assertDoesNotExist()
        compose.onNodeWithText("登录教务账号").assertDoesNotExist()

        listOf(
            SchoolConnectionStatus.VERIFYING to "正在验证登录状态",
            SchoolConnectionStatus.EXPIRED to "会话已失效 点击重新登录",
            SchoolConnectionStatus.FAILED to "连接暂不可用 点击查看",
        ).forEach { (status, message) ->
            compose.runOnIdle { connection.value = account.copy(status = status) }
            compose.onNodeWithText("测试姓名").assertIsDisplayed()
            compose.onNodeWithText(message).assertIsDisplayed()
        }

        // A logged-out record may retain cached profile fields; do not present them as signed in.
        compose.runOnIdle { connection.value = account.copy(status = SchoolConnectionStatus.LOGGED_OUT) }
        compose.onNodeWithText("登录教务账号").assertIsDisplayed()
        compose.onNodeWithText("测试姓名").assertDoesNotExist()
        compose.onNodeWithText("测试班级\n2024000123").assertDoesNotExist()
    }

    @Test fun `secondary groups keep every original management and data entry reachable`() {
        val category = mutableStateOf(MineCategory.SCHEDULE)
        val visited = mutableListOf<String>()
        var backs = 0
        compose.setContent { IYangoThemePreview {
            MineCategoryScreen(category.value, onBack = { backs++ }, onNavigate = { visited.add(it.route) })
        } }
        val expected = linkedMapOf(
            MineCategory.SCHEDULE to listOf("课程管理" to "courses", "我的课表" to "timetable", "学期管理" to "semester", "考试安排" to "exams"),
            MineCategory.SCHOOL to listOf("教务账号" to "school", "教室查询" to "classroom", "同步记录" to "synchistory"),
            MineCategory.DATA to listOf("导入导出" to "importexport", "设置备份与重置" to "settingsbackup"),
        )
        expected.forEach { (group, entries) ->
            compose.runOnIdle { category.value = group }
            entries.forEach { (label, _) -> compose.onNodeWithText(label).performScrollTo().performClick() }
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle {
            assertEquals(expected.values.flatten().map { it.second }, visited)
            assertEquals(1, backs)
        }
    }

    @Test fun `appearance page preserves current settings and forwards changes`() {
        val initial = AppearanceState(accentHex = "#AABBCC", themeMode = ThemeMode.DARK,
            tintBackground = true, dockSurfaceAlphaLight = .04f, dockSurfaceAlphaDark = .18f)
        val appearance = mutableStateOf(initial)
        val preferences = mutableStateOf(AppPreferences(defaultStartTab = 2, animationsEnabled = false))
        var changes = 0
        compose.setContent { IYangoThemePreview { MineAppearanceScreen(
            onBack = {}, appearance = appearance.value,
            onAppearanceChange = { appearance.value = it; changes++ },
            appPreferences = preferences.value,
            onDefaultStartTabChange = { preferences.value = preferences.value.copy(defaultStartTab = it) },
            onAnimationsEnabledChange = { preferences.value = preferences.value.copy(animationsEnabled = it) },
        ) } }
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onNodeWithText(ThemeMode.DARK.label).performScrollTo().assertIsSelected()
        compose.onNodeWithText(ThemeMode.LIGHT.label).performClick()
        compose.runOnIdle { assertEquals(initial.copy(themeMode = ThemeMode.LIGHT), appearance.value) }
        compose.onNodeWithText("成绩").performScrollTo().assertIsSelected()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithContentDescription("界面动画").performScrollTo().assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(3, preferences.value.defaultStartTab)
            assertTrue(preferences.value.animationsEnabled)
        }
    }

    @Test fun `appearance shortcuts keep global display scope and wallpaper navigation`() {
        val visited = mutableListOf<String>()
        compose.setContent { IYangoThemePreview { MineAppearanceScreen(onBack = {}, onNavigate = { visited.add(it.route) }) } }
        compose.onNodeWithText("课表显示").performClick()
        compose.onNodeWithText("壁纸与背景").performClick()
        compose.runOnIdle { assertEquals(listOf("display?perTimetable=false", "wallpaper"), visited) }
    }

    @Test fun `narrow large text keeps appearance controls reachable without clipping labels`() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            IYangoThemePreview { Box(Modifier.size(320.dp, 700.dp)) { MineAppearanceScreen(onBack = {}) } }
        } }
        val choice = compose.onNodeWithText(ThemeMode.SYSTEM.label)
        choice.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        choice.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        // Check rendered glyph bounds: native paragraph width can round just above its Int size.
        assertFalse(layout.didOverflowHeight)
        repeat(layout.lineCount) { line ->
            assertTrue(layout.getLineLeft(line) >= -.5f)
            assertTrue(layout.getLineRight(line) <= layout.size.width + .5f)
            assertFalse(layout.isLineEllipsized(line))
        }
        assertEquals(ThemeMode.SYSTEM.label.length, layout.getLineEnd(layout.lineCount - 1))
        compose.onNodeWithContentDescription("染背景").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("默认打开页面").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("我的").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("界面动画").performScrollTo().assertIsDisplayed()
    }
}
