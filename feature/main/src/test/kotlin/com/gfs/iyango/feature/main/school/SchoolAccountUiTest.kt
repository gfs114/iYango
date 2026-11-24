package com.gfs.iyango.feature.main.school

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.model.*
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
class SchoolAccountUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val current = SchoolConnection(id = "current", maskedAccount = "20****01", status = SchoolConnectionStatus.ACTIVE,
        profile = SchoolProfile(displayName = "当前姓名", studentNo = "TEST0001", className = "测试班级"))
    private val history = SchoolConnection(id = "history", maskedAccount = "20****02", rememberPassword = true,
        profile = SchoolProfile(displayName = "历史姓名"))

    @Test fun `overview shows permanent profile details and common actions without restoring a session`() {
        val actions = mutableListOf<AccountAction>()
        compose.setContent { IYangoThemePreview { SchoolAccountContent(
            SchoolAccountUiState(active = current, connections = listOf(current)), {}, {}, actions::add) } }
        compose.onNodeWithText("当前姓名").assertIsDisplayed()
        compose.onNodeWithText("TEST0001").assertIsDisplayed()
        compose.onNodeWithText("测试班级").assertIsDisplayed()
        compose.onNodeWithText("退出登录").assertIsDisplayed()
        listOf("删除账号", "历史账号", "会话已恢复", "最近登录", "密码保存", "同步教务课表", "刷新资料").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun `profile stays expanded and management expands in place with one history entry for each account`() {
        val actions = mutableListOf<AccountAction>()
        compose.setContent { IYangoThemePreview { SchoolAccountContent(
            SchoolAccountUiState(active = current, connections = listOf(current, history)), {}, {}, actions::add) } }
        compose.onNodeWithText("TEST0001").assertIsDisplayed()
        compose.onNodeWithText("退出登录").performScrollTo().performClick()
        compose.onNodeWithText("账号管理").performScrollTo().performClick()
        // Once in the overview header and once in history, even though active is also in connections.
        compose.onAllNodesWithText("当前姓名").assertCountEquals(2)
        compose.onNodeWithText("当前使用").assertExists()
        compose.onNodeWithText("历史姓名").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("填写账号").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(AccountAction.Logout, AccountAction.Fill(history)), actions) }
        compose.onNodeWithText("账号管理").performScrollTo().performClick()
        compose.onNodeWithText("历史姓名").assertDoesNotExist()
        compose.onNodeWithText("同步教务课表").assertDoesNotExist()
    }

    @Test fun `busy operations disable account mutations and switching uses an explicit action`() {
        val state = mutableStateOf(SchoolAccountUiState(active = current, connections = listOf(current, history)))
        val actions = mutableListOf<AccountAction>()
        compose.setContent { IYangoThemePreview { SchoolAccountContent(state.value, {}, {}, actions::add) } }
        compose.onNodeWithText("账号管理").performScrollTo().performClick()
        compose.onNodeWithText("切换账号").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(AccountAction.Restore(history.id)), actions)
            state.value = state.value.copy(restoringId = history.id) }
        listOf("正在恢复教务会话…", "填写账号", "清空历史账号", "使用其他账号登录").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsNotEnabled()
        }
        compose.onAllNodesWithText("删除").fetchSemanticsNodes().indices.forEach { index ->
            compose.onAllNodesWithText("删除")[index].performScrollTo().assertIsNotEnabled()
        }
        // Return to the top so the tap is below the fixed glass header, not behind it.
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
        compose.onNodeWithText("退出登录").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `login form validates empty input masks password and returns through local pages`() {
        val state = mutableStateOf(SchoolAccountUiState(active = current, connections = listOf(current)))
        val actions = mutableListOf<AccountAction>()
        compose.setContent { IYangoThemePreview { SchoolAccountContent(state.value, {}, {}) {
            actions += it
            state.value = when (it) {
                AccountAction.OpenLogin -> state.value.copy(loginEntry = SchoolLoginEntry.OTHER)
                AccountAction.CloseLogin -> state.value.copy(loginEntry = null, password = "")
                AccountAction.Submit -> state.value.copy(submitting = true)
                is AccountAction.AccountChanged -> state.value.copy(account = it.value)
                is AccountAction.PasswordChanged -> state.value.copy(password = it.value)
                else -> state.value
            }
        } } }
        compose.onNodeWithText("账号管理").performClick()
        compose.onNodeWithText("使用其他账号登录").performClick()
        compose.onNodeWithText("登录其他账号").assertExists()
        compose.onNodeWithText("iYango").assertIsDisplayed()
        compose.onNodeWithText("返回").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        val fieldBounds = compose.onNodeWithContentDescription("学号").getUnclippedBoundsInRoot()
        val loginBounds = compose.onNodeWithText("登录").getUnclippedBoundsInRoot()
        assertEquals(fieldBounds.left, loginBounds.left)
        assertEquals(fieldBounds.right, loginBounds.right)
        compose.onNodeWithText("登录").assertIsNotEnabled()
        compose.onNodeWithContentDescription("学号").performTextInput("test-account")
        compose.onNodeWithText("学号", useUnmergedTree = true).assertIsDisplayed()
        val loginBeforeTyping = compose.onNodeWithText("登录").getUnclippedBoundsInRoot().top
        compose.onNodeWithContentDescription("密码").performTextInput("test-password")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists()
        assertEquals(loginBeforeTyping, compose.onNodeWithText("登录").getUnclippedBoundsInRoot().top)
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        compose.onNodeWithText("登录").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("清空密码").performScrollTo().performClick()
        compose.onNodeWithContentDescription("密码").assertIsFocused()
        compose.onNodeWithText("登录").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("密码").performScrollTo().performTextInput("test-password")
        compose.onNodeWithContentDescription("密码").performImeAction()
        compose.runOnIdle { assertEquals(1, actions.count { it == AccountAction.Submit })
            state.value = state.value.copy(submitting = false, password = "") }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("当前使用").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals("", state.value.password); assertTrue(actions.contains(AccountAction.CloseLogin)) }
    }

    @Test fun `successful login or saved account switch returns to the new account overview`() {
        val state = mutableStateOf(SchoolAccountUiState(active = current, connections = listOf(current, history)))
        compose.setContent { IYangoThemePreview { SchoolAccountContent(state.value, {}, {}, {}) } }
        compose.onNodeWithText("账号管理").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(active = history.copy(status = SchoolConnectionStatus.ACTIVE)) }
        compose.onNodeWithText("历史姓名").assertIsDisplayed()
        compose.onNodeWithText("当前姓名").assertDoesNotExist()
    }

    @Test fun `profile details remain visible across state restoration`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { IYangoThemePreview { SchoolAccountContent(SchoolAccountUiState(active = current), {}, {}, {}) } }
        compose.onNodeWithText("TEST0001").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("TEST0001").assertIsDisplayed()
    }

    @Test fun `large font narrow window keeps fields login and management actions reachable`() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            IYangoThemePreview { Box(Modifier.size(320.dp, 700.dp)) {
                SchoolAccountContent(SchoolAccountUiState(), {}, {}, {})
            } }
        } }
        compose.onNodeWithText("学号").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("密码").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("记住密码").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("登录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("iYango").assertExists()
        compose.onNodeWithText("历史账号（", substring = true).assertDoesNotExist()
        compose.onNodeWithText("教务网站").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("登录帮助").performScrollTo().performClick()
        compose.onNodeWithText("打开教务网站").performScrollTo().assertIsDisplayed()
    }

    @Test fun `history expands inside the branded form and prefilling returns to the same list`() {
        val state = mutableStateOf(SchoolAccountUiState(connections = listOf(history, history)))
        val actions = mutableListOf<AccountAction>()
        compose.setContent { IYangoThemePreview { SchoolAccountContent(state.value, {}, {}) {
            actions += it
            when (it) {
                is AccountAction.Fill -> state.value = state.value.copy(loginEntry = SchoolLoginEntry.HISTORY, account = "example")
                AccountAction.CloseLogin -> state.value = state.value.copy(loginEntry = null)
                else -> Unit
            }
        } } }
        compose.onNodeWithText("iYango").assertIsDisplayed()
        compose.onNodeWithText("历史账号（1）").performScrollTo().performClick()
        compose.onNodeWithText("收起历史账号").assertExists()
        compose.onNodeWithText("教务登录").assertExists()
        compose.onNodeWithText("填写账号").performScrollTo().performClick()
        compose.onNodeWithText("重新登录").assertExists()
        compose.onNodeWithText("example").assertExists()
        compose.onNodeWithText("登录帮助").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("重新登录").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("历史姓名").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(AccountAction.Fill(history), AccountAction.CloseLogin), actions) }
    }

    @Test fun `relogin hint follows checkbox and submitting announces progress only in the button`() {
        val state = mutableStateOf(SchoolAccountUiState())
        compose.setContent { IYangoThemePreview { SchoolAccountContent(state.value, {}, {}) {
            if (it is AccountAction.RememberChanged) state.value = state.value.copy(rememberPassword = it.value)
        } } }
        compose.onNodeWithText("自动重登").assertExists()
        compose.onNodeWithText("记住密码").performScrollTo().performClick()
        compose.onNodeWithText("手动登录").performScrollTo().performClick()
        compose.onNodeWithText("记住本次登录密码。7 天内会话失效时自动重登。").performClick()
        compose.onNodeWithText("自动重登").assertExists()
        compose.onNodeWithText("记住密码").assertIsOn()
        compose.runOnIdle { state.value = state.value.copy(submitting = true, message = "正在登录教务系统…") }
        compose.onAllNodesWithText("正在登录…").assertCountEquals(1)
        compose.onNodeWithText("正在登录教务系统…").assertDoesNotExist()
        compose.onNodeWithText("正在登录…").assertIsNotEnabled()
    }

    @Test fun `restored login help without its form returns to the originating history list`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { IYangoThemePreview {
            var entry by remember { mutableStateOf<SchoolLoginEntry?>(null) }
            SchoolAccountContent(SchoolAccountUiState(connections = listOf(history), loginEntry = entry), {}, {}) {
                if (it is AccountAction.Fill) entry = SchoolLoginEntry.HISTORY
            }
        } }
        compose.onNodeWithText("历史账号（1）").performScrollTo().performClick()
        compose.onNodeWithText("填写账号").performScrollTo().performClick()
        compose.onNodeWithText("登录帮助").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("收起历史账号").assertExists()
        compose.onNodeWithText("历史姓名").performScrollTo().assertIsDisplayed()
        assertEquals("", compose.onNodeWithContentDescription("密码").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    @Test fun `school rejection appears by password and connection failure stays by submit`() {
        val state = mutableStateOf(SchoolAccountUiState(problem = AccountProblem.Login("学校拒绝了凭据", true)))
        compose.setContent { IYangoThemePreview { SchoolAccountContent(state.value, {}, {}, {}) } }
        compose.onAllNodesWithText("学校拒绝了凭据").assertCountEquals(1)
        assertTrue(compose.onNodeWithText("学校拒绝了凭据").getUnclippedBoundsInRoot().bottom <=
            compose.onNodeWithText("记住密码").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { state.value = state.value.copy(problem = AccountProblem.Login("连接失败")) }
        compose.onNodeWithText("学校拒绝了凭据").assertDoesNotExist()
        compose.onAllNodesWithText("连接失败").assertCountEquals(1)
        val error = compose.onNodeWithText("连接失败").getUnclippedBoundsInRoot()
        assertTrue(error.top >= compose.onNodeWithText("记住密码").getUnclippedBoundsInRoot().bottom)
        assertTrue(error.bottom <= compose.onNodeWithText("登录").getUnclippedBoundsInRoot().top)
    }
}
