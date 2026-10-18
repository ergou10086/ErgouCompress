plugins {
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    // 统一声明插件版本，避免子项目重复加载不同插件类加载器。
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

subprojects {
    dependencyLocking { lockAllConfigurations() }
    tasks.withType<Test>().configureEach {
        providers.gradleProperty("ergouTestTemp").orNull?.let { base ->
            val owned = file(base).resolve(project.name)
            // 保留系统临时卷用于真实跨卷测试，但将该轮文件集中到可核对的独占根。
            systemProperty("java.io.tmpdir", owned.absolutePath)
            doFirst { check(owned.mkdirs() || owned.isDirectory) }
        }
    }
}
