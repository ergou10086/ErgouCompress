package preflight

import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import preflight.db.PreflightDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDropEvent
import java.nio.file.*
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant

private val ffmpeg = "D:\\OtherLanguageSetting\\FFmpeg\\ffmpeg-8.1.1-essentials_build\\bin\\ffmpeg.exe"
private val blue = Color(0xff0067c0)
private val paper = Color(0xfff3f3f3)
private val textColor = Color(0xff202020)
private fun log(path: Path, value: String) = Files.writeString(path, value + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
@Serializable
private data class Snapshot(val schemaVersion: Int = 1, val path: String, val bytes: Long, val fps: String)

private fun crashWrite(path: Path): Nothing {
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
        connection.createStatement().use { it.execute("PRAGMA synchronous=FULL") }
        connection.autoCommit = false
        connection.createStatement().use { it.executeUpdate("UPDATE job_item SET state='Succeeded' WHERE id=2") }
        Runtime.getRuntime().halt(73)
    }
    error("unreachable")
}

private fun appHome(): Path {
    val command = ProcessHandle.current().info().command().orElse("")
    if (command.endsWith("ErgouPreflight.exe", ignoreCase = true)) return Path.of(command).parent
    return Path.of(System.getProperty("ergou.app.home") ?: error("Development launch requires ergou.app.home"))
}
private fun bootstrap(): Path {
    val data = appHome().resolve("ErgouCompressData")
    Files.createDirectories(data.resolve("native")); Files.createDirectories(data.resolve("tmp"))
    System.setProperty("jna.tmpdir", data.resolve("native").toString())
    System.setProperty("org.sqlite.tmpdir", data.resolve("native").toString())
    log(data.resolve("bootstrap.log"), "${Instant.now()} command=${ProcessHandle.current().info().command()} cwd=${Path.of("").toAbsolutePath()} home=${appHome()} data=$data java=${System.getProperty("java.version")}")
    return data
}
private fun selfTest(data: Path) {
    val report = data.resolve("self-test.log")
    val dbPath = data.resolve("probe-${System.currentTimeMillis()}.sqlite")
    val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
    driver.execute(null, "PRAGMA journal_mode=DELETE", 0)
    driver.execute(null, "PRAGMA synchronous=FULL", 0)
    PreflightDatabase.Schema.create(driver)
    val db = PreflightDatabase(driver)
    db.taskQueries.insertItem(1, "Succeeded"); db.taskQueries.insertItem(2, "Interrupted")
    runCatching { db.transaction { db.taskQueries.updateState("Succeeded", 2, 0); error("rollback experiment") } }
    check(db.taskQueries.allItems().executeAsList().last().state == "Interrupted")
    driver.close()
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT sqlite_version()").use { rs -> rs.next(); log(report, "sqlite=${rs.getString(1)}") }
            stmt.executeQuery("PRAGMA integrity_check").use { rs -> rs.next(); check(rs.getString(1) == "ok") }
        }
    }
    val command = ProcessHandle.current().info().command().orElse("")
    val crashArgv = if (command.endsWith("ErgouPreflight.exe", true)) listOf(command) else
        listOf(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp", System.getProperty("java.class.path"), "preflight.MainKt")
    val child = ProcessBuilder(crashArgv + listOf("--sqlite-crash-child", dbPath.toString()))
        .redirectErrorStream(true).redirectOutput(data.resolve("sqlite-crash-child.log").toFile()).start()
    check(child.waitFor() == 73)
    val recoveredDriver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
    check(PreflightDatabase(recoveredDriver).taskQueries.allItems().executeAsList().last().state == "Interrupted")
    recoveredDriver.close()
    val legacy = JdbcSqliteDriver("jdbc:sqlite:${data.resolve("legacy-${System.nanoTime()}.sqlite")}")
    legacy.execute(null, "CREATE TABLE job_item(id INTEGER NOT NULL PRIMARY KEY, state TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0)", 0)
    legacy.execute(null, "INSERT INTO job_item VALUES(1, 'Interrupted', 7)", 0)
    check(PreflightDatabase.Schema.version == 2L)
    PreflightDatabase.Schema.migrate(legacy, 1, 2)
    val migrated = PreflightDatabase(legacy).taskQueries.allItems().executeAsOne()
    check(migrated.state == "Interrupted" && migrated.revision == 7L && migrated.attempt_count == 0L)
    legacy.close()
    val snapshot = Snapshot(path = "中文 空格/视频.mp4", bytes = 9_007_199_254_740_993L, fps = "30000/1001")
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val encoded = json.encodeToString(snapshot)
    check(json.decodeFromString<Snapshot>(encoded.dropLast(1) + ",\"future\":true}") == snapshot)
    log(data.resolve("snapshot-roundtrip.json"), encoded)
    val original = data.resolve("replace-original-${System.nanoTime()}.bin")
    val candidate = original.resolveSibling(original.fileName.toString() + ".candidate")
    val backup = original.resolveSibling(original.fileName.toString() + ".backup")
    Files.writeString(original, "original"); Files.writeString(candidate, "candidate")
    val filesApi = Native.load("kernel32", FilesApi::class.java)
    check(filesApi.ReplaceFileW(WString(original.toString()), WString(candidate.toString()), WString(backup.toString()), 0, null, null))
    check(Files.readString(original) == "candidate" && Files.readString(backup) == "original")
    val output = data.resolve("ffmpeg-version.log")
    val process = ProcessBuilder(ffmpeg, "-hide_banner", "-version").directory(data.toFile())
        .redirectOutput(output.toFile()).redirectErrorStream(true).start()
    check(process.waitFor() == 0)
    log(report, "PASS sqlDelight=2.4.1 jna=${Native.VERSION} serialization=1.11.0 transactionRollback=true crashRollback=true generatedMigration=1to2 jsonLongAndRational=true replaceBackup=true processArgv=true portableData=$data")
    println("PREFLIGHT_SELF_TEST_PASS data=$data")
}
interface Dwm : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: IntByReference, size: Int): Int
}
interface FilesApi : StdCallLibrary {
    fun ReplaceFileW(source: WString, candidate: WString, backup: WString, flags: Int, exclude: Pointer?, reserved: Pointer?): Boolean
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tip(label: String, message: String) = HelpTip(label, message)
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--commit-crash-child") { bootstrap(); commitCrashChild(Path.of(args[1]), args[2]); return }
    if (args.firstOrNull() == "--commit-acceptance") { bootstrap(); commitAcceptance(Path.of(args[1]), Path.of(args[2])); return }
    if (args.firstOrNull() == "--bridge-server") {
        System.setProperty("javax.accessibility.assistive_technologies", "com.sun.java.accessibility.AccessBridge")
        bootstrap(); bridgeServer(Path.of(args[1]), Path.of(args[2])); return
    }
    if (args.firstOrNull() == "--drop-event-acceptance") {
        bootstrap(); dropEventAcceptance(Path.of(args[1]), Path.of(args[2])); return
    }
    if (args.firstOrNull() == "--sqlite-crash-child") crashWrite(Path.of(args[1]))
    val data = bootstrap()
    selfTest(data)
    if ("--self-test" in args) return
    application {
        var dropText by remember { mutableStateOf("拖入文件或文件夹以验证 Windows 拖放") }
        var fps by remember { mutableStateOf("30") }
        var dragging by remember { mutableStateOf(false) }
        val receiver = remember {
            FileImportTarget(received = { paths -> dropText = "已接收 ${paths.size} 项：${paths.joinToString()}" },
                highlight = { dragging = it }, eventLog = { log(data.resolve("ui-events.log"), it) })
        }
        Window(onCloseRequest = ::exitApplication, title = "ErgouCompress S0 验证", state = rememberWindowState(width = 1000.dp, height = 680.dp)) {
            DisposableEffect(window) {
                runCatching {
                    val pointer = Native.getComponentPointer(window)
                    val result = Native.load("dwmapi", Dwm::class.java).DwmSetWindowAttribute(HWND(pointer), 20, IntByReference(1), 4)
                    log(data.resolve("ui-events.log"), "DWM darkTitleHresult=$result displayScale=${window.graphicsConfiguration.defaultTransform.scaleX}")
                }.onFailure { log(data.resolve("ui-events.log"), "DWM_ERROR $it") }
                onDispose { }
            }
            Row(Modifier.fillMaxSize().background(paper)) {
                Column(Modifier.width(190.dp).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    BasicText("ErgouCompress", style = TextStyle(color = textColor, fontSize = 20.sp))
                    BasicText("新建任务", style = TextStyle(color = blue, fontSize = 16.sp))
                    BasicText("任务队列"); BasicText("历史记录"); BasicText("设置")
                }
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    BasicText("开发前验证", style = TextStyle(color = textColor, fontSize = 28.sp))
                    BasicText("便携数据目录：$data", style = TextStyle(color = textColor, fontSize = 13.sp))
                    Box(Modifier.fillMaxWidth().height(160.dp).background(Color.White)
                        .border(if (dragging) 2.dp else 1.dp, if (dragging) blue else Color.LightGray)
                        .dragAndDropTarget(shouldStartDragAndDrop = { event ->
                            val accepted = event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                            log(data.resolve("ui-events.log"), "DRAG_CANDIDATE accepted=$accepted action=${event.action} flavors=${event.awtTransferable.transferDataFlavors.joinToString()}")
                            accepted
                        }, target = receiver).padding(20.dp)) {
                        BasicText(dropText, style = TextStyle(color = textColor, fontSize = 14.sp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("输出帧率（FPS）", style = TextStyle(color = textColor, fontSize = 14.sp))
                        Tip("输出帧率", "每秒输出的画面数。保持源帧率可避免不必要的丢帧；30 fps 不等于无损，转封装时不能更改。")
                        BasicTextField(fps, { fps = it; log(data.resolve("ui-events.log"), "FPS $it") },
                            modifier = Modifier.width(100.dp).background(Color.White).border(1.dp, Color.Gray).padding(8.dp)
                                .semantics { contentDescription = "输出帧率输入框" },
                            textStyle = TextStyle(color = textColor, fontSize = 14.sp), singleLine = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BasicText("分辨率：保持源画面", style = TextStyle(color = textColor, fontSize = 14.sp))
                        Tip("分辨率", "分辨率影响清晰度与体积。缩小需重编码；默认不放大，宽高需满足编码器对齐要求。")
                    }
                    BasicText("SQLDelight、SQLite JDBC、JNA 与便携路径自检通过。此窗口只是技术样机。", style = TextStyle(color = textColor, fontSize = 13.sp))
                }
            }
        }
    }
}
