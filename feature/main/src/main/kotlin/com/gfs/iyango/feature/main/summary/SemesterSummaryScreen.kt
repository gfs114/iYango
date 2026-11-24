@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.summary

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.SemesterSummaryReport
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学期数据卡片与液态玻璃海报导出页。
 */
@Composable
fun SemesterSummaryScreen(
    wallpaperPath: String?,
    onBack: () -> Unit,
    viewModel: SemesterSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val brandAccent = LocalBrand.current.accent

    var selectedStyle by remember {
        mutableStateOf(if (!wallpaperPath.isNullOrBlank()) PosterStyle.WALLPAPER else PosterStyle.FROSTED_LIGHT)
    }
    var pendingSaveUri by remember { mutableStateOf<Uri?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null) {
            pendingSaveUri = uri
        }
    }

    val report = state.report
    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        report,
        selectedStyle,
        brandAccent,
        wallpaperPath,
    ) {
        value = if (report == null) {
            null
        } else {
            runCatching {
                viewModel.renderPoster(
                    report = report,
                    style = selectedStyle,
                    accentArgb = brandAccent.toArgb(),
                    wallpaperPath = wallpaperPath,
                )
            }.getOrNull()
        }
    }

    LaunchedEffect(pendingSaveUri) {
        val uri = pendingSaveUri ?: return@LaunchedEffect
        val currentReport = report
        pendingSaveUri = null
        if (currentReport == null) return@LaunchedEffect
        exporting = true
        val ok = runCatching {
            val bitmap = previewBitmap ?: viewModel.renderPoster(
                report = currentReport,
                style = selectedStyle,
                accentArgb = brandAccent.toArgb(),
                wallpaperPath = wallpaperPath,
            )
            writePngBitmap(context, uri, bitmap)
        }.getOrDefault(false)
        if (!ok) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
        exporting = false
        snackbar.showSnackbar(if (ok) "学期数据海报已保存" else "导出失败，未保留损坏文件")
    }

    GlassScaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GlassTopBar(
                title = "学期数据卡片",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                !state.hasTimetable || report == null -> {
                    GlassSurface(
                        shape = RoundedCornerShape(18.dp),
                        material = GlassMaterial.CONTENT,
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("暂无当前课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 760.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HeroHeaderCard(
                            report = report,
                            options = state.options,
                            selectedOptionId = state.selectedOptionId,
                            termLoading = state.termLoading,
                            errorMessage = state.errorMessage,
                            onSelectOption = viewModel::selectSemester,
                        )
                        KpiGridSection(report = report)
                        TopBuildingCard(report = report)
                        if (report.earnedCredits != null || report.averageGpa != null || report.weightedAverage != null) {
                            GradeSummaryCard(report = report)
                        }
                        WeekdayChartCard(report = report)
                        TopCoursesCard(report = report)
                        ExportPosterCard(
                            selectedStyle = selectedStyle,
                            hasWallpaper = !wallpaperPath.isNullOrBlank(),
                            previewBitmap = previewBitmap,
                            exporting = exporting,
                            onSelectStyle = { selectedStyle = it },
                            onExportClick = {
                                val safeName = report.semesterTitle
                                    .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                                    .ifBlank { "学期" }
                                createDocumentLauncher.launch("iYango_${safeName}_学期数据海报.png")
                            },
                            onMissingWallpaperTip = {
                                scope.launch { snackbar.showSnackbar("当前未设置全局壁纸，已按深色极光渲染") }
                            },
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroHeaderCard(
    report: SemesterSummaryReport,
    options: List<SemesterSummaryOption>,
    selectedOptionId: String,
    termLoading: Boolean,
    errorMessage: String?,
    onSelectOption: (String) -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(20.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = report.semesterTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (termLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Text(
                text = "共 ${report.totalWeeks} 个教学周 · 在修 ${report.distinctCourseCount} 门课程",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (options.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        LiquidFilterChip(
                            selected = option.id == selectedOptionId,
                            onClick = { onSelectOption(option.id) },
                            label = { Text(option.title) },
                        )
                    }
                }
            }
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private data class KpiTileData(
    val label: String,
    val value: String,
    val sub: String? = null,
    val highlight: Boolean = false,
)

@Composable
private fun KpiGridSection(report: SemesterSummaryReport) {
    val accent = LocalBrand.current.accent
    val row1 = listOf(
        KpiTileData("总课时（节）", "${report.totalSections}", "共 ${report.totalSessions} 次课", highlight = true),
        KpiTileData(
            "周均课量",
            String.format(Locale.US, "%.1f 节", report.totalSections.toFloat() / report.totalWeeks.coerceAtLeast(1)),
            "共 ${report.totalWeeks} 个教学周",
        ),
    )
    val row2 = listOf(
        KpiTileData("早八天数", "${report.earlyEightDays}"),
        KpiTileData("满课天数", "${report.fullClassDays}", "最高 ${report.maxSessionsInOneDay} 节/日"),
        KpiTileData("最忙星期", report.busiestWeekdayLabel),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(row1, row2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    GlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        material = GlassMaterial.CONTENT,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Text(
                                text = item.value,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (item.highlight) accent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.sub != null) {
                                Text(
                                    item.sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBuildingCard(report: SemesterSummaryReport) {
    val accent = LocalBrand.current.accent
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "最常去教学楼",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                if (report.topBuildingVisits > 0) {
                    Text(
                        text = "累计 ${report.topBuildingVisits} 次",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                text = report.topBuilding,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun GradeSummaryCard(report: SemesterSummaryReport) {
    val accent = LocalBrand.current.accent
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "学分与绩点",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text("${report.gradeCourseCount} 门课程", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricColumn("平均绩点 GPA", report.averageGpa?.let { String.format(Locale.US, "%.2f", it) } ?: "--", highlight = accent, modifier = Modifier.weight(1f))
                MetricColumn("已修学分", report.earnedCredits?.let { String.format(Locale.US, "%.1f", it).removeSuffix(".0") } ?: "--", highlight = null, modifier = Modifier.weight(1f))
                MetricColumn("加权均分", report.weightedAverage?.let { String.format(Locale.US, "%.1f", it) } ?: "--", highlight = null, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    highlight: Color?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = highlight ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekdayChartCard(report: SemesterSummaryReport) {
    val accent = LocalBrand.current.accent
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    val maxCount = report.weekdaySessionCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "周课量分布",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                report.weekdaySessionCounts.take(7).forEachIndexed { idx, count ->
                    val fraction = (count.toFloat() / maxCount).coerceIn(0f, 1f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (count > 0) {
                            Text("$count", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(68.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            if (count > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(fraction.coerceAtLeast(0.16f))
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(accent),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("周${labels[idx]}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopCoursesCard(report: SemesterSummaryReport) {
    val accent = LocalBrand.current.accent
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "课时 Top 3 课程",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            if (report.topCourses.isEmpty()) {
                Text("暂无课程数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                report.topCourses.forEach { stat ->
                    val dotColor = runCatching { Color(android.graphics.Color.parseColor(stat.colorHex)) }.getOrDefault(accent)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Text(
                            text = stat.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${stat.sections} 节 · ${stat.sessions} 次",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportPosterCard(
    selectedStyle: PosterStyle,
    hasWallpaper: Boolean,
    previewBitmap: Bitmap?,
    exporting: Boolean,
    onSelectStyle: (PosterStyle) -> Unit,
    onExportClick: () -> Unit,
    onMissingWallpaperTip: () -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "液态玻璃海报导出",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PosterStyle.entries.forEach { style ->
                    LiquidFilterChip(
                        selected = selectedStyle == style,
                        onClick = {
                            onSelectStyle(style)
                            if (style == PosterStyle.WALLPAPER && !hasWallpaper) {
                                onMissingWallpaperTip()
                            }
                        },
                        label = { Text(style.label) },
                    )
                }
            }
            if (previewBitmap != null) {
                val aspect = previewBitmap.width.toFloat() / previewBitmap.height.coerceAtLeast(1)
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "学期数据海报预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }
            LiquidButton(
                onClick = onExportClick,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (exporting) "正在生成海报…" else "导出液态玻璃海报（PNG）")
            }
        }
    }
}

private suspend fun writePngBitmap(context: Context, uri: Uri, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "w") ?: error("无法打开输出文件")
            stream.use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片编码失败" }
                it.flush()
            }
        }.isSuccess
    }
