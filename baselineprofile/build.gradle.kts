plugins {
    id("lineover.android.test")
    id("lineover.android.baselineprofile")
}

android {
    namespace = "dev.mark.lineover.baselineprofile"
    targetProjectPath = ":app"
}

// Profiles are generated on a physical device or a rooted emulator with
//   ./gradlew :app:generateReleaseBaselineProfile
// They are never generated as part of a normal build.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
