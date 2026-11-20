package dev.mark.lineover.core.ml

/**
 * The models LineOver downloads (Stage 16). Sizes and SHA-256 were measured on the files these
 * URLs served on 2026-11-20; a different file fails the check and is deleted.
 */
object ModelCatalog {
    /**
     * MagicTouch V2 (Google, Apache-2.0: the licence in the .task's manifest, and the MediaPipe
     * maintainers' answer in google-ai-edge/mediapipe#6400): interactive segmentation from taps
     * and scribbles, an int8 encoder + decoder at 768 x 768.
     */
    val MAGIC_TOUCH =
        ModelSpec(
            id = "magic_touch_v2",
            displayName = "MagicTouch V2 (interactive segmentation)",
            downloadUrl =
                "https://storage.googleapis.com/mediapipe-models/" +
                    "interactive_segmenter_v2/magic_touch/int8/1/interactive_segmentation.task",
            sizeBytes = 30_525_312L,
            license = "Apache License 2.0",
            sha256 = "38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            fileName = "magic_touch_v2.task",
            usedBy = "Object select",
        )

    /**
     * DeepLabV3+ (MobileNetV2) trained on ADE20K by MLCommons for MLPerf Mobile, 8-bit, 512 x 512,
     * 32 classes with sky = 3. Apache-2.0 as published on Zenodo (record 3966763, inside
     * deeplabv3_quant_and_eval.zip); this byte-identical copy is MLCommons' mobile_models
     * repository. MediaPipe's own DeepLab-v3 has the 21 Pascal VOC classes, without sky.
     */
    val SKY =
        ModelSpec(
            id = "sky_deeplabv3_ade20k",
            displayName = "DeepLabV3+ ADE20K (sky)",
            downloadUrl =
                "https://raw.githubusercontent.com/mlcommons/mobile_models/" +
                    "main/v0_7/tflite/deeplabv3_mnv2_ade20k_uint8.tflite",
            sizeBytes = 2_348_952L,
            license = "Apache License 2.0",
            sha256 = "2d58c681d81b5e181816b81cc815b82edf3daad905b4d7d35afade80aee93b5f",
            licenseUrl = "https://zenodo.org/record/3966763",
            fileName = "sky_deeplabv3_ade20k.tflite",
            usedBy = "Select sky",
        )

    val all: List<ModelSpec> = listOf(MAGIC_TOUCH, SKY)

    operator fun get(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
