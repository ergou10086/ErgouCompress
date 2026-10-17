package preflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.sun.jna.Native
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.*
import java.awt.datatransfer.*
import java.awt.dnd.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.Accessible
import javax.swing.*

internal const val helpMessage = "输出帧率表示每秒画面数。保持源帧率可避免不必要的丢帧，修改需要重编码。"
private val json = Json { prettyPrint = true }
internal fun saveStrings(path: Path, entries: List<Map<String, String>>) {
    Files.createDirectories(path.parent); Files.writeString(path, json.encodeToString(entries))
}
private fun hash(path: Path): String = Files.newInputStream(path).use { input ->
    val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
    while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
    md.digest().joinToString("") { "%02x".format(it) }
}
private class FilesTransfer(private val entries: List<java.io.File>) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return entries
    }
}

@Composable
internal fun AcceptanceControls(target: FileImportTarget, source: (() -> JPanel)? = null) {
    var fps by remember { mutableStateOf("30") }
    Column(Modifier.fillMaxSize().background(Color(0xfff3f3f3)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicText("ErgouCompress 独立验收窗口")
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (source != null) SwingPanel(factory = source, modifier = Modifier.width(180.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.White).fileImportTarget(target)
                .semantics { contentDescription = "文件导入区域" }) { BasicText("接收文件或文件夹") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText("输出帧率（FPS）")
            BasicTextField(fps, { fps = it }, Modifier.width(100.dp).background(Color.White).padding(8.dp)
                .semantics { contentDescription = "输出帧率输入框" }, singleLine = true)
            HelpTip("输出帧率", helpMessage)
        }
        BasicText("当前输出帧率：$fps", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun dropEventAcceptance(root: Path, report: Path) {
    Files.createDirectories(root)
    val file = root.resolve("中文 空格 [文件].mp4"); Files.writeString(file, "small transfer fixture")
    val dir = root.resolve("文件夹 空格"); Files.createDirectories(dir)
    val checksum = hash(file)
    val rows = mutableListOf<Map<String,String>>()
    fun case(name: String, data: Transferable, actions: Int, expected: List<Path>?) {
        var actual: List<Path>? = null
        val order = mutableListOf<String>()
        val event = object : DropTargetDropEvent(DropTarget().dropTargetContext, Point(1,1),
            DnDConstants.ACTION_MOVE, actions) {
            override fun acceptDrop(action: Int) { check(action == DnDConstants.ACTION_COPY); order.add("copy") }
            override fun getTransferable(): Transferable { check(order.lastOrNull() == "copy"); order.add("read"); return data }
        }
        val receiver = FileImportTarget({ actual = it })
        val accepted = receiver.onDrop(androidx.compose.ui.draganddrop.DragAndDropEvent(
            androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move, event, androidx.compose.ui.geometry.Offset.Zero))
        check(if (expected == null) !accepted && actual == null else accepted && actual == expected)
        if (actions and DnDConstants.ACTION_COPY == 0) check(order.isEmpty())
        rows.add(mapOf("test" to name, "status" to "PASS", "layer" to "real Compose callback / synthetic public AWT event", "order" to order.joinToString()))
    }
    try {
        case("move-proposal-negotiates-copy-before-read",FilesTransfer(listOf(file.toFile())),DnDConstants.ACTION_COPY_OR_MOVE,listOf(file))
        case("copy-source",FilesTransfer(listOf(file.toFile())),DnDConstants.ACTION_COPY,listOf(file))
        case("folder",FilesTransfer(listOf(dir.toFile())),DnDConstants.ACTION_COPY_OR_MOVE,listOf(dir))
        case("multiple",FilesTransfer(listOf(file.toFile(),dir.toFile())),DnDConstants.ACTION_COPY_OR_MOVE,listOf(file,dir))
        case("deduplicate",FilesTransfer(listOf(file.toFile(),file.toFile())),DnDConstants.ACTION_COPY_OR_MOVE,listOf(file))
        case("move-only-rejected-without-read",FilesTransfer(listOf(file.toFile())),DnDConstants.ACTION_MOVE,null)
        case("text-rejected",StringSelection("not a file"),DnDConstants.ACTION_COPY_OR_MOVE,null)
        case("empty-rejected",FilesTransfer(emptyList()),DnDConstants.ACTION_COPY_OR_MOVE,null)
        case("missing-path-rejected",FilesTransfer(listOf(root.resolve("missing").toFile())),DnDConstants.ACTION_COPY_OR_MOVE,null)
        check(hash(file) == checksum && Files.isDirectory(dir))
        rows.add(mapOf("test" to "source-unchanged", "status" to "PASS", "sha256" to checksum))
    } catch (ex: Throwable) { rows.add(mapOf("test" to "event-suite", "status" to "FAIL", "error" to ex.stackTraceToString())) }
    saveStrings(report,rows)
    check(rows.none { it["status"] != "PASS" }) { "Event suite failed; see $report" }
}

internal fun bridgeServer(root: Path, ready: Path) {
    Files.createDirectories(root)
    val target = FileImportTarget({})
    application {
        Window(onCloseRequest = ::exitApplication, title = "ErgouCompress JAB 独立验收",
            state = rememberWindowState(width = 800.dp, height = 480.dp)) {
            AcceptanceControls(target)
            LaunchedEffect(Unit) {
                delay(1000)
                val hwnd = Native.getComponentPointer(window)
                Files.createDirectories(ready.parent)
                Files.writeString(ready, json.encodeToString(mapOf("pid" to ProcessHandle.current().pid().toString(),
                    "hwnd" to com.sun.jna.Pointer.nativeValue(hwnd).toString(),"java" to System.getProperty("java.version"))))
                repeat(300) { if (Files.exists(root.resolve("done"))) { exitApplication(); return@LaunchedEffect }; delay(200) }
                exitApplication()
            }
        }
    }
}
