plugins {
    id("lineover.android.feature")
}

android {
    namespace = "dev.mark.lineover.feature.export"
    sourceSets {
        // The round-trip tests export one of Mark's Ultra HDR photos.
        getByName("androidTest") {
            assets.directories.add(rootProject.file("testdata/photos").path)
        }
    }
}

dependencies {
    implementation(project(":core:io"))
    // Full-resolution render and the native PNG / TIFF writers (Stage 12).
    implementation(project(":core:engine"))
    // HEIF and AVIF encoders (platform codecs).
    implementation(libs.androidx.heifwriter)
    // File pickers (save to a chosen place, logo) and the notification permission.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // Reachability tests of the reserved slots (Stage 3).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Export round trips on the emulator (Stage 12).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
