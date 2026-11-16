plugins {
    id("lineover.android.test")
}

// Macrobenchmarks run against :app's benchmarkRelease variant (created by the
// androidx.baselineprofile plugin) on a physical device only:
//   ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
// The CI emulator uses SwiftShader, which is for correctness, never performance.
android {
    namespace = "dev.mark.lineover.benchmark"
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmarkRelease") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmarkRelease"
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
