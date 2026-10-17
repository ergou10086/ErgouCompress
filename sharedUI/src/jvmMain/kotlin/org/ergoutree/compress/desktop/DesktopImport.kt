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

@OptIn(ExperimentalComposeUiApi::class)
class DesktopImportTarget(private val received: (List<String>) -> Unit) : DragAndDropTarget {
    override fun onDrop(event: DragAndDropEvent): Boolean = runCatching {
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
    val chooser = JFileChooser().apply { fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result(chooser.selectedFile.absolutePath)
}
