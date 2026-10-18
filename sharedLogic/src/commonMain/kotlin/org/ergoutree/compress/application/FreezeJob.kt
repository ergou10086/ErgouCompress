package org.ergoutree.compress.application

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

val SnapshotJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

data class PlanningSelection(val file: FileIdentity, val media: MediaInfo, val override: EncodeSettings? = null)

class FreezeJob(private val environment: PlanEnvironment) {
    suspend fun execute(id: String, selection: List<PlanningSelection>, tools: Toolchain, defaults: EncodeSettings, outputDirectory: LocalPath, mode: OutputMode = OutputMode.COPY, onlyIfSmaller: Boolean = true): FrozenJob {
        require(selection.isNotEmpty()) { "请先选择至少一个合格文件" }
        val plans = selection.map { selected ->
            val settings = selected.override ?: defaults
            if (environment.identity(selected.file.path) != selected.file) throw CompressException(ErrorCode.INPUT_CHANGED, "扫描后源文件已变化")
            val container = settings.container ?: when {
                selected.media.format.split(',').contains("matroska") -> Container.MKV
                selected.media.format.split(',').contains("mp4") -> Container.MP4
                else -> throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, "源容器尚不支持，请明确选择 MP4 或 MKV 副本")
            }
            val mapping = MediaStrategy.resolve(selected.media, settings, container)
            if (mapping.any { it.source.kind == StreamKind.VIDEO && it.action == StreamAction.ENCODE } && !tools.supports(settings.backend)) throw CompressException(ErrorCode.TOOLCHAIN_INVALID, "所选编码后端尚未通过实际自检")
            if (mapping.any { it.source.kind == StreamKind.AUDIO && it.action == StreamAction.ENCODE } && !tools.aacAvailable) throw CompressException(ErrorCode.TOOLCHAIN_INVALID, "AAC 尚未通过实际自检")
            val target = when (mode) {
                OutputMode.REPLACE -> selected.file.path
                OutputMode.CHANGE_EXTENSION -> LocalPath(selected.file.path.value.substringBeforeLast('.') + "." + container.extension)
                OutputMode.COPY -> environment.target(outputDirectory, selected.file.path, container)
            }
            if (mode == OutputMode.CHANGE_EXTENSION && target == selected.file.path) throw CompressException(ErrorCode.DESTINATION_CONFLICT, "改扩展名模式需要不同的目标扩展名")
            if (mode == OutputMode.REPLACE && !selected.file.path.value.endsWith(".${container.extension}", true)) throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, "同名替换不能改扩展名")
            if (mode != OutputMode.REPLACE && environment.exists(target)) throw CompressException(ErrorCode.DESTINATION_CONFLICT, "目标已存在：${target.value}")
            ResolvedPlan(schemaVersion = 3, plannerVersion = 3, source = selected.file, media = selected.media, tools = tools, settings = settings, container = container, destination = target, outputMode = mode, onlyIfSmaller = onlyIfSmaller)
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
        return FrozenJob(id = id, plans = owned, planHash = environment.sha256(PlanEncoding.canonicalList(owned)))
    }
}
