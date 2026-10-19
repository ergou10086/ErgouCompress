package org.ergoutree.compress.application

import org.ergoutree.compress.domain.*

/** 依据明确码率或采样给出体积范围，未知条件不生成伪精确数值。 */
object SizeEstimation {
    fun schedule(durationUs: Long, budget: SampleBudget): List<SampleWindow> {
        require(durationUs > 0)
        val span = minOf(durationUs, budget.segmentUs)
        val count = minOf(budget.count.toLong(), (durationUs / span).coerceAtLeast(1)).toInt()
        return (0 until count).map { i -> SampleWindow(if (count == 1) 0 else (durationUs - span) / (count - 1) * i + (durationUs - span) % (count - 1) * i / (count - 1), span) }
    }
    fun audioBitrate(mapping: List<StreamMapping>): Double? {
        var sum = 0.0
        for (stream in mapping.filter { it.source.kind == StreamKind.AUDIO }) {
            sum += if (stream.action == StreamAction.ENCODE) (stream.aac ?: AacSettings()).bitrateKbps * 1000.0
                else stream.source.bitrateBps?.toDouble() ?: return null
        }
        return sum
    }
    fun estimate(plan: ResolvedPlan, samples: List<VideoSample> = emptyList()): SizeEstimate {
        return estimate(plan.media, plan.settings, plan.container, samples)
    }
    /** 草稿预览直接使用媒体与参数，不能构造一个可能被误执行的虚拟输出计划。 */
    fun estimate(media: MediaInfo, settings: EncodeSettings, container: Container, samples: List<VideoSample> = emptyList()): SizeEstimate {
        val mapping = MediaStrategy.resolve(media, settings, container)
        val duration = media.durationUs ?: return unknown("缺少时长")
        if (mapping.any { it.source.kind !in setOf(StreamKind.VIDEO, StreamKind.AUDIO) }) return unknown("字幕/附件体积未建模；不能隐藏这部分大小")
        val audio = audioBitrate(mapping) ?: return unknown("复制音频码率未知；不会将源总码率作为视频再重复加音频")
        val video = mapping.single { it.source.kind == StreamKind.VIDEO }
        val rates = when {
            samples.isNotEmpty() -> samples.map { it.bitrateBps }
            video.action == StreamAction.COPY -> listOf(video.source.bitrateBps?.toDouble() ?: return unknown("复制视频码率未知"))
            settings.rateControl == RateControl.BITRATE -> listOf(settings.videoBitrateKbps!! * 1000.0)
            else -> return unknown("固定质量没有确定体积函数；可选择多段视频采样")
        }
        fun bytes(rate: Double, overhead: Double = 65_536.0) = bounded((rate + audio) * duration / 8_000_000.0 * 1.03 + overhead)
        return SizeEstimate(if (samples.isEmpty()) EstimateBasis.BITRATE_BUDGET else EstimateBasis.VIDEO_SAMPLES,
            bytes(rates.average(), if (samples.isEmpty()) 65_536.0 else 4096.0), bytes(rates.min() * 0.65, 0.0), bytes(rates.max() * 1.50),
            if (samples.isEmpty()) "视频与所选音频码率预算，含 3% + 64 KiB 封装余量；实际码率会波动"
            else "${samples.size} 段等时间分层视频有效包样本（排除样本容器重复开销）+ 独立音频预算；min/max 扩展范围不保证覆盖未采到的场景")
    }
    fun unknown(reason: String) = SizeEstimate(EstimateBasis.UNKNOWN, explanation = reason)
    internal fun bounded(bytes: Double) = bytes.coerceIn(0.0, Long.MAX_VALUE.toDouble()).toLong()
}

/** One instance per attempt. Regressing/missing progress never yields fabricated precision. */
class DynamicSizePredictor(private val durationUs: Long?) {
    private var lastTime = 0L
    private var lastBytes = 0L
    private var smoothed: Double? = null
    fun update(progress: Progress): SizeEstimate {
        val time = progress.outTimeUs ?: return SizeEstimation.unknown("进度缺少媒体时间")
        val bytes = progress.totalBytes ?: return SizeEstimation.unknown("进度缺少输出字节数")
        val duration = durationUs ?: return SizeEstimation.unknown("媒体总时长未知")
        if (time < 2_000_000 || time < lastTime || bytes < lastBytes || bytes <= 0 || duration <= 0) return SizeEstimation.unknown("样本过短、进度回退或尚未写出有效字节")
        lastTime = time; lastBytes = bytes
        val raw = bytes.toDouble() / time * duration
        smoothed = smoothed?.let { it * 0.7 + raw * 0.3 } ?: raw
        val value = smoothed!!
        return SizeEstimate(EstimateBasis.RUNNING_PROGRESS, SizeEstimation.bounded(value), SizeEstimation.bounded(value * 0.7), SizeEstimation.bounded(value * 1.4),
            "已写字节/已处理媒体时长的平滑外推；封装、长 GOP 和场景变化会影响结果，封装关闭前不是实际大小")
    }
}
