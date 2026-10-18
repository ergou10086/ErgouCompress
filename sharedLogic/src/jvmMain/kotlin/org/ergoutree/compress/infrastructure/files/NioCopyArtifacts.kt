package org.ergoutree.compress.infrastructure.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.application.PlanEncoding
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

/** Owns copy publication only. Never has an overwrite/delete-source operation. */
class NioCopyArtifacts(private val archive: LocalPath, private val files: PlanEnvironment) : CopyArtifacts {
    override suspend fun create(plan: ResolvedPlan, planHash: String): AttemptWorkspace = withContext(Dispatchers.IO) {
        if (files.availableBytes(archive) < plan.source.sizeBytes.coerceAtMost((Long.MAX_VALUE - 67_108_864L) / 2) * 2 + 67_108_864L)
            throw CompressException(ErrorCode.INSUFFICIENT_SPACE, "数据根空间不足，候选需要保守空间预算")
        val id = UUID.randomUUID().toString()
        val root = Files.createDirectories(archive.nio().resolve("jobs").resolve(id)).local()
        val work = AttemptWorkspace(id, root, root.nio().resolve("encoded.partial.${plan.container.extension}").local())
        record(work, "plan.json", SnapshotJson.encodeToString(plan))
        record(work, "identity.json", SnapshotJson.encodeToString(mapOf("planHash" to planHash, "attemptId" to id)))
        work
    }

    override suspend fun record(work: AttemptWorkspace, name: String, json: String): Unit = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[a-z-]+\\.json")))
        val path = work.root.nio().resolve(name)
        // Records are append-only per attempt. force completes before any publication.
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    override suspend fun preparePublication(plan: ResolvedPlan, receipt: ValidationReceipt, work: AttemptWorkspace, stop: StopSignal): PreparedCopy = withContext(Dispatchers.IO) {
        stop.checkRunning()
        require(plan.outputMode == OutputMode.COPY) { "副本适配器不提供原地覆盖" }
        if (!receipt.fullDecodePassed || receipt.planHash != files.sha256(PlanEncoding.canonical(plan)) || receipt.candidate.identity.path != work.candidate)
            throw CompressException(ErrorCode.VALIDATION_FAILED, "校验凭据与计划/候选不匹配")
        if (files.evidence(plan.source.path) != receipt.source) throw CompressException(ErrorCode.INPUT_CHANGED, "发布前源文件已变化")
        if (files.evidence(work.candidate) != receipt.candidate) throw CompressException(ErrorCode.VALIDATION_FAILED, "发布前候选已变化")
        val target = plan.destination.nio()
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw CompressException(ErrorCode.DESTINATION_CONFLICT, "目标已存在，拒绝覆盖：$target")
        // For cross-volume output, this is a registered output workspace, never a source sidecar.
        val sameStore = Files.getFileStore(work.root.nio()) == Files.getFileStore(target.parent)
        val stageRoot = if (sameStore) work.root.nio() else Files.createDirectories(target.parent.resolve(".ErgouCompressWorkspace").resolve(work.id))
        val staged = stageRoot.resolve("publish-staged.${plan.container.extension}")
        record(work, "publish-intent.json", SnapshotJson.encodeToString(mapOf("target" to target.toString(), "staged" to staged.toString(), "sha256" to receipt.candidate.sha256)))
        Files.newInputStream(work.candidate.nio()).use { input ->
            Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    stop.checkRunning()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        FileChannel.open(staged, StandardOpenOption.WRITE).use { it.force(true) }
        val stagedEvidence = files.evidence(staged.local())
        if (stagedEvidence.sha256 != receipt.candidate.sha256) throw CompressException(ErrorCode.VALIDATION_FAILED, "副本 staging 哈希不符")
        if (files.evidence(plan.source.path) != receipt.source) throw CompressException(ErrorCode.INPUT_CHANGED, "复制期间源文件已变化")
        stop.checkRunning()
        PreparedCopy(plan, receipt, work, stagedEvidence)
    }

    override suspend fun publish(prepared: PreparedCopy): LocalPath = withContext(Dispatchers.IO) {
        val (plan, receipt, work, stagedEvidence) = prepared
        val target = plan.destination.nio()
        val staged = stagedEvidence.identity.path.nio()
        if (files.identity(plan.source.path) != receipt.source.identity) throw CompressException(ErrorCode.INPUT_CHANGED, "最终发布边界源身份变化")
        if (files.identity(staged.local()) != stagedEvidence.identity) throw CompressException(ErrorCode.VALIDATION_FAILED, "最终发布边界 staging 身份变化")
        try {
            // Atomic create-without-replace on supported local filesystems, including NTFS.
            // ATOMIC_MOVE may overwrite an existing target on some providers; do not use it here.
            Files.createLink(target, staged)
        } catch (e: FileAlreadyExistsException) { throw CompressException(ErrorCode.DESTINATION_CONFLICT, "发布时目标冲突，候选保留", e) }
        catch (e: java.io.IOException) { throw CompressException(ErrorCode.STORE_UNAVAILABLE, "目标文件系统不支持安全副本发布或不可写；候选保留：${e.message}", e) }
        val published = files.evidence(target.local())
        if (published.sha256 != receipt.candidate.sha256) throw CompressException(ErrorCode.VALIDATION_FAILED, "发布后目标被改写，需要人工检查：$target")
        record(work, "published.json", SnapshotJson.encodeToString(published))
        Files.delete(staged) // Candidate remains independent; the target is never removed.
        target.local()
    }
}
