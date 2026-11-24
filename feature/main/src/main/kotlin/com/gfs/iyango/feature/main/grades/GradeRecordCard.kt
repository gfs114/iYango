package com.gfs.iyango.feature.main.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidCheckbox
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.model.GradeRecord

/**
 * 课程记录卡（方案 §8）：课程名最多两行，卡片仅展示科目、分数、绩点与学分；
 * 选中态 = 复选框 + 强调色细边框 + 内部淡色背景（不使用 GlassSurface.tintColor）。
 *
 * [compactBase] 由页面级可用宽度与字体缩放判定；长分数原文额外强制上下布局。
 */
@Composable
internal fun GradeRecordCard(
    record: GradeRecord,
    selectionMode: Boolean,
    selected: Boolean,
    compactBase: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val brand = LocalBrand.current
    val shape = RoundedCornerShape(16.dp)
    val compact = compactBase || record.scoreText.length > 8
    val gradePoint = gradePointText(record)
    val scoreDescription = buildString {
        append(if (record.scoreText.isBlank()) "成绩 暂无数据" else "成绩 ${record.scoreText}")
        gradePoint?.let { append("，$it") }
    }
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .testTag("grade-record-${record.id}")
            .then(
                if (selectionMode) {
                    Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .then(
                if (selected) Modifier.border(1.dp, brand.accent.copy(alpha = 0.7f), shape) else Modifier,
            ),
    ) {
        Box {
            if (selected) {
                // 淡色选中背景叠加在玻璃之上、内容之下；裁剪跟随卡片圆角。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(brand.accent.copy(alpha = 0.10f)),
                )
            }
            val meta = "${formatCredit(record.credit)} 学分"

            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (selectionMode) LiquidCheckbox(checked = selected, onCheckedChange = null)
                if (compact) {
                    // 上下布局：科目、分数、绩点、学分逐行展示。
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        GradeName(record.courseName)
                        Text(
                            text = record.scoreText.ifBlank { "—" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = brand.accent,
                            modifier = Modifier.semantics { contentDescription = scoreDescription },
                        )
                        gradePoint?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GradeMeta(meta)
                        GradeBadges(record)
                        GradePreviousScore(record)
                    }
                } else {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        GradeName(record.courseName)
                        GradeMeta(meta)
                        GradeBadges(record)
                        GradePreviousScore(record)
                    }
                    Spacer(Modifier.width(10.dp))
                    // 右侧分数区域最多约三分之一宽度：fill=false 的权重只占用实际需要的空间。
                    Column(
                        modifier = Modifier.weight(0.34f, fill = false),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = record.scoreText.ifBlank { "—" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = brand.accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { contentDescription = scoreDescription },
                        )
                        gradePoint?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeName(courseName: String) {
    Text(
        text = courseName.ifBlank { "未命名课程" },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 学分、属性、考试性质合并为可换行的辅助行（不再强制单行）。 */
@Composable
private fun GradeMeta(meta: String) {
    Text(
        text = meta,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 「新」「更新」标识移入辅助信息区，不挤占课程名首行。 */
@Composable
private fun GradeBadges(record: GradeRecord) {
    if (!record.isNew && !record.isModified) return
    val brand = LocalBrand.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (record.isNew) {
            GradeBadge(text = "新", background = brand.brandSoft, foreground = brand.accent)
        }
        if (record.isModified) {
            GradeBadge(
                text = "更新",
                background = MaterialTheme.colorScheme.errorContainer,
                foreground = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun GradePreviousScore(record: GradeRecord) {
    if (!record.isModified || record.previousScoreText.isBlank()) return
    Text(
        text = "原成绩 ${record.previousScoreText} → ${record.scoreText}",
        style = MaterialTheme.typography.labelSmall,
        color = LocalBrand.current.accent,
    )
}

@Composable
private fun GradeBadge(text: String, background: Color, foreground: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = background,
        contentColor = foreground,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}
