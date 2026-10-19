package org.ergoutree.compress.platform.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

/** 使用 Windows Shell 的现代文件/目录选择器，COM 对象和返回字符串在同一线程释放。 */
object WindowsFileDialog {
    private const val CANCELLED = -2147023673
    fun choose(owner: Window, directory: Boolean = false, multiple: Boolean = false, save: Boolean = false): List<String> {
        val initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
        COMUtils.checkRC(initialized)
        try {
            val result = PointerByReference()
            val clsid = if (save) "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}" else "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"
            val iid = if (save) "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}" else "{D57C7288-D4AD-4768-BE02-9D969532D960}"
            COMUtils.checkRC(Ole32.INSTANCE.CoCreateInstance(Guid.CLSID(clsid), null, WTypes.CLSCTX_INPROC_SERVER, Guid.GUID(iid), result))
            val dialog = ShellObject(result.value)
            try {
                val options = IntByReference()
                COMUtils.checkRC(dialog.call(10, options))
                // 只允许文件系统路径，不改变工作目录；目录与多选使用 Shell 的显式契约。
                val flags = options.value or 0x40 or 0x8 or 0x800 or (if (directory) 0x20 else 0) or (if (multiple) 0x200 else 0)
                COMUtils.checkRC(dialog.call(9, flags))
                COMUtils.checkRC(dialog.call(17, WString(if (save) "导出脱敏诊断包" else if (directory) "选择文件夹" else "选择文件")))
                if (save) { COMUtils.checkRC(dialog.call(15, WString("ErgouCompress-support.zip"))); COMUtils.checkRC(dialog.call(22, WString("zip"))) }
                val shown = dialog.call(3, WinDef.HWND(Native.getWindowPointer(owner)))
                if (shown.toInt() == CANCELLED) return emptyList()
                COMUtils.checkRC(shown)
                val selected = PointerByReference()
                if (!multiple || save) {
                    COMUtils.checkRC(dialog.call(20, selected))
                    return listOf(pathOf(selected.value))
                }
                COMUtils.checkRC(dialog.call(27, selected))
                val array = ShellObject(selected.value)
                try {
                    val count = IntByReference(); COMUtils.checkRC(array.call(7, count))
                    return (0 until count.value).map { index ->
                        val item = PointerByReference(); COMUtils.checkRC(array.call(8, index, item)); pathOf(item.value)
                    }
                } finally { array.Release() }
            } finally { dialog.Release() }
        } finally { Ole32.INSTANCE.CoUninitialize() }
    }
    /** 接管一个 IShellItem 引用并返回 UTF-16 路径，始终释放 COM 引用与字符串。 */
    internal fun pathOf(pointer: Pointer): String {
        val item = ShellObject(pointer)
        try {
            val name = PointerByReference()
            COMUtils.checkRC(item.call(5, 0x80058000.toInt(), name)) // SIGDN_FILESYSPATH 返回绝对文件系统路径。
            try { return name.value.getWideString(0) } finally { Ole32.INSTANCE.CoTaskMemFree(name.value) }
        } finally { item.Release() }
    }
    /** 虚表索引遵循 IFileDialog、IShellItem 与 IShellItemArray 的 Windows ABI。 */
    private class ShellObject(pointer: Pointer) : Unknown(pointer) {
        fun call(index: Int, vararg values: Any): WinNT.HRESULT = _invokeNativeObject(index, arrayOf<Any>(pointer, *values), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}
