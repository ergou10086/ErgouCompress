package org.ergoutree.compress

import org.ergoutree.compress.domain.LocalPath
import org.ergoutree.compress.platform.windows.WindowsProcessLauncher
import org.ergoutree.compress.ports.ProcessRequest

/** Intentionally bypasses finally, proving OS-owned Job cleanup after owner process termination. */
object NativeJobCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val process = WindowsProcessLauncher().launch(ProcessRequest(ProcessChild.javaCommand("pid-sleep"), LocalPath(args.single())))
        val pid = process.stdout.bufferedReader().readLine()
        println(pid); System.out.flush()
        Runtime.getRuntime().halt(83)
    }
}
