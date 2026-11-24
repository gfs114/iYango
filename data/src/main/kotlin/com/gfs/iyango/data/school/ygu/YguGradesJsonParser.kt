package com.gfs.iyango.data.school.ygu

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 新版成绩页 JSON 分页解析，字段映射对齐 HarmonyOS 的 `YguGradesJsonParser`。
 *
 * 严格要求 code=0、count 与 data 长度自洽、每页最多 20 行；出现评教/权限标记时
 * 报限制状态，而不是把空列表当成“没有成绩”。任何字段类型异常都判为不可读。
 */
internal object YguGradesJsonParser {

    private const val MAX_BODY_LENGTH = 1_048_576
    private const val MAX_TOTAL = 2_000
    private const val MAX_PAGE_ROWS = 20
    private const val INVALID_MESSAGE = "成绩页面暂不可读取，请稍后重试"

    fun parse(body: String): YguGradesJsonPage {
        if (body.isEmpty() || body.length > MAX_BODY_LENGTH) return invalid()
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) }
            .getOrNull() as? JsonObject ?: return invalid()
        val msg = root["msg"]
        if (msg != null && !msg.isStringScalar()) return invalid()
        val code = root["code"]?.takeIf { it.isNumberScalar() }?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: return invalid()
        if (!code.isFinite()) return invalid()
        if (code != 0.0) {
            val message = msg?.scalar().orEmpty()
            if (message.contains("登录") || message.contains("login", ignoreCase = true)) {
                return result(YguGradesStatus.LOGIN, "登录状态已失效，请返回教务登录后重试")
            }
            if (message.contains("评教") || message.contains("权限") ||
                message.contains("无权") || message.contains("未开放")
            ) {
                return result(YguGradesStatus.RESTRICTED, "学校暂未开放成绩查询，请完成相关事项后重试")
            }
            return invalid()
        }
        if (!validEvaluationIds(root["ypjx0404ids"]) || !validEvaluationIds(root["pjjx0404ids"])) {
            return invalid()
        }
        if (hasEvaluationIds(root["ypjx0404ids"]) || hasEvaluationIds(root["pjjx0404ids"])) {
            return result(YguGradesStatus.RESTRICTED, "学校暂未开放成绩查询，请完成相关事项后重试")
        }
        if (!root["isXsckpscj"].isNonnegativeControl()) return invalid()
        val count = root["count"]?.takeIf { it.isNumberScalar() }
            ?.jsonPrimitive?.content?.toIntOrNull() ?: return invalid()
        val data = root["data"] as? JsonArray ?: return invalid()
        if (count < 0 || count > MAX_TOTAL || data.size > MAX_PAGE_ROWS || data.size > count) return invalid()
        if (count == 0 && data.isEmpty()) {
            return result(YguGradesStatus.EMPTY, "本学期暂无成绩记录")
        }
        if (data.isEmpty()) return invalid()

        val records = mutableListOf<YguGradeRow>()
        for (element in data) {
            val row = element as? JsonObject ?: return invalid()
            records += readRecord(row, root["isXsckpscj"]) ?: return invalid()
        }
        return result(YguGradesStatus.OK, "").copy(
            records = records,
            total = count,
            fingerprint = data.toString(),
        )
    }

    private fun readRecord(row: JsonObject, recognitionFlag: JsonElement?): YguGradeRow? {
        if (!row["kz"].isControlValue() || !row["zcj"].isScalarValue() ||
            !recognitionFlag.isNonnegativeControl()
        ) {
            return null
        }
        val scalarFields = listOf(
            "rownum_", "xnxqid", "kch", "kc_mc", "ksdw", "zcjstr", "cjbs", "xf", "zxs", "jd",
            "bcxq", "ksfs", "ksxz", "kcsx", "kcxzmc", "txklb",
        )
        for (field in scalarFields) {
            if (!row[field].isScalarValue()) return null
        }
        val record = YguGradeRow(
            rowNumber = row["rownum_"].scalar(),
            term = row["xnxqid"].scalar(),
            courseCode = row["kch"].scalar(),
            courseName = row["kc_mc"].scalar(),
            offeringUnit = row["ksdw"].scalar(),
            score = row["zcjstr"].scalar(),
            scoreFlag = row["cjbs"].scalar(),
            credit = row["xf"].scalar(),
            totalHours = row["zxs"].scalar(),
            gradePoint = row["jd"].scalar(),
            retakeTerm = row["bcxq"].scalar(),
            assessmentMethod = row["ksfs"].scalar(),
            examType = row["ksxz"].scalar(),
            courseAttribute = row["kcsx"].scalar(),
            courseType = row["kcxzmc"].scalar(),
            generalElectiveType = row["txklb"].scalar(),
        )
        if (record.courseName.trim().isEmpty()) return null
        val rowFlag = controlNumber(row["kz"], 0.0)
        if (rowFlag != 0.0 && rowFlag != 1.0) return null
        if (rowFlag == 1.0) {
            return record.copy(score = "请评教", gradePoint = "请评教")
        }
        val recognition = controlNumber(recognitionFlag, 0.0)
        if (recognition < 0) return null
        return if (isUnrecognized(row["zcj"]) && recognition == 0.0) {
            record.copy(score = "未认定")
        } else {
            record
        }
    }

    private fun JsonElement?.isScalarValue(): Boolean = when (this) {
        null, JsonNull -> true
        is JsonPrimitive -> isString || content.toDoubleOrNull()?.isFinite() == true
        else -> false
    }

    private fun JsonElement?.isControlValue(): Boolean = when (this) {
        null, JsonNull -> true
        is JsonPrimitive -> if (isString) {
            content.trim().isNotEmpty() && content.toDoubleOrNull()?.isFinite() == true
        } else {
            content.toDoubleOrNull()?.isFinite() == true
        }
        else -> false
    }

    private fun JsonElement?.isNonnegativeControl(): Boolean =
        isControlValue() && controlNumber(this, 0.0) >= 0.0

    private fun JsonElement?.isNumberScalar(): Boolean =
        this is JsonPrimitive && !isString && content.toDoubleOrNull()?.isFinite() == true

    private fun JsonElement?.isStringScalar(): Boolean =
        this is JsonPrimitive && isString

    private fun controlNumber(value: JsonElement?, fallback: Double): Double {
        if (value == null || value is JsonNull) return fallback
        val primitive = value as? JsonPrimitive ?: return -1.0
        return primitive.content.toDoubleOrNull() ?: -1.0
    }

    private fun isUnrecognized(value: JsonElement?): Boolean =
        controlNumber(value, 0.0) == 1.0

    private fun JsonElement?.scalar(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> content
        else -> ""
    }

    private fun validEvaluationIds(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> true
        is JsonPrimitive -> value.isString
        is JsonArray -> true
        else -> false
    }

    private fun hasEvaluationIds(value: JsonElement?): Boolean = when (value) {
        is JsonPrimitive -> value.isString && value.content.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        else -> false
    }

    private fun result(status: YguGradesStatus, message: String) =
        YguGradesJsonPage(status = status, message = message)

    private fun invalid() = result(YguGradesStatus.INVALID, INVALID_MESSAGE)
}
