package org.ergoutree.compress.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

val SnapshotJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

data class PlanningSelection(val file: FileIdentity, val media: MediaInfo, val override: EncodeSettings? = null)

class FreezeJob(private val environment: PlanEnvironment) {
    suspend fun execute(id: String, selection: List<PlanningSelection>, tools: Toolchain, defaults: EncodeSettings, outputDirectory: LocalPath): FrozenJob {
        require(selection.isNotEmpty()) { "请先选择至少一个合格文件" }
        val plans = selection.map { selected ->
            val settings = selected.override ?: defaults
            if (!tools.supports(settings.backend)) throw CompressException(ErrorCode.TOOLCHAIN_INVALID, "所选编码后端尚未通过实际自检")
            if (environment.identity(selected.file.path) != selected.file) throw CompressException(ErrorCode.INPUT_CHANGED, "扫描后源文件已变化")
            val container = settings.container ?: when {
                selected.media.format.split(',').contains("matroska") -> Container.MKV
                selected.media.format.split(',').contains("mp4") -> Container.MP4
                else -> throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, "源容器尚不支持，请明确选择 MP4 或 MKV 副本")
            }
            MediaPolicy.rejection(selected.media, container)?.let { throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, it) }
            val target = environment.target(outputDirectory, selected.file.path, container)
            if (environment.exists(target)) throw CompressException(ErrorCode.DESTINATION_CONFLICT, "目标已存在：${target.value}")
            ResolvedPlan(source = selected.file, media = selected.media, tools = tools, settings = settings, container = container, destination = target)
        }
        // target() returns filesystem-normalized paths; aliases are checked by the adapter.
        if (plans.map { it.destination.value.lowercase() }.distinct().size != plans.size) throw CompressException(ErrorCode.DESTINATION_CONFLICT, "多个输入映射到同一个目标，请分开任务或选择其他目录")
        val reserve = 64L * 1024 * 1024
        var required = 0L
        for (plan in plans) {
            if (plan.source.sizeBytes < 0 || plan.source.sizeBytes > (Long.MAX_VALUE - reserve) / 2 - required)
                throw CompressException(ErrorCode.INSUFFICIENT_SPACE, "输入大小超出安全预算范围")
            required += plan.source.sizeBytes
        }
        if (environment.availableBytes(outputDirectory) < required * 2 + reserve)
            throw CompressException(ErrorCode.INSUFFICIENT_SPACE, "输出卷需要至少两倍输入大小及 64 MiB 安全余量（保守预算，非输出体积保证）")
        // Round-trip creates an owned deep snapshot, independent of mutable scan/UI collections.
        val owned = SnapshotJson.decodeFromString<List<ResolvedPlan>>(SnapshotJson.encodeToString(plans))
        return FrozenJob(id = id, plans = owned, planHash = environment.sha256(SnapshotJson.encodeToString(owned)))
    }
}
