package org.ergoutree.compress.application

/** 按值隐藏本地路径、素材名称和凭证，输出只包含不可逆关联占位符。 */
class DiagnosticRedactor(private val privateValues: List<String> = emptyList()) {
    private val paths = linkedMapOf<String, String>()
    private fun path(value: String): String = paths.getOrPut(value.lowercase()) { "<PATH_${paths.size + 1}>" }
    fun redact(input: String): String {
        if (Regex("""(?i)(authorization|bearer\s|password|secret|api[-_]?key|access[-_]?token|cookie)""").containsMatchIn(input)) return "<敏感字段已移除>"
        var value = input
        privateValues.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { value = value.replace(it, "<PRIVATE>", ignoreCase = true) }
        value = Regex("""(?i)(?:https?|ftp|file)://[^\s"]+""").replace(value, "<URL>")
        value = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").replace(value, "<EMAIL>")
        value = Regex("""(?i)(?:[a-z]:[\\/]|\\\\)[^\r\n"<>|]*""").replace(value) { path(it.value) }
        value = Regex("""(?<![A-Za-z0-9])/(?:[^\s/"<>|]+/)*[^\s,;"<>|]+""").replace(value) { path(it.value) }
        if (Regex("""(?i)^\s*(title|comment|artist|description|filename)\s*:""").containsMatchIn(value)) return "<METADATA_REMOVED>"
        return value
    }
}
