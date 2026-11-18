// Curves and grading (Stage 7): point curves, parametric curve, levels, colour grading
// (split toning is its two-wheel preset), colour balance, selective colour, channel
// mixer, black & white, photo filter, gradient map, calibration.
//
// The formulas are those of tools/golden/reference.py and of the GLSL in grade_ops.cpp;
// all three change together. Tone ops work on transfer-encoded values (sRGB curve
// extended by symmetry); curves are Fritsch-Carlson monotone cubics sampled into
// 4096-entry LUTs read with linear interpolation and extended with slope 1 outside 0..1.
// Nothing clips.
#pragma once

#include <array>
#include <cstddef>
#include <memory>
#include <utility>
#include <vector>

#include "lineover/ops.h"

namespace lo {

inline constexpr int kLutSize = 4096;

using CurvePoints = std::vector<std::pair<float, float>>;  // (x, y), x increasing, 0..1

struct CurveSet {
    CurvePoints rgb, red, green, blue, luma;  // empty = identity
};

struct GradeWheel {
    float hue = 0.f;         // degrees (OKLCh)
    float saturation = 0.f;  // 0..100
    float luminance = 0.f;   // -100..100
};

std::unique_ptr<Op> makeCurves(const CurveSet& curves);
std::unique_ptr<Op> makeParametricCurve(float highlights, float lights, float darks, float shadows, float split1, float split2,
                                        float split3);
// 4 rows (master, red, green, blue) x (in black, gamma, in white, out black, out white).
std::unique_ptr<Op> makeLevels(const std::array<float, 20>& values);
std::unique_ptr<Op> makeColorGrading(const GradeWheel& shadows, const GradeWheel& midtones, const GradeWheel& highlights,
                                     const GradeWheel& global, float blending, float balance);
std::unique_ptr<Op> makeColorBalance(const std::array<float, 3>& shadows, const std::array<float, 3>& midtones,
                                     const std::array<float, 3>& highlights, bool preserveLuminosity);
std::unique_ptr<Op> makeSelectiveColor(const std::array<float, 36>& values, bool relative);
std::unique_ptr<Op> makeChannelMixer(const std::array<float, 9>& matrixPercent, const std::array<float, 3>& constantPercent,
                                     bool monochrome);
std::unique_ptr<Op> makeBlackAndWhite(const std::array<float, 6>& mix, float tintHue, float tintSaturation);
std::unique_ptr<Op> makePhotoFilter(float hue, float saturation, float density, bool preserveLuminosity);
// Stops: (position, OKLab L, a, b) each.
std::unique_ptr<Op> makeGradientMap(const std::vector<std::array<float, 4>>& stops);
std::unique_ptr<Op> makeCalibration(const std::array<float, 3>& hue, const std::array<float, 3>& saturation);

namespace grade {

// The monotone cubic through `points` sampled at x = i / 4095 (flat outside the points).
// Monotone data gives a monotone table (Fritsch-Carlson).
void curveLut(const CurvePoints& points, float out[kLutSize]);

// The 9 points of the parametric curve (region centres moved within their region).
CurvePoints parametricPoints(float highlights, float lights, float darks, float shadows, float split1, float split2,
                             float split3);

// Working-space 3x3 of Calibration, row-major.
void calibrationMatrix(const std::array<float, 3>& hue, const std::array<float, 3>& saturation, double out[9]);

// Per-channel percentiles of the encoded values (Levels → Auto): out = (low R, high R,
// low G, high G, low B, high B), from every `stride`-th pixel.
void channelPercentiles(const float* rgba, std::size_t pixels, float low, float high, float out[6], std::size_t stride = 1);

}  // namespace grade
}  // namespace lo
