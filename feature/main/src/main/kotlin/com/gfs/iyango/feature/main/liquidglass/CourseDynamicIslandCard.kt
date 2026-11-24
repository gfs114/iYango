package com.gfs.iyango.feature.main.liquidglass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 课程灵动岛展开态卡片（高保真玻璃拟态）。
 *
 * 四层纵向结构：
 * 1. 顶部微暗色条带：课程名（单行省略）；
 * 2. 核心焦点：教室号（左对齐、加大加粗）；
 * 3. 双栏时间行：左时间段 + 右「距结束」实时倒计时（等宽数字防跳秒）；
 * 4. 底部圆角胶囊进度条：按 (当前时间 - 开始) / (结束 - 开始) 平滑推进。
 *
 * 时间由 [startTimeMs]/[endTimeMs] 驱动：组合在屏时每秒对齐秒边界刷新一次，
 * 离开组合即取消，不引入任何后台任务（对应 iOS `Text(timerInterval:)` 的
 * 「系统驱动、零后台耗电」语义在 Compose 下的等价实现）。
 *
 * @param courseName 课程名称（超长尾部省略）。
 * @param classroom 教室编号（如 "D402"）。
 * @param timeSlot 上课时间区间文本（如 "08:00–09:40"，由调用方格式化）。
 * @param startTimeMs 课程开始时刻（epoch 毫秒）。
 * @param endTimeMs 课程结束时刻（epoch 毫秒）。
 * @param modifier 外部布局修饰符。
 */
@Composable
fun CourseDynamicIslandCard(
    courseName: String,
    classroom: String,
    timeSlot: String,
    startTimeMs: Long,
    endTimeMs: Long,
    modifier: Modifier = Modifier,
) {
    // 每秒对齐秒边界刷新一次；组合离开自动取消，无后台耗电。
    var nowMs by remember(startTimeMs, endTimeMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startTimeMs, endTimeMs) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1000L - (nowMs % 1000L))
        }
    }
    val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(1L)
    val remainingMs = (endTimeMs - nowMs).coerceAtLeast(0L)
    val progress = ((nowMs - startTimeMs).toFloat() / durationMs).coerceIn(0f, 1f)
    // 进度推进带动画过渡，避免每秒一次的跳变。
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 450, easing = LinearEasing),
        label = "courseIslandProgress",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(ContainerBackground)
            .border(1.dp, ContainerGlow, RoundedCornerShape(28.dp))
            .heightIn(min = 145.dp, max = 160.dp),
    ) {
        // 第一层：顶栏课程标题条带（独立微暗色，顶部圆角由外层 clip 裁出）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(TitleStripBackground)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                text = courseName,
                color = TitleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 第二至四层：主区域（四周 16.dp 安全边距）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        ) {
            // 第二层：教室号——第一眼视觉焦点。
            Text(
                text = classroom,
                color = ClassroomColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            // 第三层：左时间段 + 右实时倒计时。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeSlot,
                    color = TimeSlotColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    style = MonospacedDigits,
                )
                Spacer(Modifier.weight(1f))
                Text(text = "距结束 ", color = CountdownPrefixColor, fontSize = 14.sp)
                Text(
                    text = formatRemaining(remainingMs),
                    color = CountdownColor,
                    fontSize = 15.sp,
                    style = MonospacedDigits,
                )
            }
            Spacer(Modifier.fillMaxWidth().weight(1f))
            // 第四层：底部圆角胶囊进度条。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(ProgressTrackColor),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(ProgressFillColor),
                )
            }
        }
    }
}

/** 剩余毫秒 → "H:MM:SS"（超一小时）或 "MM:SS"，秒数不回退为负。 */
private fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = remainingMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

// 视觉常量：容器与描边。
private val ContainerBackground = Color(0xFF1A1D26).copy(alpha = 0.95f)
private val ContainerGlow = Color.White.copy(alpha = 0.12f)
private val TitleStripBackground = Color.Black.copy(alpha = 0.22f)

// 视觉常量：文本层级。
private val TitleColor = Color(0xFFE2E4E9)
private val ClassroomColor = Color(0xFFFFFFFF)
private val TimeSlotColor = Color(0xFFE5E7EB)
private val CountdownPrefixColor = Color(0xFF8E929F)
private val CountdownColor = Color(0xFFF3F4F6)

// 视觉常量：进度条。
private val ProgressTrackColor = Color.White.copy(alpha = 0.16f)
private val ProgressFillColor = Color(0xFFFFFFFF)

/** 等宽数字（tnum）：时间段与倒计时启用，防止跳秒抖动。 */
private val MonospacedDigits = TextStyle(fontFeatureSettings = "tnum")

@Preview(name = "课中 距结束 1:30:57", showBackground = true, backgroundColor = 0xFF101216, widthDp = 360)
@Composable
private fun CourseDynamicIslandCardPreview() {
    val now = System.currentTimeMillis()
    CourseDynamicIslandCard(
        courseName = "英语应用能力专项培养1 (读写)",
        classroom = "D402",
        timeSlot = "08:00–09:40",
        startTimeMs = now - 40 * 60_000L,
        endTimeMs = now + 90 * 60_000L + 57_000L,
    )
}

@Preview(name = "临近下课 超长教室号", showBackground = true, backgroundColor = 0xFF101216, widthDp = 360)
@Composable
private fun CourseDynamicIslandCardNearEndPreview() {
    val now = System.currentTimeMillis()
    CourseDynamicIslandCard(
        courseName = "程序设计实践（含非常非常非常长的课程名称用例）",
        classroom = "T101",
        timeSlot = "19:00–21:30",
        startTimeMs = now - 2 * 60 * 60_000L + 3 * 60_000L,
        endTimeMs = now + 45_000L,
    )
}
