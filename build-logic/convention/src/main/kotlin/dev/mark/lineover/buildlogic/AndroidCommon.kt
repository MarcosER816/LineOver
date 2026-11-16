package dev.mark.lineover.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/** Debug builds also carry x86_64 so they run on the CI emulator; release is arm64 only. */
val DEBUG_ABIS = listOf("arm64-v8a", "x86_64")
val RELEASE_ABIS = listOf("arm64-v8a")

/** Settings shared by every Android module (application, library and test). */
fun Project.configureAndroidCommon(android: CommonExtension) {
    android.apply {
        compileSdk = libs.version("compileSdk").toInt()

        defaultConfig.apply {
            minSdk = libs.version("minSdk").toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions.unitTests.isIncludeAndroidResources = true

        lint.apply {
            abortOnError = true
            checkDependencies = false
            // Version-bump hints are tracked per stage, not enforced on every build;
            // targetSdk 36 is a fixed decision (AGENTS.md R0 / D2).
            disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        }
    }

    // Golden image locations for JVM unit tests (see testdata/golden and GoldenAssert).
    val goldenDir = rootProject.layout.projectDirectory.dir("testdata/golden").asFile.absolutePath
    val goldenDiffDir = layout.buildDirectory.dir("outputs/golden-diffs").get().asFile.absolutePath
    tasks.withType(Test::class.java).configureEach {
        systemProperty("lineover.goldenDir", goldenDir)
        systemProperty("lineover.goldenDiffDir", goldenDiffDir)
    }
}
