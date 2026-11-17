plugins {
    id("lineover.android.feature")
}

android {
    namespace = "dev.mark.lineover.feature.home"
}

dependencies {
    implementation(project(":core:io"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    // Reachability tests of the reserved slots (Stage 3).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
