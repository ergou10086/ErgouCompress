package org.ergoutree.compress.infrastructure.ffmpeg

import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*

class FfmpegEncoder(private val runner: ProcessRunner, private val planner: CommandPlanner = CommandPlanner()) : EncoderAdapter {
    override suspend fun encode(plan: ResolvedPlan, candidate: LocalPath, work: LocalPath, stop: StopSignal, onProgress: (Progress) -> Unit) {
        val parser = ProgressParser()
        val result = runner.run(ProcessRequest(planner.encode(plan, candidate), work, timeoutMillis = 7L * 24 * 60 * 60 * 1000,
            allowQuit = true, logPrefix = work.nio().resolve("encode").local(), captureLimit = 16_384), stop) { line ->
            parser.accept(line)?.let(onProgress)
        }
        requireProcess(result, ErrorCode.ENCODE_FAILED, "编码失败")
        stop.checkRunning()
    }
}
