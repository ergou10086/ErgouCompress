plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Hardware/media tests are explicit so the ordinary suite remains deterministic.
tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("mediaTests").orNull != "true") exclude("**/MediaIntegrationTest*")
    environment("ERGOU_TEST_FFMPEG", providers.environmentVariable("ERGOU_TEST_FFMPEG").orNull ?: "")
    environment("ERGOU_TEST_FFPROBE", providers.environmentVariable("ERGOU_TEST_FFPROBE").orNull ?: "")
}
