package org.ergoutree.compress.platform.windows

import com.sun.jna.platform.win32.*
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

object WindowsFileIdentity {
    fun extendedPath(path: Path): String {
        val absolute = path.toAbsolutePath().normalize().toString()
        return when {
            absolute.startsWith("\\\\?\\") -> absolute
            absolute.startsWith("\\\\") -> "\\\\?\\UNC\\" + absolute.removePrefix("\\\\")
            else -> "\\\\?\\$absolute"
        }
    }
    fun key(path: Path, attributes: BasicFileAttributes): String {
        val kernel = Kernel32.INSTANCE
        val handle = kernel.CreateFile(extendedPath(path), 0x80, 7, null, 3, 0x00200000, null) // READ_ATTRIBUTES; share all; OPEN_REPARSE_POINT.
        if (handle == WinBase.INVALID_HANDLE_VALUE) throw IOException("读取文件身份失败：${kernel.GetLastError()}，$path")
        try {
            val info = WinBase.FILE_ID_INFO()
            if (!kernel.GetFileInformationByHandleEx(handle, 18, info.pointer, WinDef.DWORD(info.size().toLong()))) throw IOException("读取卷/file ID 失败：${kernel.GetLastError()}，$path")
            info.read()
            return "${java.lang.Long.toUnsignedString(info.VolumeSerialNumber, 16)}:${info.FileId.Identifier.joinToString("") { "%02x".format(it.toInt() and 255) }}"
        } finally { kernel.CloseHandle(handle) }
    }
}
