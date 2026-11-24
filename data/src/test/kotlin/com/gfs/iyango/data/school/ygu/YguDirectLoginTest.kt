package com.gfs.iyango.data.school.ygu

import org.junit.Assert.assertEquals
import org.junit.Test

class YguDirectLoginTest {
    @Test fun `explicit credential rejection preserves the school reason`() {
        for (message in listOf("您输入的用户名或密码有误，请重新输入", "学号不存在", "密码不正确！")) {
            val result = YguDirectLogin.loginPageFailure("<span id='showMsg'>$message</span>")
            assertEquals(YguLoginOutcome.Rejected(message), result)
        }
    }

    @Test fun `other school messages remain general errors`() {
        for (message in listOf("教务系统正在维护", "请输入用户名和密码", "密码错误次数过多，请稍后再试")) {
            val result = YguDirectLogin.loginPageFailure("""<span id="showMsg">$message</span>""")
            assertEquals(YguLoginOutcome.Failed(message), result)
        }
    }

    @Test fun `return to login without a reason leaves credentials unclassified`() {
        for (body in listOf("<form></form>", """<span id="showMsg">&nbsp;</span>""")) {
            assertEquals(YguLoginOutcome.Failed("教务系统未确认登录，请稍后重试"), YguDirectLogin.loginPageFailure(body))
        }
    }
}
