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
    // EXIF orientation for tiles (BitmapRegionDecoder does not apply it).
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
