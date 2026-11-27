package com.gfs.iyango.feature.main.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.feature.main.ai.AiRobotIcon

/** Existing feature destinations: grouping changes their entry points, not their routes. */
enum class MineDestination(val route: String, val title: String, val summary: String) {
    COURSES("courses", "课程管理", "查看课程详情，编辑上课安排"),
    TIMETABLE("timetable", "我的课表", "创建、切换与管理课表"),
    TIMETABLE_COMPARE("timetablecompare", "合表找空", "对比多张课表 查找共同空课"),
    SEMESTER("semester", "学期管理", "学期日期、教学周与作息时间"),
    EXAMS("exams", "考试安排", "查看和管理考试信息"),
    SEMESTER_SUMMARY("semestersummary", "学期数据卡片", "统计总课时、早八、教学楼与 GPA 并导出液态玻璃海报"),
    SCHOOL("school", "教务账号", "登录账号与同步教务课表"),
    CLASSROOM("classroom", "教室查询", "查询空闲教室与使用情况"),
    HISTORY("synchistory", "同步记录", "查看教务同步结果和课程变化"),
    HOME("homesettings", "首页设置", "模块显示、顺序与快捷入口"),
    DISPLAY("display?perTimetable=false", "课表显示", "全局课表样式、字号与显示内容"),
    WALLPAPER("wallpaper", "壁纸与背景", "设置背景图片与显示效果"),
    FONT("font", "字体与可读性", "自定义 TTF/OTF 字体、字号缩放与灰字增强"),
    LIQUID_GLASS("liquidglass", "液态玻璃", "分类调节导航栏、卡片、控件、弹窗与底部栏的液态玻璃参数"),
    IMPORT_EXPORT("importexport", "导入导出", "导入课表 AI 拍图导入 导出文件或图片"),
    AI_ASSISTANT("aiassistant", "AI 助理", "查课表 算均绩 找空教室 调课"),
    BACKUP("settingsbackup", "设置备份与重置", "备份、恢复或重置应用设置"),
    REMINDER("reminder", "提醒与日历", "课程提醒、灵动岛与系统日历"),
    ABOUT("about", "关于与反馈", "应用信息与问题反馈"),
}

enum class MineCategory(
    val route: String,
    val title: String,
    val summary: String,
    val destinations: List<MineDestination>,
) {
    SCHEDULE("mine/schedule", "课程与课表", "课程 课表 学期 考试",
        listOf(MineDestination.COURSES, MineDestination.TIMETABLE, MineDestination.TIMETABLE_COMPARE, MineDestination.SEMESTER, MineDestination.EXAMS, MineDestination.SEMESTER_SUMMARY)),
    SCHOOL("mine/school", "教务服务", "账号 教室查询 同步记录",
        listOf(MineDestination.SCHOOL, MineDestination.CLASSROOM, MineDestination.HISTORY)),
    APPEARANCE("mine/appearance", "外观与布局", "主题 壁纸 字体 启动设置",
        listOf(MineDestination.HOME, MineDestination.DISPLAY, MineDestination.WALLPAPER, MineDestination.FONT, MineDestination.LIQUID_GLASS)),
    DATA("mine/data", "数据与备份", "课表导入导出 设置备份与恢复",
        listOf(MineDestination.IMPORT_EXPORT, MineDestination.BACKUP)),
}

internal val MineCategory.icon: ImageVector get() = when (this) {
    MineCategory.SCHEDULE -> Icons.Filled.DateRange
    MineCategory.SCHOOL -> Icons.Filled.Person
    MineCategory.APPEARANCE -> Icons.Filled.Star
    MineCategory.DATA -> Icons.Filled.Refresh
}

internal val MineDestination.icon: ImageVector get() = when (this) {
    MineDestination.SCHOOL -> Icons.Filled.Person
    MineDestination.CLASSROOM -> Icons.Filled.Search
    MineDestination.HISTORY, MineDestination.IMPORT_EXPORT -> Icons.Filled.Refresh
    MineDestination.HOME, MineDestination.DISPLAY, MineDestination.FONT, MineDestination.LIQUID_GLASS, MineDestination.BACKUP -> Icons.Filled.Settings
    MineDestination.AI_ASSISTANT -> AiRobotIcon
    MineDestination.WALLPAPER, MineDestination.SEMESTER_SUMMARY -> Icons.Filled.Star
    MineDestination.ABOUT -> Icons.Filled.Info
    else -> Icons.Filled.DateRange
}

@Composable
internal fun MineShortcut(destination: MineDestination, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalBrand.current.accent
    GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(18.dp),
        modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(destination.icon, null, Modifier.size(20.dp), tint = accent)
            }
            Text(destination.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 扁平分组容器（单卡片包裹多行设置项，内部用细分割线隔开，参考 XJAU-Helper 我的页视觉风格）。 */
@Composable
internal fun MineGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** 扁平分组菜单行，带强调色浅底图标胶囊与可选左缩进分割线。 */
@Composable
internal fun MineGroupedRow(
    icon: ImageVector,
    title: String,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalBrand.current.accent
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
fun MineCategoryScreen(category: MineCategory, onBack: () -> Unit, onNavigate: (MineDestination) -> Unit) {
    MineSubpage(category.title, onBack) {
        MineGroupedCard {
            category.destinations.forEachIndexed { index, destination ->
                MineGroupedRow(
                    icon = destination.icon,
                    title = destination.title,
                    showDivider = index < category.destinations.lastIndex,
                    onClick = { onNavigate(destination) },
                )
            }
        }
    }
}

/** Full-screen secondary page; padding scrolls under the same fixed glass header as other screens. */
@Composable
internal fun MineSubpage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GlassScaffold(topBar = {
        GlassTopBar(title = title, navigationIcon = {
            GlassTopBarIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                content()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
