#include "lineover/color_ops.h"

#include <algorithm>
#include <cmath>

#include "lineover/color.h"
#include "lineover/light.h"

namespace lo {
namespace colorops {
namespace {

constexpr double kPi = 3.14159265358979323846;

// colour-science's ITU-R BT.2020 matrices (D65).
constexpr double kRec2020ToXyz[9] = {6.369580483013e-01, 1.446169035862e-01, 1.688809751642e-01,
                                     2.627002120113e-01, 6.779980715189e-01, 5.930171646986e-02,
                                     4.994106574466e-17, 2.807269304909e-02, 1.060985057711e+00};
constexpr double kXyzToRec2020[9] = {1.716651187971,  -0.355670783776, -0.253366281374,
                                     -0.666684351832, 1.616481236635,  0.015768545814,
                                     0.017639857445,  -0.042770613258, 0.942103121235};
constexpr double kBradford[9] = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
constexpr double kD65x = 0.3127, kD65y = 0.3290;
constexpr double kTintDuvPerUnit = 0.05 / 150.0;

// OKLab (Ottosson) composed with Rec.2020 -> XYZ.
constexpr float kRec2020ToLms[9] = {0.616688441751f, 0.36015907047f,  0.023043293521f, 0.265140196183f, 0.635856484699f,
                                    0.099030233566f, 0.100150645103f, 0.204004323432f, 0.696324677437f};
constexpr float kLmsToRec2020[9] = {2.140140411041f,  -1.246355950476f, 0.106431725912f,  -0.884832452797f, 2.163172720721f,
                                    -0.27836159213f,  -0.048579058003f, -0.454490907968f, 1.502356294642f};
constexpr float kLmsToLab[9] = {0.2104542553f, 0.793617785f,  -0.0040720468f, 1.9779984951f, -2.428592205f,
                                0.4505937099f, 0.0259040371f, 0.7827717662f,  -0.808675766f};
constexpr float kLabToLms[9] = {0.999999998451f, 0.396337792174f,  0.215803758061f,  1.000000008882f, -0.105561342324f,
                                -0.063854174772f, 1.000000054672f, -0.089484182095f, -1.291485537864f};

void mul3(const double* m, const double* a, double* out) {
    for (int r = 0; r < 3; ++r) out[r] = m[3 * r] * a[0] + m[3 * r + 1] * a[1] + m[3 * r + 2] * a[2];
}

void mulMat(const double* a, const double* b, double* out) {
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            out[3 * r + c] = 0.0;
            for (int k = 0; k < 3; ++k) out[3 * r + c] += a[3 * r + k] * b[3 * k + c];
        }
}

bool invert3(const double* m, double* out) {
    const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (std::fabs(det) < 1e-300) return false;
    out[0] = (m[4] * m[8] - m[5] * m[7]) / det;
    out[1] = (m[2] * m[7] - m[1] * m[8]) / det;
    out[2] = (m[1] * m[5] - m[2] * m[4]) / det;
    out[3] = (m[5] * m[6] - m[3] * m[8]) / det;
    out[4] = (m[0] * m[8] - m[2] * m[6]) / det;
    out[5] = (m[2] * m[3] - m[0] * m[5]) / det;
    out[6] = (m[3] * m[7] - m[4] * m[6]) / det;
    out[7] = (m[1] * m[6] - m[0] * m[7]) / det;
    out[8] = (m[0] * m[4] - m[1] * m[3]) / det;
    return true;
}

// Krystek 1985 (as colour.temperature.CCT_to_uv_Krystek1985).
void uvPlanck(double t, double* u, double* v) {
    *u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1.0 + 8.42420235e-4 * t + 7.08145163e-7 * t * t);
    *v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1.0 - 2.89741816e-5 * t + 1.61456053e-7 * t * t);
}

void locusNormal(double t, double* nu, double* nv) {
    const double h = t * 1e-4;
    double u0, v0, u1, v1;
    uvPlanck(t - h, &u0, &v0);
    uvPlanck(t + h, &u1, &v1);
    double x = -(v1 - v0), y = u1 - u0;
    const double len = std::hypot(x, y);
    x /= len;
    y /= len;
    if (y < 0) {
        x = -x;
        y = -y;
    }
    *nu = x;
    *nv = y;
}

