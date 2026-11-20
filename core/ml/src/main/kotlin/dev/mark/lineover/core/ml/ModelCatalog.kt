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

    /**
     * Depth Anything V2 Small (Stage 17's Depth range mask): relative depth at 518 x 518, float32.
     * The weights are Apache-2.0 (the upstream README: "Depth-Anything-V2-Small model is under the
     * Apache-2.0 license"; the Hugging Face card says apache-2.0); this LiteRT conversion is
     * Qualcomm AI Hub's (release v0.67.0, its card says MIT). The zip holds one .tflite: the
     * store verifies the zip, unpacks that entry, verifies it and deletes the zip.
     */
    val DEPTH =
        ModelSpec(
            id = "depth_anything_v2_small",
            displayName = "Depth Anything V2 Small (depth)",
            downloadUrl =
                "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/" +
                    "depth_anything_v2/releases/v0.67.0/depth_anything_v2-tflite-float.zip",
            sizeBytes = 91_862_770L,
            license = "Apache License 2.0",
            sha256 = "d3d910013a1e0a3442cda6c26a6a310b700da8f5562d3f620bca60ae48cd1d32",
            licenseUrl = "https://github.com/DepthAnything/Depth-Anything-V2#license",
            fileName = "depth_anything_v2_small.tflite",
            usedBy = "Depth range mask",
            archiveEntry = "depth_anything_v2-tflite-float/depth_anything_v2.tflite",
            entrySize = 98_917_860L,
            entrySha256 = "5cdbc0346c854ddb7033c25b2831dd3ad70398c0148e9c0a70ec30e1786ef03d",
        )

    val all: List<ModelSpec> = listOf(MAGIC_TOUCH, SKY, DEPTH)

    operator fun get(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
