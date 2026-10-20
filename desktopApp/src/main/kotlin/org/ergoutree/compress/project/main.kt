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
import androidx.compose.ui.unit.Dp
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
        System.err.println("ErgouCompress 启动失败：${e.message}")
        if ("--diagnostics" in args || BackendCommandLine.accepts(args)) { kotlin.system.exitProcess(1) }
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
            val appIcon = rememberAppIcon()
            // Segoe UI Variable 是 Windows 11 的系统正文；中文由系统字体回退补全。
            val desktopFont = remember { FontFamily("Segoe UI Variable Text") }
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
            Window(onCloseRequest = { if (state.busy) confirmClose = true else controller.requestClose() }, title = "ErgouCompress",
                icon = appIcon, state = rememberWindowState(width = 1240.dp, height = 920.dp)) {
                window.minimumSize = java.awt.Dimension(980, 720)
                FluentTheme(state.preferences, systemDark, desktopFont) {
                    Box(Modifier.fillMaxSize()) {
                        WorkspaceApp(
                            controller = controller,
                            importModifier = Modifier.desktopImport(drop),
                            dragging = dragging,
                            openPage = { page = it },
                            openLogs = { logId = it },
                            pickFiles = { chooseSystemPaths(window, multiple = true, result = controller::importPaths) },
                            pickFolder = { chooseSystemPaths(window, directory = true, result = controller::importPaths) },
                            reveal = { path -> revealLocation(path) },
                            formatTime = { time -> localTime(time) },
                        )
                        if (confirmClose) {
                            FContentDialog(
                                title = "停止任务并关闭？",
                                message = "当前文件会安全停止，正在发布的文件会先完成核对。下次启动可在历史中继续；未完成文件会重新处理。",
                                primaryLabel = "安全停止并关闭",
                                onPrimary = { confirmClose = false; controller.requestClose() },
                                secondaryLabel = "继续使用",
                                onSecondary = { confirmClose = false },
                                destructive = true,
                            )
                        }
                    }
                }
            }
            page?.let { current ->
                val (title, width, height, minWidth, minHeight) = when (current) {
                    WorkspacePage.OPTIONS -> WindowSpec("处理选项", 1020.dp, 900.dp, 840, 680)
                    WorkspacePage.SETTINGS -> WindowSpec("设置", 1020.dp, 900.dp, 840, 680)
                    WorkspacePage.HISTORY -> WindowSpec("任务历史", 1100.dp, 880.dp, 860, 620)
                    WorkspacePage.ABOUT -> WindowSpec("关于", 680.dp, 780.dp, 560, 600)
                }
                Window(onCloseRequest = { page = null }, title = "$title · ErgouCompress",
                    icon = appIcon, state = rememberWindowState(width = width, height = height)) {
                    window.minimumSize = java.awt.Dimension(minWidth, minHeight)
                    FluentTheme(state.preferences, systemDark, desktopFont) {
                        when (current) {
                            WorkspacePage.OPTIONS -> TaskOptionsPage(controller,
                                { selected -> chooseSystemPaths(window, directory = true) { it.firstOrNull()?.let(selected) } },
                                { page = null }, openSettings = { page = WorkspacePage.SETTINGS })
                            WorkspacePage.SETTINGS -> SettingsPage(controller,
                                { selected -> chooseSystemPaths(window) { it.firstOrNull()?.let(selected) } },
                                { selected -> chooseSystemPaths(window, directory = true) { it.firstOrNull()?.let(selected) } },
                                { selected -> chooseSystemPaths(window, save = true) { it.firstOrNull()?.let(selected) } }) { page = null }
                            WorkspacePage.HISTORY -> HistoryPage(controller, { logId = it }, { path -> revealLocation(path) },
                                { time -> localTime(time) }) { page = null }
                            WorkspacePage.ABOUT -> AboutPage(
                                close = { page = null },
                                dataRoot = controller.workflow.dataRoot,
                                openDataRoot = { openDirectory(controller.workflow.dataRoot) },
                            )
                        }
                    }
                }
            }
            logId?.let { id ->
                Window(onCloseRequest = { logId = null }, title = "任务日志 · ${id.take(8)} · ErgouCompress",
                    icon = appIcon, state = rememberWindowState(width = 1040.dp, height = 800.dp)) {
                    window.minimumSize = java.awt.Dimension(820, 600)
                    FluentTheme(state.preferences, systemDark, desktopFont) {
                        TaskLogPageView(controller, id, { logId = null }, { time -> localClock(time) })
                    }
                }
            }
        }
    } finally { scope.cancel(); session.close() }
}

private data class WindowSpec(val title: String, val width: Dp, val height: Dp, val minWidth: Int, val minHeight: Int)

private fun windowsDarkTheme(): Boolean = runCatching {
    System.getProperty("os.name").startsWith("Windows") && Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme") == 0
}.getOrDefault(false)
private fun localTime(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun localClock(time: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun revealLocation(path: String) {
    val file = File(path).absoluteFile
    val target = if (file.isDirectory) file else file.parentFile
    if (target != null && target.isDirectory && Desktop.isDesktopSupported()) runCatching { Desktop.getDesktop().open(target) }
}
private fun openDirectory(path: String) {
    val file = File(path).absoluteFile
    if (file.isDirectory && Desktop.isDesktopSupported()) runCatching { Desktop.getDesktop().open(file) }
}

private fun chooseSystemPaths(owner: java.awt.Window, directory: Boolean = false, multiple: Boolean = false, save: Boolean = false, result: (List<String>) -> Unit) {
    if (System.getProperty("os.name").startsWith("Windows")) {
        try { WindowsFileDialog.choose(owner, directory, multiple, save).takeIf { it.isNotEmpty() }?.let(result) }
        catch (e: Exception) { JOptionPane.showMessageDialog(owner, "无法打开系统文件选择器：${e.message}", "选择文件", JOptionPane.ERROR_MESSAGE) }
    } else if (save) chooseSupportDestination { result(listOf(it)) }
    else if (multiple) chooseDesktopFiles(result)
    else chooseDesktopPath(directory) { result(listOf(it)) }
}