void d65Uv(double* u, double* v) {
    const double d = -2.0 * kD65x + 12.0 * kD65y + 3.0;
    *u = 4.0 * kD65x / d;
    *v = 6.0 * kD65y / d;
}

void sourceUv(double kelvin, double tint, double* u, double* v) {
    double du, dv, pu, pv, ru, rv, nu, nv;
    d65Uv(&du, &dv);
    uvPlanck(kelvin, &pu, &pv);
    uvPlanck(kWbDefaultKelvin, &ru, &rv);
    locusNormal(kelvin, &nu, &nv);
    *u = du + pu - ru + tint * kTintDuvPerUnit * nu;
    *v = dv + pv - rv + tint * kTintDuvPerUnit * nv;
}

void uvToXyz(double u, double v, double xyz[3]) {
    const double d = 2.0 * u - 8.0 * v + 4.0;
    const double x = 3.0 * u / d, y = 2.0 * v / d;
    xyz[0] = x / y;
    xyz[1] = 1.0;
    xyz[2] = (1.0 - x - y) / y;
}

float cbrtSigned(float x) { return std::cbrt(x); }

}  // namespace

void whiteBalanceMatrix(double kelvin, double tint, double out[9]) {
    double u, v, source[3];
    sourceUv(kelvin, tint, &u, &v);
    uvToXyz(u, v, source);
    const double target[3] = {kD65x / kD65y, 1.0, (1.0 - kD65x - kD65y) / kD65y};
    double ps[3], pt[3], inv[9];
    mul3(kBradford, source, ps);
    mul3(kBradford, target, pt);
    invert3(kBradford, inv);
    double diag[9] = {pt[0] / ps[0], 0, 0, 0, pt[1] / ps[1], 0, 0, 0, pt[2] / ps[2]};
    double a[9], cat[9], b[9];
    mulMat(diag, kBradford, a);
    mulMat(inv, a, cat);
    mulMat(cat, kRec2020ToXyz, b);
    mulMat(kXyzToRec2020, b, out);
}

bool solveNeutral(const float rgb[3], float* kelvin, float* tint) {
    const double c[3] = {rgb[0], rgb[1], rgb[2]};
    double xyz[3];
    mul3(kRec2020ToXyz, c, xyz);
    const double sum = xyz[0] + xyz[1] + xyz[2];
    if (!(xyz[1] > 1e-6) || !(sum > 1e-6)) return false;
    const double x = xyz[0] / sum, y = xyz[1] / sum;
    const double d = -2.0 * x + 12.0 * y + 3.0;
    const double tu = 4.0 * x / d, tv = 6.0 * y / d;
    // For a temperature, the best tint is the normal component of the remaining offset;
    // the temperature minimises what is left (coarse scan in mireds, then refinement).
    auto fit = [&](double k, double* bestTint) {
        double u, v, nu, nv;
        sourceUv(k, 0.0, &u, &v);
        locusNormal(k, &nu, &nv);
        const double t = std::clamp(((tu - u) * nu + (tv - v) * nv) / kTintDuvPerUnit, -150.0, 150.0);
        sourceUv(k, t, &u, &v);
        *bestTint = t;
        return std::hypot(tu - u, tv - v);
    };
    const double m0 = 1e6 / kWbMaxKelvin, m1 = 1e6 / kWbMinKelvin;
    double bestM = m0, bestErr = 1e300, t;
    for (int i = 0; i <= 2000; ++i) {
        const double m = m0 + (m1 - m0) * i / 2000.0;
        const double e = fit(1e6 / m, &t);
        if (e < bestErr) {
            bestErr = e;
            bestM = m;
        }
    }
    double lo = std::max(m0, bestM - (m1 - m0) / 2000.0), hi = std::min(m1, bestM + (m1 - m0) / 2000.0);
    for (int i = 0; i < 60; ++i) {
        const double a = lo + (hi - lo) / 3.0, b = hi - (hi - lo) / 3.0;
        if (fit(1e6 / a, &t) < fit(1e6 / b, &t)) {
            hi = b;
        } else {
            lo = a;
        }
    }
    const double k = 1e6 / (0.5 * (lo + hi));
    double bestTint;
    fit(k, &bestTint);
    *kelvin = static_cast<float>(k);
    *tint = static_cast<float>(bestTint);
    return true;
}

