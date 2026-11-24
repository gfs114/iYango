package com.gfs.iyango.feature.main.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.QrCodeEncoder
import com.gfs.iyango.core.domain.QrEncodeResult
import com.gfs.iyango.core.domain.ScheduleImportOptions
import com.gfs.iyango.core.domain.ScheduleImportPreview
import com.gfs.iyango.core.domain.ScheduleImportReadResult
import com.gfs.iyango.core.domain.ScheduleImportTargetInfo
import com.gfs.iyango.core.domain.ScheduleShareCodec
import com.gfs.iyango.core.domain.ScheduleShareDecodeResult
import com.gfs.iyango.core.domain.ScheduleTransferPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * JSON 导入导出页 ViewModel（契约 §8、F11）。
 *
 * 导出：当前课表 → SAF 写文件（Screen 持有 launcher，本类提供 JSON/文件名）。
 * 导入：选文件 → 预览 → 选择课程/写入模式（新建/追加/替换）→ 确认（重复文件二次确认）。
 * 提交时把模式、目标、选择集与配置采纳项冻结成一个 [ScheduleImportOptions]。
 */
@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val transfer: ScheduleTransferPort,
    private val timetableRepository: TimetableRepositoryPort,
) : ViewModel() {

    /** 待导出的 JSON 与建议文件名；非 null 时 Screen 触发 SAF 写入。 */
    val pendingExport = MutableStateFlow<PendingExport?>(null)

    /** 分享弹窗状态；非 null 时展示口令与二维码。 */
    val shareState = MutableStateFlow<ShareCodeState?>(null)

    /** 待保存二维码 PNG 文件名；非 null 时 Screen 触发 SAF 写入。 */
    val pendingQrSaveName = MutableStateFlow<String?>(null)

    /** 导入预览；null 表示未在预览流程中。 */
    val preview = MutableStateFlow<ScheduleImportPreview?>(null)

    /** 预览解析失败信息。 */
    val readError = MutableStateFlow<String?>(null)

    /** 写入模式；默认新建。 */
    val mode = MutableStateFlow(ImportWriteMode.CREATE)

    /** 追加/替换的目标课表 id。 */
    val targetId = MutableStateFlow<Long?>(null)

    /** 目标现状（学期/作息/课程数），用于预览冲突提示。 */
    val targetInfo = MutableStateFlow<ScheduleImportTargetInfo?>(null)

    /** 选中的预览行键（以行键而非课程名标识）。 */
    val selectedKeys = MutableStateFlow<Set<String>>(emptySet())

    /** 是否采用文件学期/作息/显示配置。 */
    val applySemester = MutableStateFlow(true)
    val applyTimeSlots = MutableStateFlow(true)
    val applyDisplayConfig = MutableStateFlow(true)

    /** 重复文件二次确认。 */
    val confirmDuplicate = MutableStateFlow(false)

    /** 导入结果文案（成功后展示）。 */
    val doneMessage = MutableStateFlow<String?>(null)

    val error = MutableStateFlow<String?>(null)

    val busy = MutableStateFlow(false)

    /** 本地课表列表（追加/替换模式选择器）。 */
    val localTimetables: StateFlow<List<Timetable>> = timetableRepository.observeAll()
        .catch { error.value = "读取课表失败：${it.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 导出当前课表（准备 JSON，由 Screen 写文件）。 */
    fun requestExport() {
        viewModelScope.launch {
            busy.value = true
            runCatching {
                val current = timetableRepository.observeCurrent().firstOrNull()
                checkNotNull(current) { "尚未创建课表" }
                val result = transfer.export(current.id)
                pendingExport.value = PendingExport(json = result.json, fileName = result.fileName)
            }.onFailure { error.value = it.message ?: "导出失败" }
            busy.value = false
        }
    }

    fun consumePendingExport() {
        pendingExport.value = null
    }

    /** 为当前课表生成分享口令与二维码。 */
    fun requestShareCode() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching {
                val current = timetableRepository.observeCurrent().firstOrNull()
                checkNotNull(current) { "尚未创建课表" }
                val exported = transfer.exportShareCode(current.id)
                val qr = QrCodeEncoder.encode(exported.shareCode)
                shareState.value = ShareCodeState(
                    timetableName = exported.timetableName,
                    shareCode = exported.shareCode,
                    courseCount = exported.courseCount,
                    timeSlotCount = exported.timeSlotCount,
                    qrResult = qr,
                )
            }.onFailure { error.value = it.message ?: "生成分享口令失败" }
            busy.value = false
        }
    }

    fun dismissShareCode() {
        shareState.value = null
    }

    fun requestSaveQrImage() {
        val current = shareState.value ?: return
        if (current.qrResult !is QrEncodeResult.Ready) return
        val safeName = current.timetableName.replace(Regex("[\\\\/:*?\"<>|]"), "-").ifBlank { "课表" }
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        pendingQrSaveName.value = "iYango-$safeName-$today.png"
    }

    fun consumePendingQrSave() {
        pendingQrSaveName.value = null
    }

    /** 解析粘贴文本中的分享口令并进入既有导入预览；失败写入 readError 并返回 false。 */
    fun importFromShareCode(rawText: String): Boolean {
        return when (val decoded = ScheduleShareCodec.decode(rawText)) {
            is ScheduleShareDecodeResult.Success -> {
                readFile(decoded.jsonBytes)
                true
            }
            is ScheduleShareDecodeResult.Failed -> {
                readError.value = decoded.message
                false
            }
        }
    }

    /** 读取所选文件字节并预览。 */
    fun readFile(bytes: ByteArray) {
        viewModelScope.launch {
            busy.value = true
            readError.value = null
            doneMessage.value = null
            when (val result = transfer.read(bytes)) {
                is ScheduleImportReadResult.Ready -> {
                    val loaded = result.preview
                    preview.value = loaded
                    mode.value = ImportWriteMode.CREATE
                    targetId.value = null
                    selectedKeys.value = loaded.items.mapTo(HashSet()) { it.rowKey }
                    applySemester.value = true
                    applyTimeSlots.value = true
                    applyDisplayConfig.value = loaded.hasDisplayConfig
                }
                is ScheduleImportReadResult.Failed -> {
                    readError.value = result.message
                }
            }
            busy.value = false
        }
    }

    fun dismissPreview() {
        preview.value = null
        confirmDuplicate.value = false
        selectedKeys.value = emptySet()
    }

    fun setMode(value: ImportWriteMode) {
        mode.value = value
        if (value == ImportWriteMode.CREATE) {
            targetId.value = null
            targetInfo.value = null
        } else if (targetId.value == null) {
            setTarget(localTimetables.value.firstOrNull { it.source == "LOCAL" }?.id)
        } else {
            refreshTargetInfo()
        }
        if (value == ImportWriteMode.APPEND) {
            // 追加默认保留本机学期/作息，避免静默改动旧课程。
            applySemester.value = false
            applyTimeSlots.value = false
        } else {
            applySemester.value = true
            applyTimeSlots.value = true
        }
    }

    /** 选择追加/替换目标；会话提交时以冻结值传给端口，避免预览后目标漂移。 */
    fun setTarget(timetableId: Long?) {
        targetId.value = timetableId
        refreshTargetInfo()
    }

    private fun refreshTargetInfo() {
        val id = targetId.value
        if (id == null) {
            targetInfo.value = null
            return
        }
        viewModelScope.launch {
            targetInfo.value = runCatching { transfer.inspectTarget(id) }.getOrNull()
        }
    }

    fun toggleRow(rowKey: String) {
        selectedKeys.value = selectedKeys.value.let { current ->
            if (rowKey in current) current - rowKey else current + rowKey
        }
    }

    /** 全选当前可见（搜索过滤后）行；隐藏行的选择保持不变。 */
    fun selectVisible(rowKeys: Collection<String>) {
        selectedKeys.value = selectedKeys.value + rowKeys
    }

    /** 清空全部选择（含被搜索隐藏的行）。 */
    fun clearSelection() {
        selectedKeys.value = emptySet()
    }

    /** 确认导入入口；重复文件先弹二次确认。 */
    fun commit(force: Boolean = false) {
        val current = preview.value ?: return
        val frozenMode = mode.value
        val target = if (frozenMode == ImportWriteMode.CREATE) null else targetId.value
        if (frozenMode != ImportWriteMode.CREATE && target == null) {
            error.value = "请选择目标课表"
            return
        }
        viewModelScope.launch {
            if (current.duplicateExisting && !force) {
                confirmDuplicate.value = true
                return@launch
            }
            confirmDuplicate.value = false
            busy.value = true
            val snapshot = ScheduleImportOptions(
                mode = frozenMode,
                targetTimetableId = target,
                selectedRowKeys = selectedKeys.value,
                applySemester = applySemester.value,
                applyTimeSlots = applyTimeSlots.value,
                applyDisplayConfig = applyDisplayConfig.value,
                force = force,
            )
            runCatching {
                val result = transfer.commit(token = current.token, options = snapshot)
                doneMessage.value = buildString {
                    append(
                        when {
                            result.replaced -> "已替换课表，写入课程 ${result.courseCount} 条"
                            result.appended -> "已追加 ${result.courseCount} 条课程，原有课程保留"
                            else -> "已新建课表并导入 ${result.courseCount} 条课程"
                        }
                    )
                    result.issues.firstOrNull()?.let { append("；$it") }
                }
                preview.value = null
                selectedKeys.value = emptySet()
            }.onFailure { error.value = it.message ?: "导入失败" }
            busy.value = false
        }
    }

    fun confirmDuplicate() = commit(force = true)

    fun dismissDuplicate() {
        confirmDuplicate.value = false
    }

    fun consumeError() {
        error.value = null
    }

    fun consumeDone() {
        doneMessage.value = null
    }

    /** 待写文件载荷。 */
    data class PendingExport(val json: String, val fileName: String)

    /** 口令与二维码分享弹窗状态。 */
    data class ShareCodeState(
        val timetableName: String,
        val shareCode: String,
        val courseCount: Int,
        val timeSlotCount: Int,
        val qrResult: QrEncodeResult,
    )
}
