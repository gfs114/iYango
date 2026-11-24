package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.ClassroomOption
import com.gfs.iyango.core.model.EleventhSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class YguClassroomMetadataStatus { OK, LOGIN, UNSUPPORTED, INVALID }

internal data class YguClassroomMetadataResult(
    val status: YguClassroomMetadataStatus,
    val message: String = "",
    val options: YguClassroomOptions = YguClassroomOptions(),
)

/** 页面解析出的全部条件选项（可变容器，交给上层转成不可变模型）。 */
internal data class YguClassroomOptions(
    val semesters: MutableList<ClassroomOption> = mutableListOf(),
    val campuses: MutableList<ClassroomOption> = mutableListOf(),
    val buildings: MutableList<ClassroomOption> = mutableListOf(),
    val roomTypes: MutableList<ClassroomOption> = mutableListOf(),
    val timeModes: MutableList<ClassroomOption> = mutableListOf(),
    val weeks: MutableList<ClassroomOption> = mutableListOf(),
    val days: MutableList<ClassroomOption> = mutableListOf(),
    val periods: MutableList<ClassroomOption> = mutableListOf(),
)

/**
 * jsjy_query 页面的离线解析，行为对齐 HarmonyOS 的 `YguClassroomMetadata`。
 *
 * 只读页面里真实出现的 select/checkbox；值逐字保留，仅把已确认的
 * 十一/十二合并组校正为「第十一节」。脚本、隐藏/禁用控件一律忽略。
 */
internal object YguClassroomMetadata {

    private const val MAX_HTML_LENGTH = 1_048_576
    private const val MAX_BUILDINGS_LENGTH = 262_144
    private const val MAX_BUILDINGS = 1_000
    private const val MESSAGE_UNSUPPORTED = "学校教务页面暂不支持，请稍后重试"

