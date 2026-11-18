// Colour pipeline of the engine.
//
// Working space: linear Rec.2020, D65, straight (un-premultiplied) alpha, values may
// exceed 1.0. Sources are converted into it on decode; the display transform
// converts out of it to Display P3 or sRGB.
//
// All buffers are interleaved RGBA float unless stated otherwise; alpha is never
// touched by a colour conversion.
#pragma once

#include <cstddef>
#include <cstdint>

namespace lo::color {

// A row-major 3x3 matrix acting on column vectors (rgb' = M * rgb).
struct Mat3 {
    float m[9];
};

// RGB-to-RGB matrices between linear spaces. All spaces share the D65 white point,
// so no chromatic adaptation is involved. Values computed with colour-science 0.4.7
// (matrix_RGB_to_RGB) and checked against it by color_test.
extern const Mat3 kSrgbToRec2020;
extern const Mat3 kRec2020ToSrgb;
extern const Mat3 kP3ToRec2020;
extern const Mat3 kRec2020ToP3;

// The sRGB transfer function (IEC 61966-2-1), also used by Display P3. Extended to
// negative values by symmetry (as Android's EXTENDED_SRGB), values > 1 continue the curve.
float srgbEotf(float encoded);  // encoded -> linear
float srgbOetf(float linear);   // linear -> encoded

// rgb' = M * rgb for `pixels` RGBA pixels; in == out is allowed.
void applyMatrix(const Mat3& matrix, const float* in, float* out, std::size_t pixels);

// Sources -----------------------------------------------------------------------

// 8-bit sRGB-encoded RGBA (straight alpha) -> working space.
void srgb8ToWorking(const std::uint8_t* rgba8, float* out, std::size_t pixels);

// 8-bit Display-P3-encoded RGBA -> working space.
void p38ToWorking(const std::uint8_t* rgba8, float* out, std::size_t pixels);

// Half-float RGBA in Android's LINEAR_EXTENDED_SRGB (linear, sRGB primaries, values
// outside 0..1 carry wide-gamut and HDR colours) -> working space.
void linearExtendedSrgbHalfToWorking(const std::uint16_t* rgba16, float* out, std::size_t pixels);

// Display -----------------------------------------------------------------------

enum class Display : int {
    Srgb = 0,
    DisplayP3 = 1,
};

// Working space -> encoded display values in 0..1 (clipped to the display gamut).
void workingToDisplay(const float* in, float* out, std::size_t pixels, Display display);

// Working space -> 8-bit encoded RGBA for a display (rounded, alpha clipped to 0..255).
void workingToDisplay8(const float* in, std::uint8_t* out, std::size_t pixels, Display display);

// Half floats (IEEE 754 binary16), portable and NEON-free.
std::uint16_t floatToHalf(float value);
float halfToFloat(std::uint16_t half);

}  // namespace lo::color
