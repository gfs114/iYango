package com.gfs.iyango.data.school.ygu

/**
 * 手工 Cookie 罐，行为对齐 HarmonyOS 版。
 *
 * 只保存合法 name=value；Expires/Max-Age 表示过期或值为空时删除。
 * Header 生成顺序为插入顺序，便于服务端会话稳定。
 */
internal class YguCookieJar {

    private val values = LinkedHashMap<String, String>()

    /** 导入 "Cookie: a=b; c=d"；返回空串表示成功，否则是给用户的错误提示。 */
    fun importHeader(header: String): String {
        clear()
        if (header.isEmpty() || header.length > 16_384 || Regex("[\\r\\n\\u0000]").containsMatchIn(header)) {
            return INVALID_MESSAGE
        }
        val input = header.trim().replace(Regex("^Cookie\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        for (part in input.split(';')) {
            val pair = part.trim()
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val name = if (index > 0) pair.substring(0, index).trim() else ""
            val value = if (index > 0) pair.substring(index + 1).trim() else ""
            if (!validName(name) || isAttribute(name) || Regex("[\\u0000-\\u0020\\u007f;, ]").containsMatchIn(value) ||
                value.contains(',')
            ) {
                clear()
                return INVALID_MESSAGE
            }
            values[name] = value
        }
        return if (values.isNotEmpty()) "" else INVALID_MESSAGE
    }

    fun absorb(headers: List<String>) {
        for (header in headers) {
            val entries = header.split(
                Regex("[\\r\\n]+|,\\s*(?=[A-Za-z0-9_!#$%&*+.^|~-]+=)"),
            )
            for (entry in entries) {
                val parts = entry.split(';')
                val index = parts[0].indexOf('=')
                if (index <= 0) continue
                val name = parts[0].substring(0, index).trim()
                val value = parts[0].substring(index + 1).trim()
                if (!validName(name) || Regex("[\\r\\n]").containsMatchIn(value)) continue
                var maxAge: Int? = null
                var expires: Long? = null
                for (partIndex in 1 until parts.size) {
                    val attribute = parts[partIndex].trim()
                    val separator = attribute.indexOf('=')
                    if (separator < 0) continue
                    val key = attribute.substring(0, separator).trim().lowercase()
                    val attributeValue = attribute.substring(separator + 1).trim()
                    if (key == "max-age" && Regex("^-?\\d+$").matches(attributeValue)) {
                        maxAge = attributeValue.toIntOrNull()
                    } else if (key == "expires") {
                        expires = parseExpires(attributeValue)
                    }
                }
                val expired = if (maxAge != null) {
                    maxAge <= 0
                } else {
                    expires != null && expires <= System.currentTimeMillis()
                }
                if (expired || value.isEmpty()) {
                    values.remove(name)
                } else {
                    values[name] = value
                }
            }
        }
    }

    fun has(name: String): Boolean = !values[name].isNullOrEmpty()

    fun header(): String = values.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun clear() {
        values.clear()
    }

    private fun parseExpires(value: String): Long? {
        // 学校常见格式：Wed, 21 Oct 2026 07:28:00 GMT
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yy HH:mm:ss zzz",
        )
        for (pattern in formats) {
            val parsed = runCatching {
                java.time.ZonedDateTime.parse(
                    value,
                    java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US),
                ).toInstant().toEpochMilli()
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun validName(name: String): Boolean =
        Regex("^[A-Za-z0-9_!#$%&*+.^|~-]+$").matches(name)

    private fun isAttribute(name: String): Boolean =
        name.lowercase() in setOf("path", "domain", "expires", "max-age", "samesite", "secure", "httponly")

    private companion object {
        const val INVALID_MESSAGE = "Cookie 格式无效，请复制请求头中的 Cookie 值"
    }
}