    private val SCRIPT = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
    private val STYLE = Regex("<style\\b[^>]*>[\\s\\S]*?</style\\s*>", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val TOKEN = Regex("<!--[\\s\\S]*?-->|<[^>]*>|[^<]+")
    private val WHITESPACE = Regex("[\\t\\r\\n ]+")

    fun parse(html: String): YguClassroomMetadataResult {
        if (html.isEmpty() || html.length > MAX_HTML_LENGTH) {
            return unsupported()
        }
        val visible = COMMENT.replace(STYLE.replace(SCRIPT.replace(html, ""), ""), "")
        if (TOKEN.findAll(visible).take(50_000).count() >= 50_000) {
            return unsupported()
        }
        if (YguSession.isLoginPage(visible)) {
            return YguClassroomMetadataResult(
                status = YguClassroomMetadataStatus.LOGIN,
                message = "登录状态已失效，请返回教务登录后重试",
            )
        }
        val root = YguHtml.parse(visible)
        val options = YguClassroomOptions()
        if (readControls(root, options, "") == 0) {
            return unsupported()
        }
        return YguClassroomMetadataResult(
            status = YguClassroomMetadataStatus.OK,
            options = options,
        )
    }

    /** 官方楼宇字典：只接收 `dm`/`dmmc` 字符串数组；空数组是成功但无楼。 */
    fun parseBuildings(body: String): List<ClassroomOption>? {
        if (body.isEmpty() || body.length > MAX_BUILDINGS_LENGTH) return null
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return null
        if (root.size > MAX_BUILDINGS) return null
        val result = mutableListOf<ClassroomOption>()
        for (element in root) {
            val item = element as? JsonObject ?: return null
            val code = item["dm"].stringOrNull() ?: return null
            val name = item["dmmc"].stringOrNull() ?: return null
            if (code.trim().isEmpty() || name.trim().isEmpty()) return null
            val existing = result.firstOrNull { it.value == code }
            if (existing != null && existing.label != name) return null
            if (existing == null) result += ClassroomOption(value = code, label = name)
        }
        return result
    }

    /** select 的周次值转成 1..60；不提供任何默认周次。 */
    fun parsedWeek(value: String): Int {
        if (!Regex("^\\d+$").matches(value)) return 0
        val parsed = value.toIntOrNull() ?: return 0
        return if (parsed in 1..60) parsed else 0
    }

    private fun readControls(node: YguHtmlNode, options: YguClassroomOptions, itemLabel: String): Int {
        if (unavailable(node)) return 0
        if (node.tag == "select") return readSelect(node, options)
        if (node.tag == "input") return readCheckbox(node, options, itemLabel)
        val label = if (node.tag == "li") itemLabelOf(node) else itemLabel
        var count = 0
        for (child in node.children) count += readControls(child, options, label)
        return count
    }

    private fun unavailable(node: YguHtmlNode): Boolean =
        node.hidden() || node.hasAttribute("hidden") || node.hasAttribute("disabled") ||
            node.tag == "script" || node.tag == "style" || node.tag == "template" ||
            (node.tag == "input" && node.attribute("type").equals("hidden", ignoreCase = true))

    private fun itemLabelOf(item: YguHtmlNode): String {
        for (child in item.children) {
            if (child.tag == "i" && !unavailable(child)) return labelOf(child)
        }
        return ""
    }

    private fun readCheckbox(input: YguHtmlNode, options: YguClassroomOptions, label: String): Int {
        if (!input.attribute("type").equals("checkbox", ignoreCase = true) ||
            !input.hasAttribute("value") || label.isEmpty()
        ) {
            return 0
        }
        val value = input.attribute("value").trim()
        if (value.isEmpty()) return 0
        val entry = ClassroomOption(
            value = value,
            label = label,
            selected = input.hasAttribute("checked"),
        )
        return when (input.attribute("name")) {
            "zc" -> {
                appendAll(options.weeks, listOf(entry))
                1
            }
            "xq" -> {
                appendAll(options.days, listOf(entry))
                1
            }
            "jc" -> {
                appendAll(options.periods, listOf(correctPeriodLabel(entry)))
                1
            }
            else -> 0
        }
    }

    private fun readSelect(select: YguHtmlNode, options: YguClassroomOptions): Int {
        val entries = mutableListOf<ClassroomOption>()
        readOptions(select, entries)
        val target = when (select.attribute("name")) {
            "xnxqh", "xnxq" -> options.semesters
            "xqbh" -> options.campuses
            "jxlbh" -> options.buildings
            "jslx" -> options.roomTypes
            "kbjcmsid" -> options.timeModes
            "selectZc" -> options.weeks
            "selectXq" -> options.days
            "selectJc" -> options.periods
            else -> return 0
        }
        val values = if (target === options.periods) entries.map { correctPeriodLabel(it) } else entries
        appendAll(target, values)
        return 1
    }

    private fun readOptions(node: YguHtmlNode, entries: MutableList<ClassroomOption>) {
        if (unavailable(node)) return
        if (node.tag == "option") {
            val label = labelOf(node)
            if (node.hasAttribute("value") && label.isNotEmpty()) {
                entries += ClassroomOption(
                    value = node.attribute("value").trim(),
                    label = label,
                    selected = node.hasAttribute("selected"),
                )
            }
            return
        }
        for (child in node.children) readOptions(child, entries)
    }

    /** 同 (value,label) 去重并合并预选标记；同值不同名仍保留两条。 */
    private fun appendAll(target: MutableList<ClassroomOption>, entries: List<ClassroomOption>) {
        for (entry in entries) {
            val existing = target.firstOrNull { it.value == entry.value && it.label == entry.label }
            if (existing == null) {
                target += entry
            } else if (entry.selected && !existing.selected) {
                target[target.indexOf(existing)] = existing.copy(selected = true)
            }
        }
    }

    private fun correctPeriodLabel(entry: ClassroomOption): ClassroomOption =
        if (EleventhSection.isExact(entry.value) || EleventhSection.matchesLabel(entry.label)) {
            entry.copy(label = EleventhSection.LABEL)
        } else {
            entry
        }

    private fun labelOf(node: YguHtmlNode): String =
        WHITESPACE.replace(node.text().trim(), " ")

    private fun unsupported(): YguClassroomMetadataResult =
        YguClassroomMetadataResult(
            status = YguClassroomMetadataStatus.UNSUPPORTED,
            message = MESSAGE_UNSUPPORTED,
        )

    private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