void hslWeights(float hueDegrees, float weights[kHslBands]) {
    std::fill(weights, weights + kHslBands, 0.f);
    float h = std::fmod(hueDegrees, 360.f);
    if (h < 0.f) h += 360.f;
    for (int i = 0; i < kHslBands; ++i) {
        const float c0 = kHslCentres[i];
        const float c1 = kHslCentres[(i + 1) % kHslBands] + (i == kHslBands - 1 ? 360.f : 0.f);
        float dist = std::fmod(h - c0, 360.f);
        if (dist < 0.f) dist += 360.f;
        if (dist < c1 - c0) {
            const float a = 0.5f * (1.f + std::cos(static_cast<float>(kPi) * dist / (c1 - c0)));
            weights[i] += a;
            weights[(i + 1) % kHslBands] += 1.f - a;
            return;
        }
    }
}

void toOklab(const float rgb[3], float lab[3]) {
    float lms[3];
    for (int r = 0; r < 3; ++r) {
        lms[r] = cbrtSigned(kRec2020ToLms[3 * r] * rgb[0] + kRec2020ToLms[3 * r + 1] * rgb[1] + kRec2020ToLms[3 * r + 2] * rgb[2]);
    }
    for (int r = 0; r < 3; ++r) lab[r] = kLmsToLab[3 * r] * lms[0] + kLmsToLab[3 * r + 1] * lms[1] + kLmsToLab[3 * r + 2] * lms[2];
}

void fromOklab(const float lab[3], float rgb[3]) {
    float lms[3];
    for (int r = 0; r < 3; ++r) {
        const float l = kLabToLms[3 * r] * lab[0] + kLabToLms[3 * r + 1] * lab[1] + kLabToLms[3 * r + 2] * lab[2];
        lms[r] = l * l * l;
    }
    for (int r = 0; r < 3; ++r) rgb[r] = kLmsToRec2020[3 * r] * lms[0] + kLmsToRec2020[3 * r + 1] * lms[1] + kLmsToRec2020[3 * r + 2] * lms[2];
}

