plugins {
    id("lineover.android.library")
}

android {
    namespace = "dev.mark.lineover.core.ml"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    // Stage 16 (licences in docs/stages/STAGE_16.md). LiteRT runs the sky model with its
    // CompiledModel API (NPU -> GPU -> CPU); the Qualcomm NPU plugin is Apache-2.0 and loads
    // Qualcomm's own QNN libraries only where the device provides them (none are bundled).
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.npu.qualcomm)
    // MediaPipe's Interactive Segmenter runs MagicTouch V2. litertlm (an LLM runtime, 22 MB of
    // native code) is only used by MediaPipe's image embedder, which LineOver does not use.
    implementation(libs.mediapipe.tasks.vision) {
        exclude(group = "com.google.ai.edge.litertlm")
    }
    // ML Kit Subject Segmentation: the model is a Google Play services module downloaded on
    // first use (ModuleInstallClient), never in the APK.
    implementation(libs.mlkit.subject.segmentation)
    testImplementation(libs.kotlinx.coroutines.test)
}
