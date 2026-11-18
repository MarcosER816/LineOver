// The Light tools (Stage 5): Exposure, Contrast, Brightness, Whites, Blacks,
// Highlights / Shadows, plus the image analysis they need (guided-filter base of log
// luminance, luminance percentiles for Auto).
//
// The formulas are those of tools/golden/reference.py (the Python reference) and of
// the GLSL bodies in light.cpp; all three must change together. Every op works on the
// Rec.2020 luminance Y and scales RGB by Y'/Y (chroma ratios preserved, alpha kept);
// pixels with Y <= 1e-6 are unchanged. Values are never clipped (HDR stays HDR).
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

#include "lineover/ops.h"

namespace lo {

namespace light {
inline constexpr float kLumaR = 0.2627f, kLumaG = 0.6780f, kLumaB = 0.0593f;
inline constexpr float kLumaEps = 1e-6f;
inline constexpr float kMidGrey = 0.18f;
inline constexpr float kContrastStrength = 0.75f;
inline constexpr float kContrastWidth = 2.5f;  // stops
inline constexpr float kWhitePoint = 1.0f;
inline constexpr float kBlackPoint = 0.002f;
inline constexpr float kGuidedRadiusFraction = 0.015f;  // of the long edge
inline constexpr double kGuidedEps = 0.01;
inline constexpr float kToneStops = 1.5f;  // Highlights / Shadows at +-100
inline constexpr float kHighlightRange = 2.5f;
inline constexpr float kShadowRange = 5.0f;
inline constexpr float kExposureRange = 5.0f;
inline constexpr float kAmountRange = 100.0f;

inline float luminance(const float* rgb) { return kLumaR * rgb[0] + kLumaG * rgb[1] + kLumaB * rgb[2]; }

// The fixed order the editor applies them in (ids as in OpType).
//   HighlightsShadows -> Exposure -> Whites -> Blacks -> Contrast -> Brightness
}  // namespace light

std::unique_ptr<Op> makeExposure(float ev);                                // ev in [-5, 5]
std::unique_ptr<Op> makeContrast(float amount);                            // [-100, 100]
std::unique_ptr<Op> makeBrightness(float amount);                          // [-100, 100]
std::unique_ptr<Op> makeWhites(float amount);                              // [-100, 100]
std::unique_ptr<Op> makeBlacks(float amount);                              // [-100, 100]
std::unique_ptr<Op> makeHighlightsShadows(float highlights, float shadows);  // [-100, 100]

// Radius of the guided filter for an image of width x height: 1.5 % of the long edge.
int guidedRadius(int width, int height);

// Self-guided filter (He et al.) of L = log2(max(Y, 1e-6)) of `rgba` (working space):
// a = var / (var + 0.01), b = mean - a * mean over clipped (2r+1)^2 windows, then
// A = box(a), B = box(b). Sums in double; O(pixels), independent of the radius.
GuidedCoefficients guidedCoefficients(const float* rgba, int width, int height);

// Luminance percentiles of an image (fractions 0..1, e.g. 0.005 and 0.995), from a
// sorted copy of the luminance of every `stride`-th pixel.
void luminancePercentiles(const float* rgba, std::size_t pixels, float low, float high, float* lowValue,
                          float* highValue, std::size_t stride = 1);

// Auto (Stage 5: a percentile stretch; Stage 11 improves it). Exposure maps the high
// percentile to 1.0 and Blacks then moves the low percentile to the black point 0.002.
// Results are clamped to the slider ranges.
struct AutoLight {
    float exposure = 0.f;
    float blacks = 0.f;
};
AutoLight autoLight(float lowValue, float highValue);

// 256-bin histograms of the sRGB display values of `rgba` (working space) and of luminance:
// clip(Rec.2020 -> sRGB) then the sRGB transfer; L = transfer(clip(Y)); bin =
// min(floor(v * 256), 255). `out` gets 1024 counts, channel-major (R, G, B, L).
// The reference of the GPU compute shader (and the path on GPUs without compute).
void histogramSrgb(const float* rgba, std::size_t pixels, std::uint32_t* out);

}  // namespace lo
