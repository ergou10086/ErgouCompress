package org.ergoutree.compress.platform.windows

import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import java.io.*

/** Raw Unicode paths support NTFS ADS, which java.nio.Path deliberately rejects on Windows. */
object WindowsDataStreams {
    private val kernel = Kernel32.INSTANCE
    fun input(path: String): InputStream {
        val handle = kernel.CreateFile(path, Int.MIN_VALUE, 7, null, 3, 0x80, null)
        if (handle == WinBase.INVALID_HANDLE_VALUE) throw IOException("打开命名流失败：${kernel.GetLastError()}")
        return object : InputStream() {
            override fun read(): Int { val bytes = ByteArray(1); return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val bytes = ByteArray(minOf(length, 65536)); val count = IntByReference()
                if (!kernel.ReadFile(handle, bytes, bytes.size, count, null)) throw IOException("读取命名流失败：${kernel.GetLastError()}")
                if (count.value == 0) return -1
                bytes.copyInto(buffer, offset, 0, count.value); return count.value
            }
            override fun close() { kernel.CloseHandle(handle) }
        }
    }
    fun create(path: String): OutputStream {
        val handle = kernel.CreateFile(path, 0x40000000, 0, null, 1, 0x80, null)
        if (handle == WinBase.INVALID_HANDLE_VALUE) throw IOException("独占创建命名流失败：${kernel.GetLastError()}")
        return object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                var written = 0
                while (written < length) {
                    val bytes = buffer.copyOfRange(offset + written, minOf(offset + length, offset + written + 65536))
                    val count = IntByReference()
                    if (!kernel.WriteFile(handle, bytes, bytes.size, count, null) || count.value <= 0) throw IOException("写命名流失败：${kernel.GetLastError()}")
                    written += count.value
                }
            }
            override fun flush() { if (!kernel.FlushFileBuffers(handle)) throw IOException("命名流落盘失败：${kernel.GetLastError()}") }
            override fun close() { try { flush() } finally { kernel.CloseHandle(handle) } }
        }
    }
}
