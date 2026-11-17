plugins {
    id("lineover.android.feature")
}

android {
    namespace = "dev.mark.lineover.feature.settings"
}

dependencies {
    implementation(project(":core:ml"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
