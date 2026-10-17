package org.ergoutree.compress.infrastructure.ffmpeg

import org.ergoutree.compress.domain.Progress

class ProgressParser {
    private val fields = LinkedHashMap<String, String>()
    fun accept(line: String): Progress? {
        val separator = line.indexOf('=')
        if (separator <= 0) return null
        val key = line.substring(0, separator)
        val value = line.substring(separator + 1).trim()
        if (fields.size < 64 || key in fields) fields[key] = value.take(4096)
        if (key != "progress") return null
        val time = fields["out_time_us"]?.toLongOrNull() ?: parseTime(fields["out_time"])
        val speed = fields["speed"]?.removeSuffix("x")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        val result = Progress(time, fields["total_size"]?.toLongOrNull()?.takeIf { it >= 0 }, speed, value == "end")
        fields.clear()
        return result
    }
    private fun parseTime(value: String?): Long? {
        val parts = value?.split(':') ?: return null
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = secondsUs(parts[2]) ?: return null
        return runCatching { Math.addExact(Math.multiplyExact(hours * 60 + minutes, 60_000_000), seconds) }.getOrNull()
    }
}
