// The Color basics (Stage 6): White balance, Vibrance, Saturation, HSL / Color Mixer,
// Invert, Posterize, Threshold, plus the eyedropper and targeted-adjustment helpers.
//
// The formulas are those of tools/golden/reference.py and of the GLSL bodies in
// color_ops.cpp; all three change together.
//  * White balance: Bradford chromatic adaptation in XYZ from the scene white of
//    (T, tint) to D65. The scene white is D65 moved by the Planckian-locus offset of T
//    from 6504 K (Krystek 1985) and by tint along the locus normal (+ = greener scene, so
//    the image turns magenta). 6504 K / tint 0 is exactly no change.
//  * Vibrance, Saturation and HSL work in OKLCh (Rec.2020 -> XYZ -> OKLab), never in
//    sRGB gamma; HSL bands use raised-cosine weights that sum to 1, and every hue-based
//    effect fades in from grey by chroma (no hue jumps near grey).
//  * Invert, Posterize, Threshold work on transfer-encoded values (sRGB curve extended by
//    symmetry). Nothing is clipped anywhere: out-of-gamut and HDR values pass through.
#pragma once

#include <array>
#include <memory>

#include "lineover/ops.h"

namespace lo {

inline constexpr float kWbDefaultKelvin = 6504.0f;
inline constexpr float kWbMinKelvin = 2000.0f;
inline constexpr float kWbMaxKelvin = 50000.0f;
inline constexpr float kWbTintRange = 150.0f;
inline constexpr int kHslBands = 8;

using HslBands = std::array<float, kHslBands>;

std::unique_ptr<Op> makeWhiteBalance(float kelvin, float tint);
std::unique_ptr<Op> makeVibrance(float amount);    // [-100, 100]
std::unique_ptr<Op> makeSaturation(float amount);  // [-100, 100]
std::unique_ptr<Op> makeHslMixer(const HslBands& hue, const HslBands& saturation, const HslBands& luminance);
std::unique_ptr<Op> makeInvert();
std::unique_ptr<Op> makePosterize(float levels);  // 2..32; < 2 = no change
std::unique_ptr<Op> makeThreshold(float level);   // 0..1 of encoded luminance

namespace colorops {

// OKLCh hues (degrees) of the band centres: red, orange, yellow, green, aqua, blue,
// purple, magenta (the OKLCh hues of saturated sRGB colours at HSV 0, 30, 60, 120, 180,
// 240, 270, 300 degrees).
inline constexpr float kHslCentres[kHslBands] = {29.2f, 52.8f, 109.8f, 142.5f, 194.8f, 264.1f, 293.8f, 328.4f};

// The working-space (linear Rec.2020) matrix of white balance (T, tint), row-major.
void whiteBalanceMatrix(double kelvin, double tint, double out[9]);

// (T, tint) that turns `rgb` (working space) neutral, clamped to the slider ranges.
// False for colours without a chromaticity (black, negative luminance).
bool solveNeutral(const float rgb[3], float* kelvin, float* tint);

// Raised-cosine weights of the 8 bands at OKLCh hue `hueDegrees`; they sum to 1.
void hslWeights(float hueDegrees, float weights[kHslBands]);

// OKLab <-> working space.
void toOklab(const float rgb[3], float lab[3]);
void fromOklab(const float lab[3], float rgb[3]);

// What the targeted adjustment acts on under the finger: the band weights of `rgb`'s
// hue, faded by chroma like the mixer (all zero for grey).
void targetedWeights(const float rgb[3], float weights[kHslBands]);

}  // namespace colorops
}  // namespace lo
