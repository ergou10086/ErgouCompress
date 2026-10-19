package org.ergoutree.compress.infrastructure.process

import org.ergoutree.compress.domain.DesktopPreferences
import org.ergoutree.compress.ports.*

/** 详细日志只作用于正式编码进程，不改变探测 JSON 或验证器的错误级别。 */
class PreferenceProcessRunner(private val delegate: ProcessRunner, private val preferences: () -> DesktopPreferences) : ProcessRunner {
    override suspend fun run(request: ProcessRequest, stop: StopSignal, onStdout: (String) -> Unit): ProcessResult {
        val verbose = preferences().ffmpegVerbose
        val actual = if (verbose && request.allowQuit) request.copy(argv = request.argv.take(1) + listOf("-loglevel", "verbose") + request.argv.drop(1)) else request
        return delegate.run(actual, stop, onStdout)
    }
}
