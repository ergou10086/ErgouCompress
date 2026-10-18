package org.ergoutree.compress

import kotlinx.coroutines.runBlocking
import org.ergoutree.compress.application.*
import org.ergoutree.compress.bootstrap.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.ffmpeg.*
import org.ergoutree.compress.infrastructure.analysis.*
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.infrastructure.process.*
import org.ergoutree.compress.platform.windows.WindowsProcessLauncher
import org.ergoutree.compress.ports.*
import org.junit.Rule
import org.junit.Assume
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*

/** 显式启用真实媒体回归，全部合成素材由临时目录管理，测试结束自动清理。 */
class MediaIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun runner(): ProcessRunner = JvmProcessRunner(if (System.getProperty("os.name").startsWith("Windows")) WindowsProcessLauncher() else PortableProcessLauncher())
    private suspend fun fixture(): Fixture {
        val ffmpeg = System.getenv("ERGOU_TEST_FFMPEG").orEmpty()
        val ffprobe = System.getenv("ERGOU_TEST_FFPROBE").orEmpty()
        require(ffmpeg.isNotBlank() && ffprobe.isNotBlank()) { "显式媒体测试必须提供 ERGOU_TEST_FFMPEG / ERGOU_TEST_FFPROBE" }
        val root = temporary.newFolder("合成 媒体").toPath()
        val archive = Files.createDirectory(root.resolve("data"))
        val source = root.resolve("中文 测试.mp4").local()
        val process = runner()
        val files = NioEnvironment()
        val tools = ValidateToolchain(process, files).execute(LocalPath(ffmpeg), LocalPath(ffprobe), archive.local())
        val generated = process.run(ProcessRequest(listOf(ffmpeg, "-hide_banner", "-nostdin", "-n", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000", "-t", "3",
            "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-vf", "setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709",
            "-c:a", "aac", "-ac", "2", "-metadata", "title=合成测试", "-metadata:s:a:0", "language=zho", "-metadata:s:a:1", "language=eng", source.value), root.local()))
        assertEquals(0, generated.exitCode, generated.stderrTail)
        val probe = FfprobeAdapter(process)
        val media = probe.inspect(source, tools, root.local(), StopSignal())
        return Fixture(root.local(), source, archive.local(), tools, process, files, probe, media)
    }
    private data class Fixture(val root: LocalPath, val source: LocalPath, val archive: LocalPath, val tools: Toolchain, val runner: ProcessRunner, val files: NioEnvironment, val probe: MediaProbe, val media: MediaInfo)

    private suspend fun encode(f: Fixture, backend: Backend, container: Container): AttemptResult {
        val output = Files.createDirectory(f.root.nio().resolve("out-$backend-$container")).local()
        val plan = FreezeJob(f.files).execute("media-test", listOf(PlanningSelection(f.files.identity(f.source), f.media)), f.tools,
            EncodeSettings(backend, if (backend == Backend.X264) 23 else 28, container = container), output).plans.single()
        val before = f.files.evidence(f.source)
        val useCase = RunCopy(f.files, FfmpegEncoder(f.runner), FullMediaValidator(f.runner, f.probe, f.files), NioCopyArtifacts(f.archive, f.files))
        val stages = mutableListOf<AttemptStage>()
        val result = useCase.execute(plan, StopSignal()) { stages += it.stage }
        assertEquals(AttemptStage.SUCCEEDED, result.stage, result.message)
        assertEquals(before, f.files.evidence(f.source))
        assertTrue(Files.size(result.destination!!.nio()) > 0)
        assertTrue(AttemptStage.VALIDATING in stages && AttemptStage.PUBLISHING in stages)
        return result
    }
    @Test fun cpuMp4AndMkvPreserveBothAudioTracksAndPublishVerifiedCopies(): Unit = runBlocking {
        val f = fixture()
        encode(f, Backend.X264, Container.MP4)
        encode(f, Backend.X264, Container.MKV)
    }
    @Test fun nvencMustActuallyEncodeOrBeReportedAsSkipped(): Unit = runBlocking {
        val f = fixture()
        Assume.assumeTrue(f.tools.checks.first { it.backend == Backend.NVENC }.diagnostic, f.tools.supports(Backend.NVENC))
        encode(f, Backend.NVENC, Container.MP4)
    }
    @Test fun truncatedCandidateFailsWithoutTouchingSource() = runBlocking {
        val f = fixture()
        val sourceBefore = f.files.evidence(f.source)
        val output = Files.createDirectory(f.root.nio().resolve("output")).local()
        val plan = FreezeJob(f.files).execute("bad", listOf(PlanningSelection(f.files.identity(f.source), f.media)), f.tools, EncodeSettings(), output).plans.single()
        val candidate = f.root.nio().resolve("broken.mp4")
        Files.write(candidate, Files.readAllBytes(f.source.nio()).take(256).toByteArray())
        assertFailsWith<CompressException> { FullMediaValidator(f.runner, f.probe, f.files).validate(plan, "bad", sourceBefore, candidate.local(), f.root, StopSignal()) }
        assertEquals(sourceBefore, f.files.evidence(f.source)); assertFalse(Files.exists(plan.destination.nio()))
    }
    @Test fun desktopWiringConnectsStartupScanFreezeAndCopy(): Unit = runBlocking {
        val f = fixture()
        val output = Files.createDirectory(f.root.nio().resolve("desktop-output"))
        DesktopBootstrap.open(f.archive.value).use { session ->
            val app = AppWiring(session)
            val tools = app.validateTools(f.tools.ffmpeg.path.value, f.tools.ffprobe.path.value, StopSignal())
            val entries = mutableListOf<ScannedMedia>()
            var complete = false
            app.scan(listOf(f.source.value), 0, 0, output.toString(), tools, StopSignal()).collect {
                if (it is ScanUpdate.Entry) entries += it.item
                if (it is ScanUpdate.Summary) complete = it.complete
            }
            assertTrue(complete); assertTrue(entries.single().eligible)
            val selected = entries.single()
            val job = app.freeze(listOf(PlanningSelection(selected.file, selected.media!!)), tools, EncodeSettings(), output.toString())
            app.queue.submit(job)
            app.queue.recover()
            val result = app.queue.run(job.id).single()
            assertEquals(AttemptStage.SUCCEEDED, result.stage, result.message)
            assertEquals(JobState.COMPLETED, app.queue.history().single().state)
            assertTrue(Files.exists(f.archive.nio().resolve("config/tools.json")))
        }
    }
    @Test fun realMediaReplacementAndOriginalRestoreUseAuthoritativeReceipt(): Unit = runBlocking {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val f = fixture(); val before = f.files.evidence(f.source)
        DesktopBootstrap.open(f.archive.value).use { session ->
            val app = AppWiring(session)
            val job = app.prepareRequest(ProcessingRequest(listOf(f.source.value), f.tools.ffmpeg.path.value, f.tools.ffprobe.path.value,
                mode = OutputMode.REPLACE, onlyIfSmaller = false))
            app.queue.submit(job); app.queue.recover()
            val result = app.queue.run(job.id).single()
            assertEquals(AttemptStage.SUCCEEDED, result.stage, result.message)
            val commit = app.commitHistory().single(); assertEquals(CommitPhase.COMMITTED, commit.phase)
            assertEquals(before.sha256, f.files.evidence(commit.backup!!).sha256)
            app.commitTransactions!!.restore(commit.id)
            assertEquals(before.sha256, f.files.evidence(f.source).sha256)
        }
    }

    private suspend fun runSettings(f: Fixture, name: String, settings: EncodeSettings): Pair<ResolvedPlan, MediaInfo> {
        val output = Files.createDirectory(f.root.nio().resolve(name)).local()
        val plan = FreezeJob(f.files).execute(name, listOf(PlanningSelection(f.files.identity(f.source), f.media)), f.tools, settings, output).plans.single()
        val before = f.files.evidence(f.source)
        val result = RunCopy(f.files, FfmpegEncoder(f.runner), FullMediaValidator(f.runner, f.probe, f.files), NioCopyArtifacts(f.archive, f.files)).execute(plan, StopSignal())
        val diagnostic = if (result.stage == AttemptStage.SUCCEEDED) "" else Files.walk(f.archive.nio()).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".log") }.limit(4).toList()
                .joinToString("\n") { Files.readString(it).takeLast(1500) }
        }
        // 失败线索进入测试断言，不另复制日志到长期保留的测试目录。
        assertEquals(AttemptStage.SUCCEEDED, result.stage, result.message + "\n" + diagnostic)
        assertEquals(before, f.files.evidence(f.source))
        return plan to f.probe.inspect(result.destination!!, f.tools, f.root, StopSignal())
    }
    @Test fun typedAacResizeFpsDropAndMetadataRemovalPassFullValidation(): Unit = runBlocking {
        val base = fixture()
        val chapters = base.root.nio().resolve("chapters.txt")
        Files.writeString(chapters, ";FFMETADATA1\ntitle=章节测试\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=3000\ntitle=完整章节\n")
        val source = base.root.nio().resolve("chaptered.mp4").local()
        val generated = base.runner.run(ProcessRequest(listOf(base.tools.ffmpeg.path.value, "-hide_banner", "-nostdin", "-n", "-i", base.source.value, "-f", "ffmetadata", "-i", chapters.toString(),
            "-map", "0", "-c", "copy", "-map_metadata", "1", "-map_chapters", "1", source.value), base.root))
        assertEquals(0, generated.exitCode, generated.stderrTail)
        val f = base.copy(source = source, media = base.probe.inspect(source, base.tools, base.root, StopSignal()))
        assertEquals(1, f.media.chapters.size)
        // FFmpeg MP4 chapters also produce a data stream; it must be explicitly dropped.
        val drops = f.media.streams.filter { it.kind == StreamKind.DATA }.map { StreamPolicy(it.index, StreamAction.DROP) }
        val settings = EncodeSettings(maxWidth = 160, maxHeight = 120, frameRate = Rational(12, 1), rateControl = RateControl.BITRATE, videoBitrateKbps = 300,
            preserveMetadata = false, preserveChapters = false, streams = listOf(StreamPolicy(1, StreamAction.ENCODE, AacSettings(96, 44100, 1)), StreamPolicy(2, StreamAction.DROP)) + drops)
        val (_, media) = runSettings(f, "transformed", settings)
        assertEquals(2, media.streams.size); assertEquals(160, media.streams[0].width); assertEquals(Rational(12, 1), media.streams[0].frameRate)
        assertEquals(44100, media.streams[1].sampleRate); assertEquals(1, media.streams[1].channels)
        assertTrue(media.chapters.isEmpty()); assertFalse(media.tags.keys.any { it.equals("title", true) })
        val (_, preserved) = runSettings(f, "chapters-preserved", EncodeSettings(streams = drops))
        assertEquals("完整章节", preserved.chapters.single().tags["title"])
        assertEquals(4, preserved.streams.size) // Three selected AV tracks plus an audited MP4 chapter helper.
    }
    @Test fun aacWithoutOverridesPreservesStereo48000AndDropReindexesCopyTrack(): Unit = runBlocking {
        val f = fixture()
        val (_, encoded) = runSettings(f, "aac-preserve", EncodeSettings(streams = listOf(StreamPolicy(1, StreamAction.ENCODE, AacSettings(128)), StreamPolicy(2, StreamAction.DROP))))
        assertEquals(48000, encoded.streams[1].sampleRate); assertEquals(2, encoded.streams[1].channels)
        val (_, copied) = runSettings(f, "copy-reindex", EncodeSettings(streams = listOf(StreamPolicy(1, StreamAction.DROP), StreamPolicy(2, StreamAction.COPY, defaultDisposition = true))))
        assertEquals("eng", copied.streams[1].tags.entries.first { it.key.equals("language", true) }.value)
    }
    @Test fun remuxRetainsVideoAndAudioPacketsAcrossMp4Mkv(): Unit = runBlocking {
        val f = fixture()
        val (plan, output) = runSettings(f, "remux-mkv", EncodeSettings(operation = MediaOperation.REMUX, container = Container.MKV))
        assertEquals("h264", output.streams[0].codec)
        val mkv = f.copy(source = plan.destination, media = output)
        runSettings(mkv, "remux-mp4", EncodeSettings(operation = MediaOperation.REMUX, container = Container.MP4))
    }
    @Test fun mkvSubtitlesAndFontArePreservedAndMp4RequiresExplicitDrops(): Unit = runBlocking {
        val f = fixture()
        val subtitle = f.root.nio().resolve("字幕.srt")
        Files.writeString(subtitle, "1\n00:00:00,100 --> 00:00:02,800\n测试字幕\n")
        val font = java.nio.file.Path.of(System.getenv("WINDIR"), "Fonts", "arial.ttf")
        require(Files.isRegularFile(font)) { "字体附件测试需要 Windows Arial fixture" }
        val source = f.root.nio().resolve("字幕 附件.mkv").local()
        val run = f.runner.run(ProcessRequest(listOf(f.tools.ffmpeg.path.value, "-hide_banner", "-nostdin", "-n", "-i", f.source.value, "-i", subtitle.toString(), "-map", "0", "-map", "1", "-c", "copy",
            "-attach", font.toString(), "-metadata:s:t", "mimetype=application/x-truetype-font", "-metadata:s:t", "filename=font.ttf", source.value), f.root))
        assertEquals(0, run.exitCode, run.stderrTail)
        val complex = f.copy(source = source, media = f.probe.inspect(source, f.tools, f.root, StopSignal()))
        assertEquals(5, complex.media.streams.size)
        runSettings(complex, "complex-mkv", EncodeSettings(container = Container.MKV))
        runSettings(complex, "complex-mkv-structural-tags", EncodeSettings(container = Container.MKV, preserveMetadata = false))
        assertNotNull(MediaStrategy.rejection(complex.media, EncodeSettings(), Container.MP4))
        val drops = complex.media.streams.filter { it.kind in setOf(StreamKind.SUBTITLE, StreamKind.ATTACHMENT) }.map { StreamPolicy(it.index, StreamAction.DROP) }
        runSettings(complex, "complex-mp4", EncodeSettings(container = Container.MP4, streams = drops))
    }
    @Test fun optionalSamplingCacheAndStaticHintsAreMeasuredAgainstActualOutputs(): Unit = runBlocking {
        val f = fixture()
        val sampler = FfmpegMediaSampler(f.runner, f.probe, f.archive.nio().resolve("samples").local())
        val analyzer = AnalyzeMedia(f.files, sampler, JsonAnalysisCache(f.archive.nio().resolve("analysis-cache")))
        val metrics = mutableListOf<Map<String, String>>()
        for (kind in listOf("static", "motion", "noise")) {
            val source = f.root.nio().resolve("$kind.mp4").local()
            val input = if (kind == "static") "color=c=blue:size=320x240:rate=24" else "testsrc2=size=320x240:rate=24"
            val filters = (if (kind == "noise") "noise=alls=30:allf=t," else "") + "setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709"
            val generated = f.runner.run(ProcessRequest(listOf(f.tools.ffmpeg.path.value, "-hide_banner", "-nostdin", "-n", "-f", "lavfi", "-i", input, "-t", "6", "-vf", filters, "-c:v", "libx264", "-pix_fmt", "yuv420p", source.value), f.root))
            assertEquals(0, generated.exitCode, generated.stderrTail)
            val media = f.probe.inspect(source, f.tools, f.root, StopSignal())
            val plan = FreezeJob(f.files).execute("analysis-$kind", listOf(PlanningSelection(f.files.identity(source), media)), f.tools, EncodeSettings(quality = 25), Files.createDirectory(f.root.nio().resolve("estimate-$kind")).local()).plans.single()
            val before = f.files.evidence(source)
            val options = AnalysisOptions(sample = true, staticHint = true, budget = SampleBudget(3, 1_000_000))
            val start = System.nanoTime()
            val report = analyzer.execute(plan, options)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(3, report.samples.size); assertEquals(EstimateBasis.VIDEO_SAMPLES, report.estimate.basis)
            assertEquals(report, analyzer.execute(plan, options))
            assertEquals(before, f.files.evidence(source))
            assertEquals(if (kind == "static") StaticClassification.CANDIDATE else StaticClassification.MOTION_OBSERVED, report.staticHint!!.classification)
            val result = RunCopy(f.files, FfmpegEncoder(f.runner), FullMediaValidator(f.runner, f.probe, f.files), NioCopyArtifacts(f.archive, f.files)).execute(plan, StopSignal())
            assertEquals(AttemptStage.SUCCEEDED, result.stage, result.message)
            val actual = Files.size(result.destination!!.nio())
            val predicted = report.estimate.expectedBytes!!
            val relativeError = kotlin.math.abs(predicted - actual).toDouble() / actual
            metrics += mapOf("class" to kind, "actualBytes" to actual.toString(), "predictedBytes" to predicted.toString(), "relativeError" to relativeError.toString(),
                "rangeCovered" to (actual in report.estimate.lowerBytes!!..report.estimate.upperBytes!!).toString(), "samplingAndStaticMillis" to elapsedMs.toString(), "sampleMediaSeconds" to "3", "note" to "6 秒合成小视频，封装预算占比高；不是生产误差承诺")
        }
        // 基准属于当轮输出；可将结论写入文档，原始报告随测试结果清理。
        println("分析基准：" + SnapshotJson.encodeToString(metrics))
    }
    @Test fun fractionalAndVariableFrameTimelinesSurviveWithoutImplicitCfr(): Unit = runBlocking {
        val f = fixture()
        for (vfr in listOf(false, true)) {
            val source = f.root.nio().resolve("timeline-$vfr.mp4").local()
            val filters = (if (vfr) "select='not(mod(n,3))+not(mod(n,5))'," else "") + "setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709"
            val generated = f.runner.run(ProcessRequest(listOf(f.tools.ffmpeg.path.value, "-hide_banner", "-nostdin", "-n", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30000/1001", "-t", "4", "-vf", filters,
                "-fps_mode:v", "passthrough", "-enc_time_base:v", "demux", "-c:v", "libx264", "-pix_fmt", "yuv420p", source.value), f.root))
            assertEquals(0, generated.exitCode, generated.stderrTail)
            val sourceMedia = f.probe.inspect(source, f.tools, f.root, StopSignal())
            runSettings(f.copy(source = source, media = sourceMedia), "timeline-out-$vfr", EncodeSettings(container = Container.MKV))
        }
    }
}
