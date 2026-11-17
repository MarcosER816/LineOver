plugins {
    id("lineover.android.feature")
}

android {
    namespace = "dev.mark.lineover.feature.export"
}

dependencies {
    implementation(project(":core:io"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
