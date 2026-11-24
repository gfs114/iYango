package com.gfs.iyango.feature.main.schedule

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gfs.iyango.core.model.*
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.feature.main.coursedetail.CourseDetailDialog
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h852dp-mdpi")
class TermBrowseHeaderTest {
    @get:Rule val compose=createComposeRule()
    @Test fun `term menu uses school options and return survives loading error`() {
        var selected=""; var returned=false
        compose.setContent { IYangoThemePreview {
            TermBrowseHeader(TermBrowseController.State(account=SchoolConnection(id="a"),
                terms=listOf(SchoolTermOption("2025-2026-2","2025-2026-2")), selected="failed",error="offline"),
                {},{selected=it},{returned=true},{})
        } }
        compose.onNodeWithTag("school-term-selector").performClick()
        compose.onNodeWithText("2025-2026-2").performClick()
        compose.runOnIdle { assertEquals("2025-2026-2",selected) }
        compose.onNodeWithTag("return-current-timetable").performClick()
        compose.runOnIdle { assertTrue(returned) }
    }
    @Test fun `browse detail has no mutation actions or synthetic dates`() {
        compose.setContent { IYangoThemePreview {
            CourseDetailDialog(courses=listOf(Course(id=-1,name="History",startWeek=1,endWeek=20)),
                timeSlots=emptyList(),displayedWeek=3,readOnly=true,onEdit={},onCopy={},onDelete={_,_->},onAdjust={},onDismiss={})
        } }
        compose.onNodeWithText("学期浏览 只读").assertIsDisplayed()
        listOf("编辑","复制","删除","调整课程","恢复该日课表").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("关闭").assertDoesNotExist()
    }
    @Test fun `selected term loading keeps add disabled and known grid still opens week picker`() {
        val ui=androidx.compose.runtime.mutableStateOf<ScheduleUiState>(ScheduleUiState.FirstLoading)
        var adds=0
        compose.setContent { IYangoThemePreview {
            ScheduleContent(state=ui.value,termBrowsing=TermBrowseController.State(selected="old"),
                onPreviousWeek={},onNextWeek={},onPickWeek={},onBackToCurrentWeek={},onRetry={},
                onDeleteCourse={_,_,_,_->},onApplyAdjustment={_,_,_,_,_,_,_,_->},onAddCourse={adds++})
        } }
        compose.onNodeWithContentDescription("添加课程").assertIsNotEnabled()
        compose.runOnIdle {
            ui.value=projectBrowsedTerm(SchoolTimetableData(termValue="old"),1,java.time.LocalDateTime.of(2026,11,1,8,0),4){_,_,_,_->emptyList()}
        }
        compose.onNodeWithText("2026/11/1").assertIsDisplayed()
        compose.onNodeWithText("第1周 周日").performClick()
        compose.onNodeWithText("选择学期与周次").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0,adds) }
    }

    @Test fun `title opens unified chooser without a current timetable`() {
        compose.setContent { IYangoThemePreview {
            ScheduleContent(state=ScheduleUiState.NoTimetable,
                onPreviousWeek={},onNextWeek={},onPickWeek={},onBackToCurrentWeek={},onRetry={},
                onDeleteCourse={_,_,_,_->},onApplyAdjustment={_,_,_,_,_,_,_,_->})
        } }
        compose.onNodeWithTag("school-term-selector").assertDoesNotExist()
        compose.onNodeWithText("课表", useUnmergedTree = true).performClick()
        compose.onNodeWithText("选择学期与周次").assertIsDisplayed()
        compose.onNodeWithTag("school-term-selector").assertIsDisplayed()
    }

    @Test fun `chooser survives term loading and failure then closes after week pick`() {
        val source=SchoolTimetableData(termValue="old",termLabel="历史学期")
        val ui=androidx.compose.runtime.mutableStateOf<ScheduleUiState>(ScheduleUiState.NoSemester)
        val browse=androidx.compose.runtime.mutableStateOf(TermBrowseController.State(
            account=SchoolConnection(id="a"),terms=listOf(SchoolTermOption("old","历史学期"))))
        var picked=0
        compose.setContent { IYangoThemePreview {
            ScheduleContent(state=ui.value,termBrowsing=browse.value,
                onBrowseTerm={ browse.value=browse.value.copy(selected=it,loading=true) },
                onPreviousWeek={},onNextWeek={},onPickWeek={picked=it},onBackToCurrentWeek={},onRetry={},
                onDeleteCourse={_,_,_,_->},onApplyAdjustment={_,_,_,_,_,_,_,_->})
        } }
        compose.onNodeWithText("课表",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("school-term-selector").performClick()
        compose.onNodeWithText("历史学期").performClick()
        compose.onNodeWithText("选择学期与周次").assertIsDisplayed()
        compose.onNodeWithText("正在读取学校课表…").assertIsDisplayed()
        compose.onNodeWithText("1",useUnmergedTree=true).assertDoesNotExist()
        compose.runOnIdle {
            browse.value=browse.value.copy(loading=false,error="离线")
            ui.value=ScheduleUiState.Error("离线",true)
        }
        compose.onNodeWithText("选择学期与周次").assertIsDisplayed()
        compose.onNodeWithText("重试读取课表").assertIsDisplayed()
        compose.onNodeWithText("1",useUnmergedTree=true).assertDoesNotExist()
        compose.runOnIdle {
            browse.value=browse.value.copy(data=source,error=null)
            ui.value=projectBrowsedTerm(source,1,java.time.LocalDateTime.of(2026,11,1,8,0),4){_,_,_,_->emptyList()}
        }
        compose.onNodeWithText("2",useUnmergedTree=true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2,picked) }
        compose.onNodeWithText("选择学期与周次").assertDoesNotExist()
    }

    @Test fun `large font narrow chooser can scroll to final week`() {
        val source=SchoolTimetableData(termValue="old",termLabel="历史学期")
        val data=projectBrowsedTerm(source,1,java.time.LocalDateTime.of(2026,11,1,8,0),4){_,_,_,_->emptyList()}.data
        var picked=0
        compose.setContent { IYangoThemePreview {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(1f,1.6f)) {
                WeekPickerSheet(data=data,onPick={picked=it},onDismiss={},
                    termBrowsing=TermBrowseController.State(selected="old",data=source))
            }
        } }
        compose.onNodeWithText("20",useUnmergedTree=true).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(20,picked) }
    }

    @Test fun `projection failure offers retry inside chooser without reselecting term`() {
        var retries=0
        var selections=0
        compose.setContent { IYangoThemePreview {
            ScheduleContent(state=ScheduleUiState.Error("周次计算失败",true),
                termBrowsing=TermBrowseController.State(selected="old",data=SchoolTimetableData(termValue="old")),
                onBrowseTerm={selections++},onPreviousWeek={},onNextWeek={},onPickWeek={},onBackToCurrentWeek={},
                onRetry={retries++},onDeleteCourse={_,_,_,_->},onApplyAdjustment={_,_,_,_,_,_,_,_->})
        } }
        compose.onNodeWithText("课表", useUnmergedTree = true).performClick()
        compose.onNodeWithText("重试读取课表").performClick()
        compose.runOnIdle { assertEquals(1,retries); assertEquals(0,selections) }
        compose.onNodeWithText("选择学期与周次").assertIsDisplayed()
    }

    @Test fun `chooser network retry uses scoped refresh and never the pull return action`() {
        var scopedRefreshes = 0
        var pulls = 0
        var retries = 0
        compose.setContent { IYangoThemePreview {
            ScheduleContent(
                state = ScheduleUiState.Error("offline", true),
                termBrowsing = TermBrowseController.State(selected = "old", error = "offline"),
                onRefresh = { scopedRefreshes++ },
                onPullRefresh = { pulls++ },
                onPreviousWeek = {}, onNextWeek = {}, onPickWeek = {}, onBackToCurrentWeek = {},
                onRetry = { retries++ }, onDeleteCourse = { _, _, _, _ -> },
                onApplyAdjustment = { _, _, _, _, _, _, _, _ -> },
            )
        } }
        compose.onNodeWithText("课表", useUnmergedTree = true).performClick()
        compose.onNodeWithText("重试读取课表").performClick()
        compose.runOnIdle {
            assertEquals(1, scopedRefreshes)
            assertEquals(0, pulls)
            assertEquals(0, retries)
        }
        compose.onNodeWithText("选择学期与周次").assertIsDisplayed()
    }

}
