package preflight

import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun importPaths(data: Transferable): List<Path> {
    require(data.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
    val entries = data.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: error("Not a file list")
    require(entries.isNotEmpty() && entries.all { it is File })
    return entries.map { (it as File).toPath().toAbsolutePath().normalize() }.distinct().onEach {
        require(Files.isRegularFile(it, NOFOLLOW_LINKS) || Files.isDirectory(it, NOFOLLOW_LINKS)) { "Not a local ordinary file/directory: $it" }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal class FileImportTarget(
    private val received: (List<Path>) -> Unit,
    private val highlight: (Boolean) -> Unit = {},
    private val eventLog: (String) -> Unit = {},
) : DragAndDropTarget {
    override fun onStarted(event: DragAndDropEvent) { highlight(true); eventLog("START ${event.action}") }
    override fun onEntered(event: DragAndDropEvent) { eventLog("ENTER ${event.action}") }
    override fun onEnded(event: DragAndDropEvent) { highlight(false) }
    override fun onDrop(event: DragAndDropEvent): Boolean = runCatching {
        val native = event.nativeEvent as? DropTargetDropEvent ?: return false
        if (native.sourceActions and DnDConstants.ACTION_COPY == 0) return false
        native.acceptDrop(DnDConstants.ACTION_COPY)
        val paths = importPaths(event.awtTransferable)
        received(paths)
        eventLog("DROP_COPY proposed=${event.action} count=${paths.size}")
        true
    }.getOrElse { eventLog("DROP_REJECTED $it"); false }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
internal fun Modifier.fileImportTarget(target: FileImportTarget): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }, target = target)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HelpTip(label: String, message: String) {
    var pinned by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    Column {
        TooltipArea(tooltip = { Box(Modifier.background(Color.White).border(1.dp, Color.Gray).padding(12.dp)) {
            BasicText(message, style = TextStyle(color = Color.Black, fontSize = 14.sp))
        } }) {
            Box(Modifier.size(24.dp).border(1.dp, if (focused) Color(0xff0067c0) else Color.Gray, CircleShape)
                .onFocusChanged { if (focused != it.isFocused) dismissed = false; focused = it.isFocused }
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { pinned = false; dismissed = true; true } else false
                }
                .clickable(role = Role.Button, onClickLabel = "显示或关闭${label}说明") { pinned = !pinned; dismissed = !pinned }
                .semantics { contentDescription = "${label}帮助"; stateDescription = if (pinned || (focused && !dismissed)) "已展开" else "已收起" },
                contentAlignment = Alignment.Center) {
                BasicText("i", style = TextStyle(color = Color.Black, fontSize = 13.sp))
            }
        }
        if (pinned || (focused && !dismissed)) BasicText(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = TextStyle(color = Color.Black, fontSize = 12.sp))
    }
}
