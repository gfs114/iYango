package com.gfs.iyango.data.school.ygu

/** 阳光学院强智教务站点的固定端点（与 HarmonyOS 版逐字一致）。 */
internal object YguConfig {
    const val ID: String = "ygu"
    const val NAME: String = "阳光学院教务系统"
    const val ORIGIN: String = "https://jwgl.ygu.edu.cn"
    const val BASE: String = "$ORIGIN/jsxsd"
    const val LOGIN: String = "$BASE/"
    const val LOGON: String = "$BASE/xk/LoginToXkLdap"
    const val HOME: String = "$BASE/framework/xsMainV.htmlx"
    const val PROFILE_HOME: String = "$BASE/framework/xsMainV_new.htmlx?t1=1&ggflag=&jumpid="
    const val COURSE_ENTRY: String = "$BASE/xskb/xskb_list.do"
    const val WRAPPER: String = "$BASE/framework/xsMainV.htmlx?jumpUrl=/view/kbxx/kbcx/kbxx_xzb_ljjy.jsp"
    const val TIMETABLE: String = "$BASE/view/kbxx/kbcx/kbxx_xzb_ljjy.jsp"
    const val CURRICULUM_PROGRESS: String = "$BASE/xxwcqk/xxwcqkOnkclb.do?isdb=0"

    /** 只允许访问学校站点下的 jsxsd 路径；登录重定向额外允许站点根。 */
    fun allowedRequestUrl(url: String): Boolean {
        if (url == ORIGIN || url == "$ORIGIN/") return true
        return url.startsWith("$ORIGIN/jsxsd/")
    }
}
