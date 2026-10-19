package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

/** 桌面外观模式，系统主题由平台读取，不向通用层引入注册表。 */
@Serializable enum class AppTheme { SYSTEM, LIGHT, DARK }
/** 日志筛选与编码器详细程度独立，不因隐藏日志而丢失审计事件。 */
@Serializable enum class LogLevel { DEBUG, INFO, WARNING, ERROR }
/** 工具级偏好只影响新任务，既有任务始终使用冻结快照。 */
@Serializable data class DesktopPreferences(
    val schemaVersion: Int = 1,
    val theme: AppTheme = AppTheme.SYSTEM,
    val reduceMotion: Boolean = false,
    val scanDepth: Int? = 4,
    val minimumBytes: Long = 0,
    val outputDirectory: String = "",
    val defaults: EncodeSettings = EncodeSettings(),
    val outputMode: OutputMode = OutputMode.COPY,
    val onlyIfSmaller: Boolean = true,
    val logLevel: LogLevel = LogLevel.INFO,
    val ffmpegVerbose: Boolean = false,
    val logBufferLines: Int = 1500,
) {
    init {
        require(schemaVersion == 1)
        require(scanDepth == null || scanDepth in 0..100)
        require(minimumBytes >= 0 && logBufferLines in 200..5000)
    }
}
/** 记录导入来源和未选原因，使一次任务的输入审核过程可以追溯。 */
@Serializable data class TaskImportAudit(val roots: List<String>, val entries: List<ImportAuditEntry>)
/** 只保存路径和选择结果，不复制用户媒体到档案。 */
@Serializable data class ImportAuditEntry(val path: LocalPath, val selected: Boolean, val reason: String?)
/** 增量日志游标绑定任务，不能把另一任务的偏移直接拿来续读。 */
data class TaskLogCursor(val jobId: String, val eventSequence: Long = 0, val offsets: Map<String, Long> = emptyMap())
/** 一条日志保留来源、级别和稳定标识，便于虚拟列表与筛选。 */
data class TaskLogLine(val id: String, val source: String, val level: LogLevel, val text: String, val timeMillis: Long? = null)
/** 日志读取有界，后续轮询从已消费偏移继续，不反复读取整个文件。 */
data class TaskLogPage(val cursor: TaskLogCursor, val lines: List<TaskLogLine>)
