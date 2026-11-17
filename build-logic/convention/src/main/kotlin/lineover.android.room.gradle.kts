import com.android.build.api.dsl.LibraryExtension
import dev.mark.lineover.buildlogic.lib
import dev.mark.lineover.buildlogic.libs

// Room with KSP. The schema of every database version is exported to schemas/ and
// committed: migration tests are built from those files (rule R4: never edit them).
plugins {
    id("com.google.devtools.ksp")
    id("androidx.room")
}

room {
    schemaDirectory("$projectDir/schemas")
}

extensions.configure<LibraryExtension>("android") {
    // MigrationTestHelper reads the exported schemas as assets, on the JVM (Robolectric)
    // and on a device.
    sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

dependencies {
    "implementation"(libs.lib("androidx-room-runtime"))
    "implementation"(libs.lib("androidx-room-ktx"))
    "ksp"(libs.lib("androidx-room-compiler"))
    "testImplementation"(libs.lib("androidx-room-testing"))
}
