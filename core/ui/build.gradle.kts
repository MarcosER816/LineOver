plugins {
    id("lineover.android.library")
    id("lineover.android.compose")
}

android {
    namespace = "dev.mark.lineover.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    api(libs.androidx.lifecycle.viewmodel)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // WindowSizeClass breakpoints (material3-adaptive needs compileSdk 37, see STAGE_02).
    implementation(libs.androidx.window.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