namespace {

constexpr float kChromaFade = 0.03f;
constexpr float kVibranceChromaRef = 0.3f;
constexpr float kSkinLo = 34.5f, kSkinHi = 84.2f, kSkinEdge = 10.f, kSkinProtect = 0.75f;
constexpr float kHueDegrees = 30.f, kLuminance = 0.4f;

float smoothstepf(float e0, float e1, float x) {
    const float t = std::clamp((x - e0) / (e1 - e0), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

float hueOf(float a, float b) {
    float h = std::atan2(b, a) * static_cast<float>(180.0 / kPi);
    return h < 0.f ? h + 360.f : h;
}

}  // namespace

void targetedWeights(const float rgb[3], float weights[kHslBands]) {
    float lab[3];
    toOklab(rgb, lab);
    const float c = std::hypot(lab[1], lab[2]);
    hslWeights(hueOf(lab[1], lab[2]), weights);
    const float fade = smoothstepf(0.f, kChromaFade, c);
    for (int i = 0; i < kHslBands; ++i) weights[i] *= fade;
}

}  // namespace colorops

namespace {

using namespace colorops;

// GLSL shared by the OKLCh ops: conversions and the shapes of the C++ above.
#define LO_GLSL_OKLAB                                                                            \
    "const mat3 LO_TO_LMS = mat3(0.616688441751, 0.265140196183, 0.100150645103,\n"             \
    "    0.36015907047, 0.635856484699, 0.204004323432, 0.023043293521, 0.099030233566, 0.696324677437);\n" \
    "const mat3 LO_LMS_TO_LAB = mat3(0.2104542553, 1.9779984951, 0.0259040371,\n"                \
    "    0.793617785, -2.428592205, 0.7827717662, -0.0040720468, 0.4505937099, -0.808675766);\n"  \
    "const mat3 LO_LAB_TO_LMS = mat3(0.999999998451, 1.000000008882, 1.000000054672,\n"          \
    "    0.396337792174, -0.105561342324, -0.089484182095, 0.215803758061, -0.063854174772, -1.291485537864);\n" \
    "const mat3 LO_LMS_TO_RGB = mat3(2.140140411041, -0.884832452797, -0.048579058003,\n"        \
    "    -1.246355950476, 2.163172720721, -0.454490907968, 0.106431725912, -0.27836159213, 1.502356294642);\n" \
    "vec3 lo_cbrt(vec3 x) { return sign(x) * pow(abs(x), vec3(1.0 / 3.0)); }\n"                 \
    "vec3 lo_toLab(vec3 c) { return LO_LMS_TO_LAB * lo_cbrt(LO_TO_LMS * c); }\n"                 \
    "vec3 lo_fromLab(vec3 lab) { vec3 l = LO_LAB_TO_LMS * lab; return LO_LMS_TO_RGB * (l * l * l); }\n" \
    "float lo_hue(vec2 ab) { float h = degrees(atan(ab.y, ab.x)); return h < 0.0 ? h + 360.0 : h; }\n"

// Extended sRGB transfer, as reference.encode / decode.
#define LO_GLSL_ENCODE                                                                               \
    "float lo_enc1(float x) { float a = abs(x);\n"                                                   \
    "    return sign(x) * (a <= 0.0031308 ? a * 12.92 : 1.055 * pow(a, 1.0 / 2.4) - 0.055); }\n"     \
    "float lo_dec1(float e) { float a = abs(e);\n"                                                   \
    "    return sign(e) * (a <= 0.04045 ? a / 12.92 : pow((a + 0.055) / 1.055, 2.4)); }\n"           \
    "vec3 lo_enc(vec3 x) { return vec3(lo_enc1(x.r), lo_enc1(x.g), lo_enc1(x.b)); }\n"              \
    "vec3 lo_dec(vec3 e) { return vec3(lo_dec1(e.r), lo_dec1(e.g), lo_dec1(e.b)); }\n"

float encode1(float x) {
    const float a = std::fabs(x);
    const float e = a <= 0.0031308f ? a * 12.92f : 1.055f * std::pow(a, 1.f / 2.4f) - 0.055f;
    return std::copysign(e, x) * (x == 0.f ? 0.f : 1.f);
}

float decode1(float e) {
    const float a = std::fabs(e);
    const float x = a <= 0.04045f ? a / 12.92f : std::pow((a + 0.055f) / 1.055f, 2.4f);
    return std::copysign(x, e) * (e == 0.f ? 0.f : 1.f);
}

class WhiteBalanceOp final : public Op {
public:
    WhiteBalanceOp(float kelvin, float tint)
        : kelvin_(std::clamp(kelvin, kWbMinKelvin, kWbMaxKelvin)), tint_(std::clamp(tint, -kWbTintRange, kWbTintRange)) {
        double m[9];
        whiteBalanceMatrix(kelvin_, tint_, m);
        for (int i = 0; i < 9; ++i) m_[i] = static_cast<float>(m[i]);
    }
    OpType type() const override { return OpType::WhiteBalance; }
    bool isIdentity() const override { return kelvin_ == kWbDefaultKelvin && tint_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader("uniform mat3 u_m;", "return vec4(u_m * c.rgb, c.a);");
        context.setUniformMat3("u_m", m_);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float r = in[4 * i], g = in[4 * i + 1], b = in[4 * i + 2];
            for (int k = 0; k < 3; ++k) out[4 * i + k] = m_[3 * k] * r + m_[3 * k + 1] * g + m_[3 * k + 2] * b;
            out[4 * i + 3] = in[4 * i + 3];
        }
    }

private:
    float kelvin_, tint_;
    float m_[9];
};

// An op in OKLCh: subclasses map (L, C, h) per pixel.
class LchOp : public Op {
public:
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            float lab[3];
            toOklab(in + 4 * i, lab);
            float l = lab[0], c = std::hypot(lab[1], lab[2]), h = hueOf(lab[1], lab[2]);
            map(&l, &c, &h);
            const float r = h * static_cast<float>(kPi / 180.0);
            const float lab2[3] = {l, c * std::cos(r), c * std::sin(r)};
            fromOklab(lab2, out + 4 * i);
            out[4 * i + 3] = in[4 * i + 3];
        }
    }

protected:
    virtual void map(float* l, float* c, float* h) const = 0;
    static constexpr double kPi = 3.14159265358979323846;
};

