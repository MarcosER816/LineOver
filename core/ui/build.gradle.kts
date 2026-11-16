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
    testImplementation(libs.kotlinx.coroutines.test)
}
