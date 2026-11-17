plugins {
    id("lineover.android.library")
    id("lineover.android.compose")
    id("lineover.roborazzi")
}

android {
    namespace = "dev.mark.lineover.core.designsystem"
}

dependencies {
    // LoToastState suspends until the toast is acted on or times out.
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
