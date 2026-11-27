package com.gfs.iyango.data.update

/**
 * 应用更新检查的远端清单地址配置。
 */
object AppUpdateConfig {
    /**
     * 应用更新清单 JSON 地址（指向 GitHub Releases API；开发者模式可自定义覆盖）。
     *
     * 支持以下两种格式：
     * 1. 自定义静态 JSON：`{"versionCode":4,"versionName":"0.1.3","releaseNotes":"...","downloadUrl":"https://...","forceUpdate":false}`
     * 2. GitHub Releases API：`https://api.github.com/repos/<owner>/<repo>/releases/latest`
     *
     * 留空时启动自动检测会静默跳过，关于页手动检查会提示“暂未配置更新地址”。
     */
    const val UPDATE_CHECK_URL: String = "https://api.github.com/repos/gfs114/iYango/releases/latest"
}
