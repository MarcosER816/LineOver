plugins {
    id("lineover.android.library")
}

android {
    namespace = "dev.mark.lineover.core.engine"

    // GoldenAssert and the PNG / NPY readers, shared by unit and instrumented
    // tests of this and other modules: testImplementation(testFixtures(project(":core:engine"))).
    testFixtures {
        enable = true
    }

    sourceSets {
        // Instrumented tests read the golden reference files as assets.
        getByName("androidTest") {
            assets.directories.add(rootProject.file("testdata/golden").path)
        }
    }
}

dependencies {
    // Provides liblineover_engine.so.
    implementation(project(":core:engine-native"))
    api(project(":core:io"))
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
