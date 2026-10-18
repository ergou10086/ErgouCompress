package org.ergoutree.compress.application

import org.ergoutree.compress.domain.*

object MediaRecommendations {
    fun forPlan(plan: ResolvedPlan): List<Recommendation> = buildList {
        val mapping = MediaStrategy.resolve(plan.media, plan.settings, plan.container)
        val video = mapping.single { it.source.kind == StreamKind.VIDEO }
        add(Recommendation("PRESERVE_DEFAULTS", "默认保留尺寸、帧率和音轨规格；${video.source.width}×${video.source.height} 源视频无需为了压缩自动降分辨率"))
        if (plan.settings.operation == MediaOperation.REMUX) add(Recommendation("REMUX", "兼容转封装保持编码内容，主要改变封装，通常不会显著缩小视频数据"))
        if (plan.settings.rateControl == RateControl.QUALITY && video.action == StreamAction.ENCODE) add(Recommendation("SAMPLE_SIZE", "固定质量无法直接计算大小；需要预测时可选有预算的分层采样"))
        if (plan.settings.backend == Backend.NVENC && video.action == StreamAction.ENCODE) add(Recommendation("NVENC", "该任务使用已自检的 HEVC NVENC；CQ 与 x264 CRF 不作为同等画质数值"))
        if (mapping.any { it.source.kind == StreamKind.AUDIO && it.action == StreamAction.ENCODE }) add(Recommendation("AAC_LOSSY", "AAC 重编码有损；若不需要改变音轨，优先 Copy 并保留声道/采样率"))
        if (mapping.any { it.source.kind == StreamKind.AUDIO && it.action == StreamAction.COPY && it.source.bitrateBps == null }) add(Recommendation("UNKNOWN_AUDIO_BUDGET", "复制音频码率缺失，体积预测将显示未知，不复用源总码率"))
        if (plan.settings.frameRate != null) add(Recommendation("FPS_LOSS", "显式 CFR 会按时间戳复制/丢帧；静态候选不会替你选择低帧率"))
        if (!plan.settings.preserveMetadata || !plan.settings.preserveChapters) add(Recommendation("METADATA_LOSS", "本任务明确关闭了标签或章节保留"))
        plan.settings.streams.filter { it.action == StreamAction.DROP }.forEach { add(Recommendation("DROP_${it.sourceIndex}", "已明确丢弃源流 ${it.sourceIndex}，请确认任务目的")) }
        add(Recommendation("STATIC_HINT_ONLY", "静态候选只供人工提示，不自动删除未采样位置的运动或降低帧率"))
    }
}
