package org.ergoutree.compress.project

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.ergoutree.compress.bootstrap.*
import org.ergoutree.compress.desktop.*
import org.ergoutree.compress.presentation.DesktopController
import javax.swing.JOptionPane
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson

fun main(args: Array<String>) {
    if (System.getProperty("os.name").startsWith("Windows")) System.setProperty("javax.accessibility.assistive_technologies", "com.sun.java.accessibility.AccessBridge")
    val session = try { DesktopBootstrap.open(args.firstOrNull { it.startsWith("--data-root=") }?.substringAfter('='), "--strict-portable" in args) }
    catch (e: Exception) {
        if ("--diagnostics" in args) { System.err.println(e.message); kotlin.system.exitProcess(1) }
        JOptionPane.showMessageDialog(null, e.message, "ErgouCompress 启动失败", JOptionPane.ERROR_MESSAGE); return
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    try {
        val wiring = AppWiring(session)
        if ("--diagnostics" in args) {
            val report = SnapshotJson.encodeToString(mapOf("javaVersion" to System.getProperty("java.version"), "dataRoot" to wiring.dataRoot,
                "notice" to wiring.startupNotice.orEmpty(), "startupProbe" to "passed"))
            val diagnostics = java.nio.file.Files.createDirectories(session.location.root.resolve("diagnostics"))
            java.nio.file.Files.writeString(diagnostics.resolve("startup.json"), report)
            println(report)
            return
        }
        val controller = DesktopController(wiring, scope)
        application {
            val state by controller.state.collectAsState()
            var closeAfterStop by remember { mutableStateOf(false) }
            LaunchedEffect(state.busy, closeAfterStop) { if (closeAfterStop && !state.busy) exitApplication() }
            val drop = remember { DesktopImportTarget { paths -> controller.edit { it.copy(roots = (it.roots.lines().filter(String::isNotBlank) + paths).distinct().joinToString("\n")) } } }
            Window(onCloseRequest = { if (state.busy) { closeAfterStop = true; controller.stop() } else exitApplication() }, title = "ErgouCompress", state = rememberWindowState(width = 1240.dp, height = 860.dp)) {
                App(controller, Modifier.desktopImport(drop), pickFile = { chooseDesktopPath(false, it) }, pickFolder = { chooseDesktopPath(true, it) })
            }
        }
    } finally { scope.cancel(); session.close() }
}