#define LO_GLSL_LCH_HEAD                                       \
    "vec3 lab = lo_toLab(c.rgb);\n"                           \
    "float L = lab.x; float C = length(lab.yz); float h = lo_hue(lab.yz);\n"
#define LO_GLSL_LCH_TAIL                                       \
    "float r = radians(h);\n"                                 \
    "return vec4(lo_fromLab(vec3(L, C * cos(r), C * sin(r))), c.a);"

class SaturationOp final : public LchOp {
public:
    explicit SaturationOp(float amount) : amount_(std::clamp(amount, -100.f, 100.f)) {}
    OpType type() const override { return OpType::Saturation; }
    bool isIdentity() const override { return amount_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_OKLAB "uniform float u_k;", LO_GLSL_LCH_HEAD "C *= u_k;\n" LO_GLSL_LCH_TAIL);
        context.setUniform1f("u_k", 1.f + amount_ / 100.f);
    }

protected:
    void map(float*, float* c, float*) const override { *c *= 1.f + amount_ / 100.f; }

private:
    float amount_;
};

class VibranceOp final : public LchOp {
public:
    explicit VibranceOp(float amount) : amount_(std::clamp(amount, -100.f, 100.f)) {}
    OpType type() const override { return OpType::Vibrance; }
    bool isIdentity() const override { return amount_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_OKLAB "uniform float u_v;",
                          LO_GLSL_LCH_HEAD
                          "float s = min(C / 0.3, 1.0);\n"
                          "float skin = smoothstep(24.5, 34.5, h) * (1.0 - smoothstep(84.2, 94.2, h));\n"
                          "C *= 1.0 + u_v * (1.0 - s) * (1.0 - 0.75 * skin);\n" LO_GLSL_LCH_TAIL);
        context.setUniform1f("u_v", amount_ / 100.f);
    }

protected:
    void map(float*, float* c, float* h) const override {
        const float s = std::min(*c / kVibranceChromaRef, 1.f);
        const float skin = smoothstepf(kSkinLo - kSkinEdge, kSkinLo, *h) * (1.f - smoothstepf(kSkinHi, kSkinHi + kSkinEdge, *h));
        *c *= 1.f + amount_ / 100.f * (1.f - s) * (1.f - kSkinProtect * skin);
    }

private:
    float amount_;
};

class HslMixerOp final : public LchOp {
public:
    HslMixerOp(const HslBands& hue, const HslBands& sat, const HslBands& lum) {
        for (int i = 0; i < kHslBands; ++i) {
            hue_[i] = std::clamp(hue[i], -100.f, 100.f) / 100.f;
            sat_[i] = std::clamp(sat[i], -100.f, 100.f) / 100.f;
            lum_[i] = std::clamp(lum[i], -100.f, 100.f) / 100.f;
            if (hue_[i] != 0.f || sat_[i] != 0.f || lum_[i] != 0.f) identity_ = false;
        }
    }
    OpType type() const override { return OpType::HslMixer; }
    bool isIdentity() const override { return identity_; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_OKLAB
                          "uniform float u_hue[8];\nuniform float u_sat[8];\nuniform float u_lum[8];\n"
                          "const float LO_CENTRES[9] = float[9](29.2, 52.8, 109.8, 142.5, 194.8, 264.1, 293.8, 328.4, 389.2);\n",
                          LO_GLSL_LCH_HEAD
                          "float dh = 0.0; float ds = 0.0; float dl = 0.0;\n"
                          "for (int i = 0; i < 8; ++i) {\n"
                          "    float c0 = LO_CENTRES[i]; float c1 = LO_CENTRES[i + 1];\n"
                          "    float d = mod(h - c0, 360.0);\n"
                          "    if (d < c1 - c0) {\n"
                          "        float a = 0.5 * (1.0 + cos(3.14159265358979 * d / (c1 - c0)));\n"
                          "        int j = i == 7 ? 0 : i + 1;\n"
                          "        dh = a * u_hue[i] + (1.0 - a) * u_hue[j];\n"
                          "        ds = a * u_sat[i] + (1.0 - a) * u_sat[j];\n"
                          "        dl = a * u_lum[i] + (1.0 - a) * u_lum[j];\n"
                          "        break;\n"
                          "    }\n"
                          "}\n"
                          "float fade = smoothstep(0.0, 0.03, C);\n"
                          "L *= 1.0 + fade * 0.4 * dl;\n"
                          "C *= max(1.0 + fade * ds, 0.0);\n"
                          "h += fade * dh * 30.0;\n" LO_GLSL_LCH_TAIL);
        context.setUniform1fv("u_hue", hue_, kHslBands);
        context.setUniform1fv("u_sat", sat_, kHslBands);
        context.setUniform1fv("u_lum", lum_, kHslBands);
    }

