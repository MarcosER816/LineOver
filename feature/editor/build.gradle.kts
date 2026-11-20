plugins {
    id("lineover.android.feature")
}

android {
    namespace = "dev.mark.lineover.feature.editor"
}

dependencies {
    implementation(project(":core:io"))
    // The image engine (native render thread, tiles, GPU ops).
    implementation(project(":core:engine"))
    // Smart selections (Stage 16): the segmentation models and their download manager.
    implementation(project(":core:ml"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // BackHandler (already in the app through :app).
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)
    // GoldenAssert / NpyIo: the UI display transform against the reference.
    testImplementation(testFixtures(project(":core:engine")))
    // Reachability and recomposition tests drive the real editor on the JVM (Robolectric).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Frame timing of the GL canvas on a device or emulator.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
