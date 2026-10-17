package org.ergoutree.compress

/** Deterministic, private test subprocess. No shell, media or user files. */
object ProcessChild {
    @JvmStatic fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "flood" -> repeat(20000) { println("out-$it"); System.err.println("err-$it") }
            "quit" -> { println("ready"); System.out.flush(); readlnOrNull(); println("quit accepted") }
            "sleep" -> Thread.sleep(60_000)
            "pid-sleep" -> { println(ProcessHandle.current().pid()); System.out.flush(); Thread.sleep(60_000) }
            "argv" -> args.drop(1).forEach { println("ARG:" + java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))) }
            "spawn" -> {
                val p = ProcessBuilder(javaCommand("sleep")).start()
                println(p.pid()); System.out.flush(); Thread.sleep(60_000)
            }
        }
    }
    fun javaCommand(vararg args: String): List<String> {
        val executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val classpath = listOf(ProcessChild::class.java, kotlin.Unit::class.java).map { java.nio.file.Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.joinToString(java.io.File.pathSeparator)
        return listOf(executable, "-cp", classpath, ProcessChild::class.java.name) + args
    }
}
