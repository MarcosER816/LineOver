import dev.mark.lineover.buildlogic.lib
import dev.mark.lineover.buildlogic.libs

// Roborazzi screenshot tests (JVM, Robolectric native graphics).
// Record:  ./gradlew recordRoborazziDebug     Verify: ./gradlew verifyRoborazziDebug
// Recorded images are committed under src/test/screenshots.
plugins {
    id("io.github.takahirom.roborazzi")
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

dependencies {
    "testImplementation"(libs.lib("junit"))
    "testImplementation"(libs.lib("robolectric"))
    "testImplementation"(libs.lib("roborazzi"))
    "testImplementation"(libs.lib("roborazzi-compose"))
}
