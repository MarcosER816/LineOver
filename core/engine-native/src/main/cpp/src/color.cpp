#include "lineover/color.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "op_util.h"

namespace lo::color {

// colour.matrix_RGB_to_RGB(a, b, chromatic_adaptation_transform=None), colour-science 0.4.7.
const Mat3 kSrgbToRec2020 = {{0.62744137205797812f, 0.32929745952190931f, 0.043351458394494675f,
                              0.069027617147078099f, 0.91958066688702822f, 0.011361422575400862f,
                              0.016364235071680722f, 0.088017162471726973f, 0.89556497272598279f}};
const Mat3 kRec2020ToSrgb = {{1.6603034854214451f, -0.58757014253161666f, -0.072890060215055993f,
                              -0.12437559530838646f, 1.1328344814319582f, -0.008359737187401451f,
                              -0.018112279959916489f, -0.10058360850721247f, 1.1187703261570985f}};
const Mat3 kP3ToRec2020 = {{0.75383303436172144f, 0.19859736905261652f, 0.047569596585661907f,
                            0.045743848965358304f, 0.9417772198116936f, 0.012478931222948124f,
                            -0.0012103403545183934f, 0.017601717301089993f, 0.98360862305342822f}};
const Mat3 kRec2020ToP3 = {{1.3435782525843329f, -0.28217967052613602f, -0.061398582058196254f,
                            -0.065297452789119539f, 1.0757879158485744f, -0.010490463059454943f,
                            0.002821787261701058f, -0.019598494524494192f, 1.0167767072627931f}};

namespace {

constexpr float kLinearThreshold = 0.04045f;
constexpr float kEncodedThreshold = 0.0031308f;

float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

// 256-entry decode tables for 8-bit sources (exact to float precision).
struct Lut8 {
    float values[256];
    Lut8() {
        for (int i = 0; i < 256; ++i) values[i] = srgbEotf(static_cast<float>(i) / 255.0f);
    }
};

const Lut8& lut8() {
    static const Lut8 table;
    return table;
}

void decode8(const std::uint8_t* rgba8, float* out, std::size_t pixels) {
    const float* lut = lut8().values;
    for (std::size_t i = 0; i < pixels; ++i) {
        out[4 * i + 0] = lut[rgba8[4 * i + 0]];
        out[4 * i + 1] = lut[rgba8[4 * i + 1]];
        out[4 * i + 2] = lut[rgba8[4 * i + 2]];
        out[4 * i + 3] = static_cast<float>(rgba8[4 * i + 3]) / 255.0f;
    }
}

}  // namespace

float srgbEotf(float encoded) {
    const float a = std::fabs(encoded);
    const float linear = a <= kLinearThreshold ? a / 12.92f : std::pow((a + 0.055f) / 1.055f, 2.4f);
    return encoded < 0.0f ? -linear : linear;
}

float srgbOetf(float linear) {
    const float a = std::fabs(linear);
    const float encoded = a <= kEncodedThreshold ? a * 12.92f : 1.055f * std::pow(a, 1.0f / 2.4f) - 0.055f;
    return linear < 0.0f ? -encoded : encoded;
}

void applyMatrix(const Mat3& matrix, const float* in, float* out, std::size_t pixels) {
    const float* m = matrix.m;
    for (std::size_t i = 0; i < pixels; ++i) {
        const float r = in[4 * i + 0];
        const float g = in[4 * i + 1];
        const float b = in[4 * i + 2];
        out[4 * i + 0] = m[0] * r + m[1] * g + m[2] * b;
        out[4 * i + 1] = m[3] * r + m[4] * g + m[5] * b;
        out[4 * i + 2] = m[6] * r + m[7] * g + m[8] * b;
        out[4 * i + 3] = in[4 * i + 3];
    }
}

void srgb8ToWorking(const std::uint8_t* rgba8, float* out, std::size_t pixels) {
    decode8(rgba8, out, pixels);
    applyMatrix(kSrgbToRec2020, out, out, pixels);
}

void p38ToWorking(const std::uint8_t* rgba8, float* out, std::size_t pixels) {
    decode8(rgba8, out, pixels);
    applyMatrix(kP3ToRec2020, out, out, pixels);
}

void linearExtendedSrgbHalfToWorking(const std::uint16_t* rgba16, float* out, std::size_t pixels) {
    for (std::size_t i = 0; i < 4 * pixels; ++i) out[i] = halfToFloat(rgba16[i]);
    applyMatrix(kSrgbToRec2020, out, out, pixels);
}

namespace {

// reference.GAMUT_STEPS / GAMUT_EPS, and the GPU display pass (gpu_pipeline.cpp).
constexpr int kGamutSteps = 16;
constexpr double kGamutEps = 5e-4;

void toDisplayLinear(const Mat3& matrix, const double rgb[3], double out[3]) {
    const float* m = matrix.m;
    for (int r = 0; r < 3; ++r) out[r] = m[3 * r] * rgb[0] + m[3 * r + 1] * rgb[1] + m[3 * r + 2] * rgb[2];
}

bool insideGamut(const double d[3]) {
    for (int c = 0; c < 3; ++c) {
        if (!(d[c] >= -kGamutEps && d[c] <= 1.0 + kGamutEps)) return false;
    }
    return true;
}

}  // namespace

void gamutMap(const float* in, float* out, std::size_t pixels, Display display) {
    const Mat3& matrix = display == Display::DisplayP3 ? kRec2020ToP3 : kRec2020ToSrgb;
    for (std::size_t i = 0; i < pixels; ++i) {
        const double rgb[3] = {in[4 * i], in[4 * i + 1], in[4 * i + 2]};
        double d[3];
        toDisplayLinear(matrix, rgb, d);
        if (!insideGamut(d)) {
            // Keep OKLab lightness (clamped to 0..1) and hue; bisect the chroma along the
            // line to the grey RGB = L^3 of the same lightness (white stays white).
            double lab[3], grey[3], trial[3], linear[3];
            opsutil::toLab(rgb, lab);
            lab[0] = std::min(std::max(lab[0], 0.0), 1.0);
            const double g = lab[0] * lab[0] * lab[0];
            const double greyRgb[3] = {g, g, g};
            opsutil::toLab(greyRgb, grey);
            double lo = 0.0, hi = 1.0;
            for (int step = 0; step < kGamutSteps; ++step) {
                const double mid = 0.5 * (lo + hi);
                for (int c = 0; c < 3; ++c) trial[c] = grey[c] + mid * (lab[c] - grey[c]);
                opsutil::fromLab(trial, linear);
                toDisplayLinear(matrix, linear, d);
                (insideGamut(d) ? lo : hi) = mid;
            }
            for (int c = 0; c < 3; ++c) trial[c] = grey[c] + lo * (lab[c] - grey[c]);
            opsutil::fromLab(trial, linear);
            toDisplayLinear(matrix, linear, d);
        }
        for (int c = 0; c < 3; ++c) out[4 * i + c] = clamp01(static_cast<float>(d[c]));
        out[4 * i + 3] = in[4 * i + 3];
    }
}

void workingToDisplay(const float* in, float* out, std::size_t pixels, Display display) {
    gamutMap(in, out, pixels, display);
    for (std::size_t i = 0; i < pixels; ++i) {
        for (int c = 0; c < 3; ++c) out[4 * i + c] = srgbOetf(out[4 * i + c]);
        out[4 * i + 3] = clamp01(out[4 * i + 3]);
    }
}

void workingToDisplay8(const float* in, std::uint8_t* out, std::size_t pixels, Display display) {
    float rgba[4];
    for (std::size_t i = 0; i < pixels; ++i) {
        workingToDisplay(in + 4 * i, rgba, 1, display);
        for (int c = 0; c < 4; ++c) out[4 * i + c] = static_cast<std::uint8_t>(std::lround(rgba[c] * 255.0f));
    }
}

std::uint16_t floatToHalf(float value) {
    std::uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    const std::uint32_t sign = (bits >> 16) & 0x8000u;
    const std::uint32_t exponent = (bits >> 23) & 0xFFu;
    std::uint32_t mantissa = bits & 0x7FFFFFu;
    if (exponent == 0xFFu) {  // Inf or NaN
        return static_cast<std::uint16_t>(sign | 0x7C00u | (mantissa != 0 ? 0x200u : 0u));
    }
    const int e = static_cast<int>(exponent) - 127 + 15;
    if (e >= 31) return static_cast<std::uint16_t>(sign | 0x7C00u);  // overflow -> Inf
    if (e <= 0) {
        if (e < -10) return static_cast<std::uint16_t>(sign);  // underflow -> signed zero
        mantissa |= 0x800000u;
        const int shift = 14 - e;
        std::uint32_t half = mantissa >> shift;
        const std::uint32_t rest = mantissa & ((1u << shift) - 1u);
        const std::uint32_t halfway = 1u << (shift - 1);
        if (rest > halfway || (rest == halfway && (half & 1u))) ++half;  // round to nearest even
        return static_cast<std::uint16_t>(sign | half);
    }
    std::uint32_t half = (static_cast<std::uint32_t>(e) << 10) | (mantissa >> 13);
    const std::uint32_t rest = mantissa & 0x1FFFu;
    if (rest > 0x1000u || (rest == 0x1000u && (half & 1u))) ++half;  // may carry into the exponent: correct
    return static_cast<std::uint16_t>(sign | half);
}

float halfToFloat(std::uint16_t half) {
    const std::uint32_t sign = (static_cast<std::uint32_t>(half) & 0x8000u) << 16;
    const std::uint32_t exponent = (half >> 10) & 0x1Fu;
    std::uint32_t mantissa = half & 0x3FFu;
    std::uint32_t bits;
    if (exponent == 0) {
        if (mantissa == 0) {
            bits = sign;
        } else {  // subnormal: normalise
            int e = -1;
            do {
                ++e;
                mantissa <<= 1;
            } while ((mantissa & 0x400u) == 0);
            bits = sign | (static_cast<std::uint32_t>(127 - 15 - e) << 23) | ((mantissa & 0x3FFu) << 13);
        }
    } else if (exponent == 0x1Fu) {
        bits = sign | 0x7F800000u | (mantissa << 13);
    } else {
        bits = sign | ((exponent - 15 + 127) << 23) | (mantissa << 13);
    }
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

}  // namespace lo::color
