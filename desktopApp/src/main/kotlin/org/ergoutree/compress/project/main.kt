package org.ergoutree.compress.project

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.*
import org.ergoutree.compress.bootstrap.*
import org.ergoutree.compress.desktop.*
import org.ergoutree.compress.presentation.WorkspaceController
import org.ergoutree.compress.workspace.*
import org.ergoutree.compress.domain.AppTheme
import org.ergoutree.compress.platform.windows.WindowsFileDialog
import javax.swing.JOptionPane
import javax.swing.UIManager
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.Desktop
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson

/** 启动本地工作台并保持一个组合根；独立窗口共享同一持久任务状态。 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
fun main(args: Array<String>) {
    if (System.getProperty("os.name").startsWith("Windows")) System.setProperty("javax.accessibility.assistive_technologies", "com.sun.java.accessibility.AccessBridge")
    val session = try { DesktopBootstrap.open(args.firstOrNull { it.startsWith("--data-root=") }?.substringAfter('='), "--strict-portable" in args) }
    catch (e: Exception) {
        if ("--diagnostics" in args || BackendCommandLine.accepts(args)) { System.err.println(e.message); kotlin.system.exitProcess(1) }
        JOptionPane.showMessageDialog(null, e.message, "ErgouCompress 启动失败", JOptionPane.ERROR_MESSAGE); return
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    try {
        val wiring = AppWiring(session)
        if (BackendCommandLine.accepts(args)) {
            try { BackendCommandLine.execute(args, wiring) }
            catch (e: Exception) { System.err.println(e.message); throw e }
            return
        }
        if ("--diagnostics" in args) {
            val report = SnapshotJson.encodeToString(mapOf("javaVersion" to System.getProperty("java.version"), "dataRoot" to wiring.dataRoot,
                "notice" to wiring.startupNotice.orEmpty(), "startupProbe" to "passed"))
            val diagnostics = java.nio.file.Files.createDirectories(session.location.root.resolve("diagnostics"))
            java.nio.file.Files.writeString(diagnostics.resolve("startup.json"), report)
            println(report)
            return
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        val controller = WorkspaceController(wiring, scope)
        application {
            val state by controller.state.collectAsState()
            var page by remember { mutableStateOf<WorkspacePage?>(null) }
            var logId by remember { mutableStateOf<String?>(null) }
            var dragging by remember { mutableStateOf(false) }
            var confirmClose by remember { mutableStateOf(false) }
            var systemDark by remember { mutableStateOf(false) }
            val desktopFont = remember { FontFamily("Microsoft YaHei UI") }
            LaunchedEffect(Unit) {
                controller.initialize()
                args.filter { it.startsWith("--open=") }.map { it.substringAfter('=') }.takeIf { it.isNotEmpty() }?.let(controller::importPaths)
            }
            LaunchedEffect(state.preferences.theme) {
                while (state.preferences.theme == AppTheme.SYSTEM) {
                    systemDark = withContext(Dispatchers.IO) { windowsDarkTheme() }; delay(5000)
                }
            }
            LaunchedEffect(state.canExit) { if (state.canExit) exitApplication() }
            LaunchedEffect(state.selectedJobId) { if (state.selectedJobId != null && page == WorkspacePage.OPTIONS && state.rows.isEmpty()) page = null }
            val drop = remember { DesktopImportTarget(hover = { dragging = it }, received = controller::importPaths) }
            Window(onCloseRequest = { if (state.busy) confirmClose = true else controller.requestClose() }, title = "ErgouCompress", state = rememberWindowState(width = 1180.dp, height = 900.dp)) {
                window.minimumSize = java.awt.Dimension(920, 720)
                FluentTheme(state.preferences, systemDark, desktopFont) {
                    WorkspaceApp(controller, Modifier.desktopImport(drop), dragging, openPage = { page = it }, openLogs = { logId = it },
                        pickFiles = { chooseSystemPaths(window, multiple = true, result = controller::importPaths) },
                        pickFolder = { chooseSystemPaths(window, directory = true, result = controller::importPaths) })
                }
            }
            page?.let { current ->
                Window(onCloseRequest = { page = null }, title = "${when (current) { WorkspacePage.OPTIONS -> "处理选项"; WorkspacePage.SETTINGS -> "设置"; WorkspacePage.HISTORY -> "任务历史"; WorkspacePage.ABOUT -> "关于" }} · ErgouCompress",
                    state = rememberWindowState(width = 860.dp, height = 840.dp)) {
                    window.minimumSize = java.awt.Dimension(700, 600)
                    FluentTheme(state.preferences, systemDark, desktopFont) {
                        BookPage(state.preferences.reduceMotion) {
                            when (current) {
                                WorkspacePage.OPTIONS -> TaskOptionsPage(controller, { selected -> chooseSystemPaths(window, directory = true) { it.firstOrNull()?.let(selected) } }) { page = null }
                                WorkspacePage.SETTINGS -> SettingsPage(controller, { selected -> chooseSystemPaths(window) { it.firstOrNull()?.let(selected) } },
                                    { selected -> chooseSystemPaths(window, directory = true) { it.firstOrNull()?.let(selected) } },
                                    { selected -> chooseSystemPaths(window, save = true) { it.firstOrNull()?.let(selected) } }) { page = null }
                                WorkspacePage.HISTORY -> HistoryPage(controller, { logId = it }, { path -> revealLocation(path) }, { time -> localTime(time) }) { page = null }
                                WorkspacePage.ABOUT -> AboutPage { page = null }
                            }
                        }
                    }
                }
            }
            logId?.let { id ->
                Window(onCloseRequest = { logId = null }, title = "任务日志 · ${id.take(8)} · ErgouCompress", state = rememberWindowState(width = 960.dp, height = 760.dp)) {
                    window.minimumSize = java.awt.Dimension(740, 560)
                    FluentTheme(state.preferences, systemDark, desktopFont) { BookPage(state.preferences.reduceMotion) { TaskLogPageView(controller, id) { logId = null } } }
                }
            }
            if (confirmClose) DialogWindow(onCloseRequest = { confirmClose = false }, title = "关闭 ErgouCompress", state = rememberDialogState(width = 480.dp, height = 260.dp)) {
                FluentTheme(state.preferences, systemDark, desktopFont) {
                    Column(Modifier.fillMaxSize().background(LocalFluentColors.current.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FText("停止任务并关闭？", 20, strong = true)
                        FText("当前文件将安全停止，正在发布的文件会先完成核对。下次启动可在历史中继续，未完成文件会重新处理。", 13)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FButton("继续使用") { confirmClose = false }
                            FButton("安全停止并关闭", primary = true) { confirmClose = false; controller.requestClose() }
                        }
                    }
                }
            }
        }
    } finally { scope.cancel(); session.close() }
}

/** 书页采用短距离展开与左侧书脊，不旋转文字；减少动画时立即呈现。 */
@Composable
private fun BookPage(reduceMotion: Boolean, content: @Composable () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered || reduceMotion) 1f else 0f, tween(if (reduceMotion) 0 else 180))
    Row(Modifier.fillMaxSize().background(LocalFluentColors.current.background)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(LocalFluentColors.current.border))
        Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { translationX = (1 - progress) * 18.dp.toPx(); alpha = progress }) { content() }
    }
}

private fun windowsDarkTheme(): Boolean = runCatching {
    System.getProperty("os.name").startsWith("Windows") && Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme") == 0
}.getOrDefault(false)
private fun localTime(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun revealLocation(path: String) {
    val file = File(path).absoluteFile
    if (Desktop.isDesktopSupported() && file.parentFile.isDirectory) Desktop.getDesktop().open(file.parentFile)
}

private fun chooseSystemPaths(owner: java.awt.Window, directory: Boolean = false, multiple: Boolean = false, save: Boolean = false, result: (List<String>) -> Unit) {
    if (System.getProperty("os.name").startsWith("Windows")) {
        try { WindowsFileDialog.choose(owner, directory, multiple, save).takeIf { it.isNotEmpty() }?.let(result) }
        catch (e: Exception) { JOptionPane.showMessageDialog(owner, "无法打开系统文件选择器：${e.message}", "选择文件", JOptionPane.ERROR_MESSAGE) }
    } else if (save) chooseSupportDestination { result(listOf(it)) }
    else if (multiple) chooseDesktopFiles(result)
    else chooseDesktopPath(directory) { result(listOf(it)) }
}
