import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("app.cash.sqldelight") version "2.4.1"
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("app.cash.sqldelight:sqlite-driver:2.4.1")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-test:1.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
sqldelight { databases { create("PreflightDatabase") { packageName.set("preflight.db") } } }
compose.desktop {
    application {
        mainClass = "preflight.MainKt"
        jvmArgs += "-Dergou.app.home=${projectDir.resolve("portable-dev").absolutePath}"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "ErgouPreflight"
            packageVersion = "0.1.0"
            modules("java.sql", "java.desktop", "java.management", "jdk.accessibility", "jdk.unsupported")
            windows { dirChooser = true; perUserInstall = true; menu = false; shortcut = false }
        }
    }
}
tasks.register<JavaExec>("selfTest") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("preflight.MainKt")
    args("--self-test")
    jvmArgs("-Dergou.app.home=${projectDir.resolve("portable-dev").absolutePath}")
}
dependencyLocking { lockAllConfigurations() }

tasks.register<JavaExec>("dropEventAcceptance") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("preflight.MainKt")
    args("--drop-event-acceptance", projectDir.parentFile.resolve("work/s0-acceptance/drop-event").absolutePath,
        projectDir.parentFile.resolve("reports/drop-event-acceptance.json").absolutePath)
    jvmArgs("-Dergou.app.home=${projectDir.resolve("portable-dev").absolutePath}")
}
tasks.register<JavaExec>("commitAcceptance") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("preflight.MainKt")
    args("--commit-acceptance", projectDir.parentFile.resolve("work/s0-acceptance/commit-jvm").absolutePath,
        projectDir.parentFile.resolve("reports/commit-jvm-acceptance.json").absolutePath)
    jvmArgs("-Dergou.app.home=${projectDir.resolve("portable-dev").absolutePath}")
}
