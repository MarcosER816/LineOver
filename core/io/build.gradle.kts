plugins {
    id("lineover.android.library")
    id("lineover.android.room")
    // manifest.json of .loproj project files.
    id("lineover.kotlin.serialization")
}

android {
    namespace = "dev.mark.lineover.core.io"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    // EXIF orientation for tiles (BitmapRegionDecoder does not apply it).
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
