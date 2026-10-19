package org.ergoutree.compress.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.swing.JFileChooser
import java.awt.FileDialog
import java.awt.Frame

/** 仅接收文件复制拖入，不接受移动语义；悬停状态由宿主用于拖入区反馈。 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopImportTarget(private val hover: (Boolean) -> Unit = {}, private val received: (List<String>) -> Unit) : DragAndDropTarget {
    override fun onEntered(event: DragAndDropEvent) { hover(true) }
    override fun onExited(event: DragAndDropEvent) { hover(false) }
    override fun onEnded(event: DragAndDropEvent) { hover(false) }
    override fun onDrop(event: DragAndDropEvent): Boolean = runCatching {
        hover(false)
        val drop = event.nativeEvent as? DropTargetDropEvent ?: return false
        if (drop.sourceActions and DnDConstants.ACTION_COPY == 0) return false
        drop.acceptDrop(DnDConstants.ACTION_COPY)
        val paths = (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.map {
            require(it is File)
            it.toPath().toAbsolutePath().normalize().also { path ->
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            }.toString()
        }?.distinct().orEmpty()
        if (paths.isEmpty()) return false
        received(paths)
        true
    }.getOrDefault(false)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun Modifier.desktopImport(target: DesktopImportTarget): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }, target = target)

fun chooseDesktopPath(directory: Boolean, result: (String) -> Unit) {
    if (!directory) {
        FileDialog(null as Frame?, "选择文件", FileDialog.LOAD).apply {
            isVisible = true
            files.firstOrNull()?.let { result(it.absolutePath) }
            dispose()
        }
        return
    }
    val chooser = JFileChooser().apply { fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result(chooser.selectedFile.absolutePath)
}

/** 使用系统文件对话框多选，取消选择不创建空任务。 */
fun chooseDesktopFiles(result: (List<String>) -> Unit) {
    FileDialog(null as Frame?, "添加视频文件", FileDialog.LOAD).apply {
        isMultipleMode = true; isVisible = true
        if (files.isNotEmpty()) result(files.map { it.absolutePath })
        dispose()
    }
}

/** 诊断包目标只返回用户选择，实际冲突与路径检查仍由共享用例负责。 */
fun chooseSupportDestination(result: (String) -> Unit) {
    FileDialog(null as Frame?, "导出脱敏诊断包", FileDialog.SAVE).apply {
        file = "ErgouCompress-support.zip"; isVisible = true
        files.firstOrNull()?.let { result(it.absolutePath) }; dispose()
    }
}
