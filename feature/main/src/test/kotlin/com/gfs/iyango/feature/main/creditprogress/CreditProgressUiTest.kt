package com.gfs.iyango.feature.main.creditprogress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.CreditGradeSourceSnapshot
import com.gfs.iyango.core.domain.CreditProgressCalculator
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.model.CreditAccountPlan
import com.gfs.iyango.core.model.CreditCoverageStatus
import com.gfs.iyango.core.model.CreditPassThreshold
import com.gfs.iyango.core.model.CurriculumCourseItem
import com.gfs.iyango.core.model.CurriculumNatureGroup
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.CurriculumStudyStatus
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSnapshot
import com.gfs.iyango.core.model.HomeShortcut
import com.gfs.iyango.feature.main.grades.GradesHeader
import com.gfs.iyango.feature.main.grades.GradesUiState
import com.gfs.iyango.feature.main.today.homeShortcutTitle
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreditProgressUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `GradesHeader shows credit progress button and HomeShortcut title is学分进度`() {
        assertEquals("学分进度", homeShortcutTitle(HomeShortcut.CREDIT_PROGRESS.id))

        var opened = 0
        compose.setContent {
            IYangoThemePreview {
                GradesHeader(
                    state = GradesUiState(connectionId = "conn-1", loggedIn = true),
                    onOpenSchool = {},
                    onOpenCreditProgress = { opened++ },
                )
            }
        }
        compose.onNodeWithText("学分进度").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `not queried with saved target renders dash over target instead of zero`() {
        val plan = CreditAccountPlan(
            accountKey = "2024001",
            totalTargetCredits = BigDecimal("160.00"),
        )
        val report = CreditProgressCalculator.calculate(
            CreditGradeSourceSnapshot(accountKey = "2024001"),
            plan,
        )
        val uiState = CreditProgressUiState(
            hasConnection = true,
            loggedIn = false,
            accountKey = "2024001",
            accountDisplayName = "测试同学",
            plan = plan,
            report = report,
        )

        compose.setContent {
            IYangoThemePreview {
                Box(Modifier.size(393.dp, 852.dp)) {
                    CreditProgressPageLayout(state = uiState)
                }
            }
        }

        compose.onNodeWithText("— / 160 学分").assertIsDisplayed()
        compose.onNode(hasText(CreditPassThreshold.SOURCE_DESCRIPTION, substring = true)).assertDoesNotExist()
    }

    @Test
    fun `legacy cache merges refresh-only count into banner without spamming pending cards`() {
        val legacyRecords = (1..3).map { index ->
            GradeRecord(
                accountKey = "2024001",
                semester = "2023-2024-1",
                courseName = "旧课$index",
                courseCode = "OLD$index",
                credit = 2.0,
                scoreText = "85",
                scoreValue = 85.0,
                attribute = "必修",
                schoolRecordId = "old-$index",
                rawAttributesKnown = false,
            )
        }
        val source = CreditGradeSourceSnapshot(
            accountKey = "2024001",
            allSemesterSnapshot = GradeSnapshot(
                accountKey = "2024001",
                semester = GradeRepositoryPort.ALL_SEMESTERS,
                complete = true,
                queryFingerprint = GradeRepositoryPort.ALL_SEMESTERS,
                targetSemesters = listOf("2023-2024-1"),
                queriedSemesters = listOf("2023-2024-1"),
            ),
            allSemesterRecords = legacyRecords,
        )
        val plan = CreditAccountPlan(accountKey = "2024001")
        val report = CreditProgressCalculator.calculate(source, plan)
        assertEquals(CreditCoverageStatus.LEGACY_CACHE_NEEDS_REFRESH, report.coverageStatus)

        val uiState = CreditProgressUiState(
            hasConnection = true,
            loggedIn = true,
            accountKey = "2024001",
            plan = plan,
            report = report,
            legacyRefreshOnlyCount = report.pendingCourses.count { it.isLegacyRefreshOnly },
            actionablePendingCourses = report.pendingCourses.filterNot { it.isLegacyRefreshOnly },
        )

        compose.setContent {
            IYangoThemePreview {
                Box(Modifier.size(393.dp, 852.dp)) {
                    CreditProgressPageLayout(state = uiState)
                }
            }
        }

        compose.onNodeWithText("旧版缓存待更新全部学期").assertDoesNotExist()
        compose.onNode(hasText("待确认课程", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `actionable pending course renders card and opens resolution dialog`() {
        val pendingRecord = GradeRecord(
            accountKey = "2024001",
            semester = "2024-2025-1",
            courseName = "特色板块课",
            courseCode = "SP01",
            credit = 2.0,
            rawCreditText = "2",
            scoreText = "免修",
            scoreValue = null,
            attribute = "必修",
            rawCourseAttribute = "必修",
            rawAttributesKnown = true,
            schoolRecordId = "sp-1",
        )
        val source = CreditGradeSourceSnapshot(
            accountKey = "2024001",
            allSemesterSnapshot = GradeSnapshot(
                accountKey = "2024001",
                semester = GradeRepositoryPort.ALL_SEMESTERS,
                complete = true,
                queryFingerprint = GradeRepositoryPort.ALL_SEMESTERS,
                targetSemesters = listOf("2024-2025-1"),
                queriedSemesters = listOf("2024-2025-1"),
            ),
            allSemesterRecords = listOf(pendingRecord),
        )
        val plan = CreditAccountPlan(accountKey = "2024001")
        val report = CreditProgressCalculator.calculate(source, plan)
        assertEquals(1, report.pendingCourses.size)
        val uiState = CreditProgressUiState(
            hasConnection = true,
            loggedIn = true,
            accountKey = "2024001",
            plan = plan,
            report = report,
            legacyRefreshOnlyCount = 0,
            actionablePendingCourses = report.pendingCourses,
        )

        compose.setContent {
            IYangoThemePreview {
                Box(Modifier.size(393.dp, 852.dp)) {
                    CreditProgressPageLayout(state = uiState)
                }
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("确认处理"))
        compose.onNodeWithText("待确认课程（1）").assertIsDisplayed()
        compose.onNodeWithText("确认处理").assertIsDisplayed().performClick()
        compose.onNodeWithText("确认课程归属：特色板块课").assertIsDisplayed()
    }

    @Test
    fun `curriculum snapshot renders plan title totals and nature groups`() {
        val snapshot = CurriculumProgressSnapshot(
            planTitle = "2024 专业培养方案及教学计划",
            totalEarnedCredits = BigDecimal("107"),
            totalInProgressCredits = BigDecimal("21.75"),
            groups = listOf(
                CurriculumNatureGroup(
                    courseNature = "公共必修课",
                    earnedCredits = BigDecimal("61.5"),
                    inProgressCredits = BigDecimal("1.25"),
                    courses = listOf(
                        CurriculumCourseItem(
                            courseCode = "101305003",
                            courseName = "思想道德与法治",
                            credit = BigDecimal("2.5"),
                            creditText = "2.5",
                            courseAttribute = "必修",
                            courseNature = "公共必修课",
                            studyStatus = CurriculumStudyStatus.COMPLETED,
                            scoreText = "70",
                            isPassed = true,
                        ),
                        CurriculumCourseItem(
                            courseCode = "101307025",
                            courseName = "形势与政策(五)",
                            credit = BigDecimal("0.25"),
                            creditText = "0.25",
                            courseAttribute = "必修",
                            courseNature = "公共必修课",
                            studyStatus = CurriculumStudyStatus.IN_PROGRESS,
                        ),
                        CurriculumCourseItem(
                            courseCode = "101307026",
                            courseName = "形势与政策(六)",
                            credit = BigDecimal("0.25"),
                            creditText = "0.25",
                            courseAttribute = "必修",
                            courseNature = "公共必修课",
                            studyStatus = CurriculumStudyStatus.PENDING_STUDY,
                        ),
                    ),
                ),
                CurriculumNatureGroup(
                    courseNature = "专业选修课",
                    earnedCredits = BigDecimal("7.5"),
                    inProgressCredits = BigDecimal("10"),
                    courses = emptyList(),
                ),
            ),
            fetchedAtEpochMillis = 1234L,
        )
        val plan = CreditAccountPlan(
            accountKey = "2024001",
            curriculumSnapshot = snapshot,
        )
        val report = CreditProgressCalculator.calculate(
            CreditGradeSourceSnapshot(accountKey = "2024001"),
            plan,
        )
        val uiState = CreditProgressUiState(
            hasConnection = true,
            loggedIn = true,
            accountKey = "2024001",
            plan = plan,
            curriculumSnapshot = snapshot,
            report = report,
        )

        compose.setContent {
            IYangoThemePreview {
                Box(Modifier.size(393.dp, 852.dp)) {
                    CreditProgressPageLayout(state = uiState)
                }
            }
        }

        // 107 (已修读) + 21.75 (修读中) + 0.25 (待修读) = 129
        compose.onNodeWithText("107 / 129 学分").assertIsDisplayed()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("公共必修课"))
        compose.onNodeWithText("公共必修课").assertIsDisplayed().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("思想道德与法治 (101305003)"))
        compose.onNodeWithText("思想道德与法治 (101305003)").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("形势与政策(五) (101307025)"))
        compose.onNodeWithText("形势与政策(五) (101307025)").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("形势与政策(六) (101307026)"))
        compose.onNodeWithText("形势与政策(六) (101307026)").assertIsDisplayed()
    }
}
