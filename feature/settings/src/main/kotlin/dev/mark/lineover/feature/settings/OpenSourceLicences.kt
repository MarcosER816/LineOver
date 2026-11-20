package dev.mark.lineover.feature.settings

/** A third-party component shipped in the app, for About → licences. */
data class OpenSourceLicence(
    val name: String,
    val licence: String,
    val url: String,
)

/**
 * Everything bundled in the APK (test-only libraries are not listed). Keep in sync with
 * the dependency tables of the stage notes (rule R10).
 */
val OpenSourceLicences =
    listOf(
        OpenSourceLicence(
            "AndroidX (Activity, Core, Lifecycle, Navigation 3, Room, SplashScreen, Window)",
            "Apache-2.0",
            "https://developer.android.com/jetpack/androidx",
        ),
        OpenSourceLicence("Jetpack Compose and Material 3", "Apache-2.0", "https://developer.android.com/compose"),
        OpenSourceLicence("Kotlin standard library", "Apache-2.0", "https://kotlinlang.org"),
        OpenSourceLicence("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        OpenSourceLicence("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
        OpenSourceLicence("Roboto Flex", "SIL Open Font License 1.1", "https://github.com/googlefonts/roboto-flex"),
        OpenSourceLicence("Material Symbols", "Apache-2.0", "https://github.com/google/material-design-icons"),
        // Stage 16: the on-device ML runtimes (models are downloaded, never bundled: Settings → AI models).
        OpenSourceLicence(
            "LiteRT (Google AI Edge), with its GPU and Qualcomm NPU plugins",
            "Apache-2.0",
            "https://github.com/google-ai-edge/LiteRT",
        ),
        OpenSourceLicence("MediaPipe Tasks Vision", "Apache-2.0", "https://github.com/google-ai-edge/mediapipe"),
        OpenSourceLicence(
            "ML Kit Subject Segmentation (Google Play services)",
            "ML Kit Terms of Service",
            "https://developers.google.com/ml-kit/terms",
        ),
    )
