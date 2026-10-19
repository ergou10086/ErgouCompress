plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()

    jvmToolchain(21)

    sourceSets {
        commonTest {
            // 本地开发脚本不属于运行时夹具，禁止复制历史验证目录到测试类路径。
            resources.exclude("development-scripts/**")
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
    }
}

sqldelight { databases { create("CatalogDatabase") { packageName.set("org.ergoutree.compress.catalog") } } }

// Hardware/media tests are explicit so the ordinary suite remains deterministic.
tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("mediaTests").orNull != "true") exclude("**/MediaIntegrationTest*")
    environment("ERGOU_TEST_FFMPEG", providers.environmentVariable("ERGOU_TEST_FFMPEG").orNull ?: "")
    environment("ERGOU_TEST_FFPROBE", providers.environmentVariable("ERGOU_TEST_FFPROBE").orNull ?: "")
}
