// Auto (Stage 11): slider values from image statistics, no learning. Gray-world white balance
// (the white balance that makes the mean of the midtones neutral), then percentile-based
// Exposure, Contrast, Whites and Blacks on the balanced luminance, and a mild Vibrance that
// fades out on already colourful photos. Plan B (a small learned model) is for later.
#pragma once

namespace lo {

struct AutoSettings {
    float temperature = 6504.f;  // Kelvin (the white balance op's)
    float tint = 0.f;
    float exposure = 0.f;  // EV
    float contrast = 0.f;  // -100..100 like the sliders
    float whites = 0.f;
    float blacks = 0.f;
    float vibrance = 0.f;
};

// Working-space RGBA (the analysis proxy of the preview), width x height. False when nothing
// usable is found (an empty or black image): the result is then the defaults.
bool autoSettings(const float* rgba, int width, int height, AutoSettings* out);

}  // namespace lo
