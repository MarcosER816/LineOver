import dev.mark.lineover.buildlogic.lib
import dev.mark.lineover.buildlogic.libs

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    "implementation"(libs.lib("kotlinx-serialization-json"))
}
