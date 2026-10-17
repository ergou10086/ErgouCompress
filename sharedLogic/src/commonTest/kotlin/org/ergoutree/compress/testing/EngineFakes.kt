package org.ergoutree.compress.testing

import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

fun sampleMedia() = MediaInfo(format = "mov,mp4,m4a,3gp,3g2,mj2", durationUs = 3_000_000, streams = listOf(
    MediaStream(0, StreamKind.VIDEO, "h264", width = 128, height = 96, pixelFormat = "yuv420p", frameRate = Rational(24, 1), colorTransfer = "bt709"),
    MediaStream(1, StreamKind.AUDIO, "aac", sampleRate = 48000, channels = 2),
))
fun sampleTools() = Toolchain(ToolBinary(LocalPath("/tools/ffmpeg"), "tools", "ffmpeg"), ToolBinary(LocalPath("/tools/ffprobe"), "tools", "ffprobe"), listOf(BackendCheck(Backend.X264, true, "tested")), "build")
fun samplePlan() = ResolvedPlan(source = FileIdentity(LocalPath("/input/video.mp4"), 500, 1, "file-id"), media = sampleMedia(), tools = sampleTools(), settings = EncodeSettings(), container = Container.MP4, destination = LocalPath("/output/video.mp4"))

class FakeEnvironment : PlanEnvironment {
    val plan = samplePlan()
    var changed = false
    var conflict = false
    var space = Long.MAX_VALUE
    override suspend fun identity(path: LocalPath) = if (changed) plan.source.copy(modifiedMillis = 2) else plan.source
    override suspend fun evidence(path: LocalPath) = ContentEvidence(identity(path), if (path.value.startsWith("/tools")) "tools" else "content")
    override suspend fun sha256(value: String) = "hash:$value"
    override suspend fun exists(path: LocalPath) = conflict
    override suspend fun availableBytes(path: LocalPath) = space
    override suspend fun target(directory: LocalPath, source: LocalPath, container: Container) = LocalPath(directory.value + "/" + source.value.substringAfterLast('/').substringBeforeLast('.') + "." + container.extension)
}

class FakeArtifacts : CopyArtifacts {
    var published = 0
    val records = mutableListOf<String>()
    var stopInPreparation = false
    override suspend fun create(plan: ResolvedPlan, planHash: String) = AttemptWorkspace("attempt", LocalPath("/work/attempt"), LocalPath("/work/attempt/candidate.mp4"))
    override suspend fun record(work: AttemptWorkspace, name: String, json: String) { records += name }
    override suspend fun preparePublication(plan: ResolvedPlan, receipt: ValidationReceipt, work: AttemptWorkspace, stop: StopSignal): PreparedCopy {
        if (stopInPreparation) stop.request()
        return PreparedCopy(plan, receipt, work, receipt.candidate)
    }
    override suspend fun publish(prepared: PreparedCopy): LocalPath { published++; return prepared.plan.destination }
}
