package org.ergoutree.compress.platform.windows

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import org.ergoutree.compress.domain.ManagedArtifact
import org.ergoutree.compress.infrastructure.files.hex
import java.nio.file.Path
import java.security.MessageDigest

/** 仅暴露按已打开句柄设置删除标记的 Win32 调用。 */
private interface DispositionApi : StdCallLibrary {
    fun SetFileInformationByHandle(handle: WinNT.HANDLE, type: Int, data: Pointer, size: Int): Boolean
}
/** 锁定父目录和目标后按同一文件句柄复核并删除，避免校验与路径删除之间的替换竞态。 */
object WindowsManagedDeletion {
    private val kernel = Kernel32.INSTANCE
    private val api = Native.load("kernel32", DispositionApi::class.java)
    fun delete(path: Path, artifact: ManagedArtifact) {
        val parents = mutableListOf<WinNT.HANDLE>()
        fun opened(path: Path, access: Int, share: Int, flags: Int): WinNT.HANDLE {
            val handle = kernel.CreateFile(WindowsFileIdentity.extendedPath(path), access, share, null, 3, flags, null)
            check(handle != WinBase.INVALID_HANDLE_VALUE) { "清理文件锁定失败：${kernel.GetLastError()}" }
            return handle
        }
        try {
            // 禁止父目录被重命名，目标独占打开后禁止内容写入和链接替换。
            generateSequence(path.parent) { it.parent }.toList().asReversed().forEach { parent ->
                parents += opened(parent, 0x80, 3, 0x02200000)
                check(kernel.GetFileAttributes(WindowsFileIdentity.extendedPath(parent)) and 0x400 == 0) { "父目录是重解析点" }
            }
            val target = opened(path, Int.MIN_VALUE or 0x00010000, 0, 0x00200000)
            try {
                val id = WinBase.FILE_ID_INFO()
                check(kernel.GetFileInformationByHandleEx(target, 18, id.pointer, WinDef.DWORD(id.size().toLong())))
                id.read()
                val key = "${java.lang.Long.toUnsignedString(id.VolumeSerialNumber, 16)}:${id.FileId.Identifier.joinToString("") { "%02x".format(it.toInt() and 255) }}"
                check(key == artifact.evidence.identity.fileKey) { "清理句柄 file ID 已变化" }
                val basic = Memory(40)
                check(kernel.GetFileInformationByHandleEx(target, 0, basic, WinDef.DWORD(40)))
                check(basic.getInt(32) and 0x400 == 0) { "目标是重解析点" }
                check(basic.getLong(16) / 10_000 - 11_644_473_600_000L == artifact.evidence.identity.modifiedMillis) { "目标修改时间变化" }
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128 * 1024); var size = 0L
                while (true) {
                    val count = IntByReference()
                    check(kernel.ReadFile(target, buffer, buffer.size, count, null)) { "清理句柄读取失败" }
                    if (count.value == 0) break
                    size += count.value; digest.update(buffer, 0, count.value)
                }
                check(size == artifact.evidence.identity.sizeBytes && digest.digest().hex() == artifact.evidence.sha256) { "句柄内容与预览证据不符" }
                // FILE_DISPOSITION_INFO 的 DeleteFile 为一字节 BOOLEAN，不是四字节 Win32 BOOL。
                val disposition = Memory(1).apply { setByte(0, 1) }
                check(api.SetFileInformationByHandle(target, 4, disposition, 1)) { "清理删除标记失败：${kernel.GetLastError()}" }
            } finally { kernel.CloseHandle(target) }
        } finally { parents.asReversed().forEach { kernel.CloseHandle(it) } }
    }
}
