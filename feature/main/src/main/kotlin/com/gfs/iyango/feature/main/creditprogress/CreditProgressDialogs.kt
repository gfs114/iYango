package com.gfs.iyango.feature.main.creditprogress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidCheckbox
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidRadioButton
import com.gfs.iyango.core.model.CountedCourseGroup
import com.gfs.iyango.core.model.CreditCategoryPlan
import com.gfs.iyango.core.model.CreditRecordAnchor
import com.gfs.iyango.core.model.CreditSplitAssignment
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.NotCountedCourseGroup
import com.gfs.iyango.core.model.PendingCourseGroup
import com.gfs.iyango.core.model.PendingReason
import com.gfs.iyango.core.model.formatCreditBigDecimal
import com.gfs.iyango.core.model.parseCreditBigDecimal
import java.math.BigDecimal

@Composable
internal fun TotalTargetEditDialog(
    initialTarget: BigDecimal?,
    initialNote: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Boolean,
) {
    var targetText by remember(initialTarget) {
        mutableStateOf(initialTarget?.let(::formatCreditBigDecimal).orEmpty())
    }
    var noteText by remember(initialNote) { mutableStateOf(initialNote) }
    var validationError by remember { mutableStateOf<String?>(null) }

    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置总学分目标") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "总学分目标与备注只影响本机进度展示，不会改动教务原始成绩。留空则显示未设置目标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LiquidOutlinedTextField(
                    value = targetText,
                    onValueChange = {
                        targetText = it
                        validationError = null
                    },
                    label = { Text("总学分目标（例如 160）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = validationError != null,
                    supportingText = validationError?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidOutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("个人备注（可选）") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = targetText.trim()
                    if (trimmed.isNotEmpty() && parseCreditBigDecimal(trimmed) == null) {
                        validationError = "请输入有效的非负数字"
                    } else if (onSave(targetText, noteText)) {
                        onDismiss()
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun CategoryManagerDialog(
    categories: List<CreditCategoryPlan>,
    availableRawAttributes: Set<String>,
    onDismiss: () -> Unit,
    onSaveCategory: (String?, String, String, Set<String>, Int) -> Boolean,
    onDeleteCategory: (String) -> Unit,
    onResetDefault: () -> Unit,
) {
    var editingCategory by remember { mutableStateOf<CreditCategoryPlan?>(null) }
    var addingNew by remember { mutableStateOf(false) }

    if (addingNew || editingCategory != null) {
        val current = editingCategory
        CategoryEditorDialog(
            initialCategory = current,
            nextSortOrder = (categories.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            availableRawAttributes = availableRawAttributes,
            onDismiss = {
                addingNew = false
                editingCategory = null
            },
            onSave = { name, target, attrs, order ->
                if (onSaveCategory(current?.categoryId, name, target, attrs, order)) {
                    addingNew = false
                    editingCategory = null
                }
            },
        )
        return
    }

    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理学分分类与映射") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "分类支持修改名称、配额学分与教务原始属性映射；修改名称不影响已有课程规则绑定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                categories.sortedBy { it.sortOrder }.forEach { category ->
                    GlassSurface(
                        material = GlassMaterial.CONTENT,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    category.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val targetLabel = category.targetCredits?.let {
                                    "目标 ${formatCreditBigDecimal(it)} 学分"
                                } ?: "未设目标"
                                val attrsLabel = if (category.mappedRawAttributes.isEmpty()) {
                                    "无自动属性映射"
                                } else {
                                    category.mappedRawAttributes.sorted().joinToString("、")
                                }
                                Text(
                                    "$targetLabel $attrsLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { editingCategory = category }) {
                                Text("编辑")
                            }
                            if (categories.size > 1) {
                                TextButton(onClick = { onDeleteCategory(category.categoryId) }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiquidOutlinedButton(
                        onClick = { addingNew = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("新增分类")
                    }
                    LiquidOutlinedButton(
                        onClick = onResetDefault,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("恢复默认")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(
    initialCategory: CreditCategoryPlan?,
    nextSortOrder: Int,
    availableRawAttributes: Set<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, Set<String>, Int) -> Unit,
) {
    var displayName by remember(initialCategory) {
        mutableStateOf(initialCategory?.displayName.orEmpty())
    }
    var targetText by remember(initialCategory) {
        mutableStateOf(initialCategory?.targetCredits?.let(::formatCreditBigDecimal).orEmpty())
    }
    var customAttrInput by remember { mutableStateOf("") }
    val selectedAttrs = remember(initialCategory) {
        mutableStateListOf<String>().apply {
            addAll(initialCategory?.mappedRawAttributes.orEmpty().sorted())
        }
    }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val attributeCandidates = remember(availableRawAttributes, selectedAttrs.toList()) {
        (DEFAULT_ATTRIBUTE_SUGGESTIONS + availableRawAttributes + selectedAttrs)
            .filter { it.isNotBlank() }
            .toSortedSet()
    }

    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialCategory == null) "新增学分分类" else "编辑分类") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiquidOutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        errorMsg = null
                    },
                    label = { Text("分类显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidOutlinedTextField(
                    value = targetText,
                    onValueChange = {
                        targetText = it
                        errorMsg = null
                    },
                    label = { Text("分类学分目标（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "映射到该分类的教务原始属性：",
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attributeCandidates.forEach { attr ->
                        val checked = attr in selectedAttrs
                        LiquidFilterChip(
                            selected = checked,
                            onClick = {
                                if (checked) selectedAttrs.remove(attr) else selectedAttrs.add(attr)
                            },
                            label = { Text(attr) },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiquidOutlinedTextField(
                        value = customAttrInput,
                        onValueChange = { customAttrInput = it },
                        label = { Text("补充属性名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val trimmed = customAttrInput.trim()
                            if (trimmed.isNotEmpty() && trimmed !in selectedAttrs) {
                                selectedAttrs.add(trimmed)
                                customAttrInput = ""
                            }
                        },
                    ) {
                        Text("添加")
                    }
                }
                if (errorMsg != null) {
                    Text(
                        errorMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanName = displayName.trim()
                    val cleanTarget = targetText.trim()
                    when {
                        cleanName.isEmpty() -> errorMsg = "请输入分类名称"
                        cleanTarget.isNotEmpty() && parseCreditBigDecimal(cleanTarget) == null ->
                            errorMsg = "分类目标必须是有效非负数字"
                        else -> onSave(
                            cleanName,
                            cleanTarget,
                            selectedAttrs.toSet(),
                            initialCategory?.sortOrder ?: nextSortOrder,
                        )
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PendingResolutionDialog(
    group: PendingCourseGroup,
    categories: List<CreditCategoryPlan>,
    onDismiss: () -> Unit,
    onConfirmMerge: (assignedCategoryId: String?, chosenAnchorKey: String?, manualPassConfirmed: Boolean) -> Unit,
    onConfirmSplit: (assignments: List<CreditSplitAssignment>) -> Unit,
    onExclude: () -> Unit,
    onConfirmPostRestoreOnly: (ruleKey: String) -> Unit,
) {
    val canSplit = group.records.size > 1
    var mode by remember(group) {
        mutableStateOf(
            if (PendingReason.MISSING_COURSE_CODE_DUPLICATE_NAME in group.reasons && canSplit) {
                ResolutionUiMode.MERGE
            } else {
                ResolutionUiMode.MERGE
            },
        )
    }
    var selectedCategoryId by remember(group) { mutableStateOf<String?>(null) }
    var chosenAnchorKey by remember(group) { mutableStateOf<String?>(null) }
    var manualPass by remember(group) {
        mutableStateOf(PendingReason.SCORE_PENDING_CONFIRMATION in group.reasons)
    }

    val splitCategories = remember(group) {
        mutableStateMapOf<Int, String?>()
    }
    val splitExcludes = remember(group) {
        mutableStateMapOf<Int, Boolean>()
    }
    val splitPassOverrides = remember(group) {
        mutableStateMapOf<Int, Boolean>()
    }

    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认课程归属：${group.courseName}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (group.courseCode.isNotBlank()) {
                    Text(
                        "课程号：${group.courseCode}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    group.detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (PendingReason.RULE_DRIFT_OR_POST_RESTORE in group.reasons) {
                    LiquidOutlinedButton(
                        onClick = {
                            onConfirmPostRestoreOnly(group.suggestedRuleKey)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("按原规则直接核认并生效")
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidFilterChip(
                        selected = mode == ResolutionUiMode.MERGE,
                        onClick = { mode = ResolutionUiMode.MERGE },
                        label = { Text(if (canSplit) "合并为同一门课" else "确认归属与通过") },
                    )
                    if (canSplit) {
                        LiquidFilterChip(
                            selected = mode == ResolutionUiMode.SPLIT,
                            onClick = { mode = ResolutionUiMode.SPLIT },
                            label = { Text("拆分为多门课") },
                        )
                    }
                    LiquidFilterChip(
                        selected = mode == ResolutionUiMode.EXCLUDE,
                        onClick = { mode = ResolutionUiMode.EXCLUDE },
                        label = { Text("不计入计划") },
                    )
                }

                HorizontalDivider()

                when (mode) {
                    ResolutionUiMode.MERGE -> {
                        if (group.records.size > 1) {
                            Text(
                                "选择代表记录（用于同号不同学分或指定计入记录）：",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { chosenAnchorKey = null },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LiquidRadioButton(
                                    selected = chosenAnchorKey == null,
                                    onClick = { chosenAnchorKey = null },
                                )
                                Text("自动按最高成绩选用", style = MaterialTheme.typography.bodySmall)
                            }
                            group.records.forEach { record ->
                                val anchor = CreditRecordAnchor.buildAnchorKey(record)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { chosenAnchorKey = anchor },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LiquidRadioButton(
                                        selected = chosenAnchorKey == anchor,
                                        onClick = { chosenAnchorKey = anchor },
                                    )
                                    Text(
                                        recordSummaryLine(record),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        } else {
                            Text(
                                recordSummaryLine(group.records.first()),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Text("指定计入分类：", style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            LiquidFilterChip(
                                selected = selectedCategoryId == null,
                                onClick = { selectedCategoryId = null },
                                label = { Text("按属性自动识别") },
                            )
                            categories.forEach { category ->
                                LiquidFilterChip(
                                    selected = selectedCategoryId == category.categoryId,
                                    onClick = { selectedCategoryId = category.categoryId },
                                    label = { Text(category.displayName) },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { manualPass = !manualPass },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiquidCheckbox(
                                checked = manualPass,
                                onCheckedChange = { manualPass = it },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "核准记为已获得学分（如免修、认定或特殊成绩）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    ResolutionUiMode.SPLIT -> {
                        Text(
                            "为每条来源记录分别选择归属分类（或排除）：",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        group.records.forEachIndexed { index, record ->
                            val excluded = splitExcludes[index] == true
                            val assigned = splitCategories[index]
                            val passOverride = splitPassOverrides[index] == true
                            GlassSurface(
                                material = GlassMaterial.CONTENT,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        recordSummaryLine(record),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        LiquidFilterChip(
                                            selected = !excluded && assigned == null,
                                            onClick = {
                                                splitExcludes[index] = false
                                                splitCategories[index] = null
                                            },
                                            label = { Text("自动") },
                                        )
                                        categories.forEach { category ->
                                            LiquidFilterChip(
                                                selected = !excluded && assigned == category.categoryId,
                                                onClick = {
                                                    splitExcludes[index] = false
                                                    splitCategories[index] = category.categoryId
                                                },
                                                label = { Text(category.displayName) },
                                            )
                                        }
                                        LiquidFilterChip(
                                            selected = excluded,
                                            onClick = { splitExcludes[index] = true },
                                            label = { Text("不计入") },
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            splitPassOverrides[index] = !passOverride
                                        },
                                    ) {
                                        LiquidCheckbox(
                                            checked = passOverride,
                                            onCheckedChange = { splitPassOverrides[index] = it },
                                        )
                                        Text("确认该条通过/免修", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    ResolutionUiMode.EXCLUDE -> {
                        Text(
                            "确认将该组所有记录移入“未通过或不计入”，不计入任何分类与总学分。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (mode) {
                        ResolutionUiMode.MERGE -> onConfirmMerge(selectedCategoryId, chosenAnchorKey, manualPass)
                        ResolutionUiMode.SPLIT -> {
                            val assignments = group.records.mapIndexed { index, record ->
                                CreditSplitAssignment(
                                    anchorKey = CreditRecordAnchor.buildAnchorKey(record),
                                    occurrenceIndex = index,
                                    customGroupKey = "${group.suggestedRuleKey}#split$index",
                                    assignedCategoryId = splitCategories[index],
                                    expectedFingerprint = CreditRecordAnchor.buildContentFingerprint(record),
                                    exclude = splitExcludes[index] == true,
                                    manualPassConfirmed = splitPassOverrides[index] == true,
                                )
                            }
                            onConfirmSplit(assignments)
                        }
                        ResolutionUiMode.EXCLUDE -> onExclude()
                    }
                    onDismiss()
                },
            ) {
                Text("确认应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun CountedCourseDetailDialog(
    course: CountedCourseGroup,
    associatedRuleKey: String?,
    onDismiss: () -> Unit,
    onDeleteRule: (String) -> Unit,
) {
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(course.courseName) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "计入学分：${formatCreditBigDecimal(course.earnedCredits)}  分类：${course.categoryName}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (course.courseCode.isNotBlank()) {
                    Text(
                        "课程号：${course.courseCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(course.detailText, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("教务原始记录（${course.historyRecords.size} 条）", style = MaterialTheme.typography.labelMedium)
                course.historyRecords.forEach { rec ->
                    Text(
                        recordSummaryLine(rec),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (course.isManualConfirmed && associatedRuleKey != null) {
            {
                TextButton(
                    onClick = {
                        onDeleteRule(associatedRuleKey)
                        onDismiss()
                    },
                ) {
                    Text("撤销人工规则")
                }
            }
        } else {
            null
        },
    )
}

@Composable
internal fun NotCountedCourseDetailDialog(
    course: NotCountedCourseGroup,
    associatedRuleKey: String?,
    onDismiss: () -> Unit,
    onDeleteRule: (String) -> Unit,
) {
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(course.courseName) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (course.courseCode.isNotBlank()) {
                    Text(
                        "课程号：${course.courseCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(course.detailText, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                course.records.forEach { rec ->
                    Text(recordSummaryLine(rec), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (course.isManualConfirmed && associatedRuleKey != null) {
            {
                TextButton(
                    onClick = {
                        onDeleteRule(associatedRuleKey)
                        onDismiss()
                    },
                ) {
                    Text("撤销人工规则")
                }
            }
        } else {
            null
        },
    )
}

private enum class ResolutionUiMode {
    MERGE,
    SPLIT,
    EXCLUDE,
}

private fun recordSummaryLine(record: GradeRecord): String {
    val creditLabel = when {
        record.rawCreditText.isNotBlank() -> record.rawCreditText
        else -> record.credit.toString()
    }
    val attr = listOf(record.rawCourseAttribute, record.rawCourseType, record.rawGeneralElectiveType)
        .filter { it.isNotBlank() }
        .joinToString("/")
        .ifBlank { record.attribute.ifBlank { "未知属性" } }
    val sem = record.semester.trim().takeIf { it.isNotEmpty() && it != "*" }
    return listOfNotNull(
        sem,
        "成绩 ${record.scoreText.ifBlank { "空" }}",
        "学分 $creditLabel",
        attr,
        record.examType.ifBlank { "常规" },
    ).joinToString("  ")
}

private val DEFAULT_ATTRIBUTE_SUGGESTIONS = setOf(
    "必修", "选修", "限选", "任选", "专业选修", "专选", "公选", "通识选修", "公共选修", "实践", "实习", "毕业设计", "毕业论文", "实验",
)
