package org.ergoutree.compress.infrastructure.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import java.nio.file.Files
import java.util.UUID

/** 通过真实短片与音频编码验证工具能力，自检产物纳入应用档案索引。 */
class ValidateToolchain(private val runner: ProcessRunner, private val environment: PlanEnvironment, private val recorder: ArtifactRecorder = NoopArtifactRecorder) {
    suspend fun execute(ffmpeg: LocalPath, ffprobe: LocalPath, archive: LocalPath, stop: StopSignal = StopSignal()): Toolchain = withContext(Dispatchers.IO) {
        val work = Files.createDirectories(archive.nio().resolve(UUID.randomUUID().toString())).local()
        try {
            suspend fun command(exe: LocalPath, vararg args: String): String {
                val result = runner.run(ProcessRequest(listOf(exe.value) + args, work), stop)
                requireProcess(result, ErrorCode.TOOLCHAIN_INVALID, "工具自检失败")
                if (result.stdoutTruncated) throw CompressException(ErrorCode.TOOLCHAIN_INVALID, "工具信息过大")
                return result.stdout + result.stderrTail
            }
            val ff = ToolBinary(environment.identity(ffmpeg).path, environment.evidence(ffmpeg).sha256, command(ffmpeg, "-version").lineSequence().first())
            val probe = ToolBinary(environment.identity(ffprobe).path, environment.evidence(ffprobe).sha256, command(ffprobe, "-version").lineSequence().first())
            if (!ff.version.startsWith("ffmpeg version") || !probe.version.startsWith("ffprobe version")) throw CompressException(ErrorCode.TOOLCHAIN_INVALID, "请选择真实 FFmpeg 与 ffprobe")
            val build = command(ffmpeg, "-buildconf")
            val encoders = command(ffmpeg, "-encoders")
            for (capability in listOf("muxers", "filters", "hwaccels")) Files.writeString(work.nio().resolve("$capability.txt"), command(ffmpeg, "-$capability"))
            Files.writeString(work.nio().resolve("encoders.txt"), encoders)
            val base = Toolchain(ff, probe, emptyList(), build)
            val checks = Backend.entries.map { backend ->
                val name = if (backend == Backend.X264) "libx264" else "hevc_nvenc"
                if (!encoders.contains(name)) BackendCheck(backend, false, "FFmpeg 构建不含 $name") else {
                    val output = work.nio().resolve("$name.mp4").local()
                    val settings = EncodeSettings(backend, if (backend == Backend.X264) 23 else 28)
                    val args = listOf(ff.path.value, "-hide_banner", "-nostdin", "-n", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24", "-t", "0.5") +
                        CommandPlanner().videoOptions(settings) + listOf("-pix_fmt", "yuv420p", "-color_trc", "bt709", output.value)
                    val result = runner.run(ProcessRequest(args, work, logPrefix = work.nio().resolve(name).local()), stop)
                    if (result.stopped) stop.checkRunning()
                    val diagnostic = if (result.exitCode == 0 && !result.timedOut) {
                        val media = FfprobeAdapter(runner).inspect(output, base, work, stop)
                        if (media.streams.any { it.kind == StreamKind.VIDEO } && (media.durationUs ?: 0) > 0) "真实短片编码及 ffprobe 通过" else "自检输出不完整"
                    } else "自检失败：${result.stderrTail.takeLast(900)}"
                    BackendCheck(backend, diagnostic == "真实短片编码及 ffprobe 通过", diagnostic)
                }
            }
            val aacOutput = work.nio().resolve("aac.m4a").local()
            val aacRun = runner.run(ProcessRequest(listOf(ff.path.value, "-hide_banner", "-nostdin", "-n", "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "0.5", "-c:a", "aac", "-b:a", "192k", aacOutput.value), work,
                logPrefix = work.nio().resolve("aac").local()), stop)
            if (aacRun.stopped) stop.checkRunning()
            val aacAvailable = aacRun.exitCode == 0 && !aacRun.timedOut && FfprobeAdapter(runner).inspect(aacOutput, base, work, stop).streams.any { it.kind == StreamKind.AUDIO && it.codec == "aac" && it.sampleRate == 48000 }
            val result = base.copy(checks = checks, aacAvailable = aacAvailable)
            if (!result.supports(Backend.X264)) throw CompressException(ErrorCode.TOOLCHAIN_INVALID, "CPU 基础编码自检失败：${checks.first().diagnostic}")
            Files.writeString(work.nio().resolve("capabilities.json"), SnapshotJson.encodeToString(result))
            result
        } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recorder.directory(work) } }
    }
}
