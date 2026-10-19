package org.ergoutree.compress

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import org.ergoutree.compress.platform.windows.WindowsFileDialog
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*

/** 对真实 Shell 对象验证 Unicode 文件与目录路径及 COM 所有权边界。 */
class WindowsShellPathTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun shellItemsReturnChineseAndSpacePathsForFilesAndDirectories() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        COMUtils.checkRC(Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED))
        try {
            val root = temporary.newFolder("中文 目录").toPath()
            val file = Files.writeString(root.resolve("示例 视频.mp4"), "fixture")
            val shell = Native.load("shell32", ShellApi::class.java)
            for (path in listOf(root, file)) {
                val pointer = PointerByReference()
                COMUtils.checkRC(shell.SHCreateItemFromParsingName(WString(path.toAbsolutePath().toString()), null,
                    Guid.GUID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}"), pointer))
                assertEquals(path.toAbsolutePath().toString(), WindowsFileDialog.pathOf(pointer.value))
            }
        } finally { Ole32.INSTANCE.CoUninitialize() }
    }
    /** 仅绑定系统 Shell 导出，不创建窗口或模拟用户输入。 */
    private interface ShellApi : StdCallLibrary {
        fun SHCreateItemFromParsingName(path: WString, bindContext: Pointer?, iid: Guid.GUID, result: PointerByReference): WinNT.HRESULT
    }
}
