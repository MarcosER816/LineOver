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
}
