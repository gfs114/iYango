package com.gfs.iyango.feature.main.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.model.*
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayAccountUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = SchoolConnection(
        id = "saved", maskedAccount = "202****001", status = SchoolConnectionStatus.ACTIVE,
        profile = SchoolProfile(displayName = "测试同学"),
    )
    private fun ready(connection: SchoolConnection? = null, week: Int = 9): TodayUiState.Ready {
        val date = LocalDate.of(2026, 11, 5)
        return TodayUiState.Ready(
            timetable = Timetable(id = 1), semester = Semester(startDate = date.minusWeeks(8)),
            week = week, today = date, todayRows = emptyList(), tomorrow = date.plusDays(1),
            tomorrowRows = emptyList(), busyDates = emptySet(), month = YearMonth.from(date),
            timeSlots = emptyList(), detailCourses = emptyList(), connection = connection,
        )
    }
    private fun render(state: () -> TodayUiState.Ready, onProfileClick: () -> Unit = {}) {
        compose.setContent { IYangoThemePreview { Box(Modifier.size(393.dp, 700.dp)) {
            TodayReadyContent(
                state(), 96.dp, 0.dp, emptyList(), emptyList(), {}, {}, {}, {}, onProfileClick,
            )
        } } }
    }

    @Test fun `saved active account displays name on first composition without subtitle`() {
        render({ ready(account) })
        compose.onNodeWithText("测试同学", substring = true).assertIsDisplayed()
        compose.onNodeWithText("未绑定教务账号", substring = true).assertDoesNotExist()
    }

    @Test fun `expiration failure and logout change the existing card and keep account entry clickable`() {
        val state = mutableStateOf(ready(account))
        var clicks = 0
        render({ state.value }) { clicks++ }
        compose.runOnIdle { state.value = ready(account.copy(status = SchoolConnectionStatus.EXPIRED)) }
        compose.onNodeWithText("测试同学", substring = true).assertIsDisplayed().performClick()
        compose.runOnIdle { state.value = ready(account.copy(status = SchoolConnectionStatus.FAILED)) }
        compose.onNodeWithText("测试同学", substring = true).assertIsDisplayed()
        compose.runOnIdle { state.value = ready() }
        compose.onNodeWithText("同学", substring = true).assertIsDisplayed()
        compose.onNodeWithText("测试同学", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun `missing profile uses masked account and verification keeps masked identity`() {
        val state = mutableStateOf(ready(account.copy(profile = SchoolProfile())))
        render({ state.value })
        compose.onNodeWithText("202****001", substring = true).assertIsDisplayed()
        compose.runOnIdle { state.value = ready(account.copy(profile = SchoolProfile(), status = SchoolConnectionStatus.VERIFYING), week = 0) }
        compose.onNodeWithText("202****001", substring = true).assertIsDisplayed()
        compose.runOnIdle { state.value = ready(account, week = 0).copy(semester = null) }
        compose.onNodeWithText("测试同学", substring = true).assertIsDisplayed()
    }

    @Test fun `explicitly logged out account does not expose saved profile as current identity`() {
        render({ ready(account.copy(status = SchoolConnectionStatus.LOGGED_OUT)) })
        compose.onNodeWithText("同学", substring = true).assertIsDisplayed()
        compose.onNodeWithText("测试同学", substring = true).assertDoesNotExist()
        compose.onNodeWithText("202****001", substring = true).assertDoesNotExist()
    }
}
