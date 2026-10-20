import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":sharedUI"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
    implementation(libs.jna.platform)
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "org.ergoutree.compress.project.MainKt"
        // Compose 的 run 任务不是普通 JavaExec，开发数据根必须在这里显式传入。
        jvmArgs += listOf("-Dergou.app.home=${rootDir.absolutePath}")

        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "ErgouCompress"
            packageVersion = "0.1.0"
            modules("java.sql", "java.desktop", "jdk.accessibility", "jdk.unsupported")
            windows { perUserInstall = true; dirChooser = true }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dergou.app.home=${rootDir.absolutePath}")
}

tasks.withType<Test>().configureEach {
    systemProperty("ergou.test.runtimeClasspath", sourceSets["test"].runtimeClasspath.asPath)
    if (providers.gradleProperty("mediaTests").orNull != "true") exclude("**/MediaIntegrationTest*")
    environment("ERGOU_TEST_FFMPEG", providers.environmentVariable("ERGOU_TEST_FFMPEG").orNull ?: "")
    environment("ERGOU_TEST_FFPROBE", providers.environmentVariable("ERGOU_TEST_FFPROBE").orNull ?: "")
    jvmArgs("-Dergou.app.home=${rootDir.absolutePath}")
}