protected:
    void map(float* l, float* c, float* h) const override {
        float w[kHslBands];
        hslWeights(*h, w);
        float dh = 0.f, ds = 0.f, dl = 0.f;
        for (int i = 0; i < kHslBands; ++i) {
            dh += w[i] * hue_[i];
            ds += w[i] * sat_[i];
            dl += w[i] * lum_[i];
        }
        const float fade = smoothstepf(0.f, kChromaFade, *c);
        *l *= 1.f + fade * kLuminance * dl;
        *c *= std::max(1.f + fade * ds, 0.f);
        *h += fade * dh * kHueDegrees;
    }

private:
    float hue_[kHslBands]{}, sat_[kHslBands]{}, lum_[kHslBands]{};
    bool identity_ = true;
};

class EncodedOp : public Op {
public:
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            for (int k = 0; k < 3; ++k) out[4 * i + k] = decode1(map(encode1(in[4 * i + k])));
            out[4 * i + 3] = in[4 * i + 3];
        }
    }

protected:
    virtual float map(float e) const = 0;
};

class InvertOp final : public EncodedOp {
public:
    OpType type() const override { return OpType::Invert; }
    bool isIdentity() const override { return false; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_ENCODE, "return vec4(lo_dec(1.0 - lo_enc(c.rgb)), c.a);");
    }

protected:
    float map(float e) const override { return 1.f - e; }
};

class PosterizeOp final : public EncodedOp {
public:
    explicit PosterizeOp(float levels) : levels_(std::round(std::clamp(levels, 0.f, 32.f))) {}
    OpType type() const override { return OpType::Posterize; }
    bool isIdentity() const override { return levels_ < 2.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_ENCODE "uniform float u_n;",
                          "return vec4(lo_dec(floor(lo_enc(c.rgb) * u_n + 0.5) / u_n), c.a);");
        context.setUniform1f("u_n", levels_ - 1.f);
    }

protected:
    float map(float e) const override {
        const float n = levels_ - 1.f;
        return std::floor(e * n + 0.5f) / n;
    }

private:
    float levels_;
};

class ThresholdOp final : public Op {
public:
    explicit ThresholdOp(float level) : level_(std::clamp(level, 0.f, 1.f)) {}
    OpType type() const override { return OpType::Threshold; }
    bool isIdentity() const override { return false; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_ENCODE "uniform float u_t;",
                          "float y = lo_enc1(dot(c.rgb, vec3(0.2627, 0.6780, 0.0593)));\n"
                          "return vec4(vec3(y >= u_t ? 1.0 : 0.0), c.a);");
        context.setUniform1f("u_t", level_);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float v = encode1(light::luminance(in + 4 * i)) >= level_ ? 1.f : 0.f;
            out[4 * i] = out[4 * i + 1] = out[4 * i + 2] = v;
            out[4 * i + 3] = in[4 * i + 3];
        }
    }

private:
    float level_;
};

}  // namespace

std::unique_ptr<Op> makeWhiteBalance(float kelvin, float tint) { return std::make_unique<WhiteBalanceOp>(kelvin, tint); }
std::unique_ptr<Op> makeVibrance(float amount) { return std::make_unique<VibranceOp>(amount); }
std::unique_ptr<Op> makeSaturation(float amount) { return std::make_unique<SaturationOp>(amount); }
std::unique_ptr<Op> makeHslMixer(const HslBands& hue, const HslBands& saturation, const HslBands& luminance) {
    return std::make_unique<HslMixerOp>(hue, saturation, luminance);
}
std::unique_ptr<Op> makeInvert() { return std::make_unique<InvertOp>(); }
std::unique_ptr<Op> makePosterize(float levels) { return std::make_unique<PosterizeOp>(levels); }
std::unique_ptr<Op> makeThreshold(float level) { return std::make_unique<ThresholdOp>(level); }

}  // namespace lo
