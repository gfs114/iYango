package com.gfs.iyango.feature.main.coursedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.feature.main.schedule.ScheduleDisplayProjection
import java.util.Locale

/** 单课配色三组 3×20（共 60 色）调色板，对齐参考设计 XJAU-Helper。 */
enum class CoursePaletteSet(val label: String, val hexes: List<String>) {
    VIBRANT(
        label = "鲜艳",
        hexes = listOf(
            "#E53935", "#D81B60", "#8E24AA", "#5E35B1", "#3949AB",
            "#1E88E5", "#039BE5", "#00ACC1", "#00897B", "#43A047",
            "#7CB342", "#C0CA33", "#FDD835", "#FFB300", "#FB8C00",
            "#F4511E", "#6D4C41", "#757575", "#546E7A", "#212121",
        ),
    ),
    SOFT(
        label = "柔和",
        hexes = listOf(
            "#FFCDD2", "#F8BBD0", "#E1BEE7", "#D1C4E9", "#C5CAE9",
            "#BBDEFB", "#B3E5FC", "#B2EBF2", "#B2DFDB", "#C8E6C9",
            "#DCEDC8", "#F0F4C3", "#FFF9C4", "#FFECB3", "#FFE0B2",
            "#FFCCBC", "#D7CCC8", "#F5F5F5", "#CFD8DC", "#ECEFF1",
        ),
    ),
    DEEP(
        label = "深邃",
        hexes = listOf(
            "#B71C1C", "#880E4F", "#4A148C", "#311B92", "#1A237E",
            "#0D47A1", "#01579B", "#006064", "#004D40", "#1B5E20",
            "#33691E", "#827717", "#F57F17", "#FF6F00", "#E65100",
            "#BF360C", "#3E2723", "#424242", "#263238", "#000000",
        ),
    ),
}

internal fun normalizePaletteHex(raw: String): String {
    val trimmed = raw.trim().uppercase(Locale.US)
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
}

internal fun parsePaletteColorOrNull(raw: String): Color? {
    val clean = raw.trim().removePrefix("#")
    val value = clean.toLongOrNull(16) ?: return null
    return when (clean.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> null
    }
}

/** 检查同课表内其他启用课程是否已使用 [candidateHex]，命中返回该课程名，未命中返回 null。 */
fun findCourseColorCollision(
    targetCourse: Course,
    candidateHex: String,
    allCourses: List<Course>,
    courseColorOverrides: Map<String, String>,
): String? {
    val normalizedCandidate = normalizePaletteHex(candidateHex)
    if (normalizedCandidate.isEmpty()) return null
    val targetKey = ScheduleDisplayProjection.courseColorKey(targetCourse)
    for (other in allCourses) {
        if (!other.enabled) continue
        val otherKey = ScheduleDisplayProjection.courseColorKey(other)
        if (otherKey == targetKey) continue
        val effectiveHex = courseColorOverrides[otherKey]?.takeIf { it.isNotBlank() } ?: other.color
        if (normalizePaletteHex(effectiveHex) == normalizedCandidate) {
            return other.name
        }
    }
    return null
}

/** 详情内嵌 3×20 选色板，支持组切换、撞色提示与恢复默认。 */
@Composable
fun CourseColorPalettePicker(
    course: Course,
    currentHex: String?,
    allCourses: List<Course>,
    courseColorOverrides: Map<String, String>,
    onSelectHex: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedCurrent = remember(currentHex) { normalizePaletteHex(currentHex.orEmpty()) }
    var selectedSet by remember(course.id) {
        val matched = CoursePaletteSet.entries.firstOrNull { set ->
            set.hexes.any { normalizePaletteHex(it) == normalizedCurrent }
        } ?: CoursePaletteSet.VIBRANT
        mutableStateOf(matched)
    }
    var collisionCourse by remember(course.id) { mutableStateOf<String?>(null) }
    val courseKey = remember(course) { ScheduleDisplayProjection.courseColorKey(course) }
    val hasOverride = !courseColorOverrides[courseKey].isNullOrBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CoursePaletteSet.entries.forEach { set ->
                    LiquidFilterChip(
                        selected = selectedSet == set,
                        onClick = { selectedSet = set },
                        label = { Text(set.label, fontSize = 12.sp) },
                    )
                }
            }
            if (hasOverride) {
                TextButton(
                    onClick = {
                        collisionCourse = null
                        onSelectHex(null)
                    },
                ) {
                    Text("恢复默认", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 20 色分 2 行 × 10 列展示，便于单手点选
        val rows = remember(selectedSet) { selectedSet.hexes.chunked(10) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { rowHexes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    rowHexes.forEach { hex ->
                        val color = parsePaletteColorOrNull(hex) ?: MaterialTheme.colorScheme.primary
                        val isSelected = normalizedCurrent == normalizePaletteHex(hex)
                        val checkTint = if (color.luminance() > 0.55f) Color.Black else Color.White
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape,
                                        )
                                    } else {
                                        Modifier.border(
                                            width = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape,
                                        )
                                    },
                                )
                                .clickable {
                                    collisionCourse = findCourseColorCollision(
                                        targetCourse = course,
                                        candidateHex = hex,
                                        allCourses = allCourses,
                                        courseColorOverrides = courseColorOverrides,
                                    )
                                    onSelectHex(hex)
                                }
                                .testTag("palette_swatch_$hex"),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "已选颜色",
                                    tint = checkTint,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        collisionCourse?.let { name ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "《${name}》已使用此颜色",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("palette_collision_warning"),
            )
        }
    }
}
