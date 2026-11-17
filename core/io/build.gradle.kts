plugins {
    id("lineover.android.library")
    id("lineover.android.room")
}

android {
    namespace = "dev.mark.lineover.core.io"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
