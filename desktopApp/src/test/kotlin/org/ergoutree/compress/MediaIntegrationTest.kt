package org.ergoutree.compress

import kotlinx.coroutines.runBlocking
import org.ergoutree.compress.application.*
import org.ergoutree.compress.bootstrap.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.ffmpeg.*
import org.ergoutree.compress.infrastructure.process.*
import org.ergoutree.compress.platform.windows.WindowsProcessLauncher
import org.ergoutree.compress.ports.*
import org.junit.Rule
import org.junit.Assume
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*

/** Explicit opt-in. All media are synthesized under a TemporaryFolder; no private sample inputs. */
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
}
