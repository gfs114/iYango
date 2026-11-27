<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="96" alt="iYango" />

# iYango

面向阳光学院的本地优先 Android 校园课表与教务助手

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/license-MIT-3DA639)](LICENSE)

</div>

---

## 下载安装

前往 [Releases](https://github.com/gfs114/iYango/releases/latest) 下载 `iYango-release.apk`（支持 Android 8.0 及以上），安装后可在应用内「版本更新」页检查并下载新版。

## 核心功能

- **课表与日程**：今日日程、周课表网格/时间轴双视图、多课表管理与对比、单双周与单次调课、法定节假日与调休感知、学期数据海报
- **教务集成**：阳光学院教务登录与课表同步、成绩与 GPA / 学分进度统计、实时空教室查询
- **提醒与静音**：课前提醒、上课自动静音/免打扰与定时恢复、系统日历同步、Android 16+ 实时进度与出分 Live Update、多尺寸桌面小组件与快捷磁贴
- **AI 助理与导课**：自带 Key 接入 OpenAI / Anthropic 接口，支持流式对话、课表/成绩/空教室查询与二次确认改课，以及拍图或粘贴文本识表导入
- **导入导出与同步**：支持 ICS / WakeUp / CSV / JSON 导入导出、口令分享与二维码扫码/相册识码、WebDAV 加密云备份同步
- **外观与更新**：Liquid Glass 液态玻璃材质、动态壁纸、自定义强调色与字体、内置版本更新页（支持下载镜像切换与历史版本查看）

## 隐私与安全

- **本地优先**：课表、成绩与设置均存储在本机，云同步仅在主动配置 WebDAV 后启用
- **加密凭据**：教务密码、WebDAV 凭据与 AI API Key 使用系统 Keystore 加密保存，不进入系统备份
- **直连请求**：AI 请求由设备直接发往用户配置的接口地址，不经过中转服务器

## 许可与声明

- 本项目基于 [MIT 许可证](LICENSE) 开源
- 液态玻璃材质基于 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）；学分进度分类思路参考 [XJAU-Helper](https://github.com/FengHDSL/XJAU-Helper)（MIT）
- 本项目为非官方校园辅助工具，教务同步依赖学校教务页面结构；请遵守学校网络使用规范，自带 AI 接口产生的调用费用由用户自行承担
