#include "lineover/grade_ops.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>

#include "lineover/color_ops.h"
#include "lineover/light.h"
#include "op_util.h"

namespace lo {
namespace {

using opsutil::decode1;
using opsutil::encode1;
using opsutil::smoothstepf;
using opsutil::spow;
using opsutil::fromLab;
using opsutil::toLab;
using opsutil::mul;

constexpr double kPi = 3.14159265358979323846;
constexpr float kLumaEps = 1e-6f;

// reference.tint_direction
void tintDirection(double hue, float out[3]) {
    const double r = hue * kPi / 180.0;
    const double lab[3] = {0.7, 0.1 * std::cos(r), 0.1 * std::sin(r)};
    double rgb[3];
    fromLab(lab, rgb);
    double mx = 0.0;
    for (double& x : rgb) {
        x -= 0.7 * 0.7 * 0.7;
        mx = std::max(mx, std::fabs(x));
    }
    for (int i = 0; i < 3; ++i) out[i] = static_cast<float>(rgb[i] / mx);
}

float luma(const float* p) { return light::luminance(p); }

// Tone weights (shadows, midtones, highlights) of encoded luminance.
void toneWeights(float ye, float mid, float width, float w[3]) {
    const float t = smoothstepf(mid - width, mid + width, ye);
    w[0] = (1.f - t) * (1.f - t);
    w[1] = 2.f * t * (1.f - t);
    w[2] = t * t;
}

float lutSample(const float* lut, float e) {
    const float c = std::clamp(e, 0.f, 1.f);
    const float p = c * (kLutSize - 1);
    const int i = std::min(static_cast<int>(std::floor(p)), kLutSize - 2);
    const float f = p - static_cast<float>(i);
    return lut[i] * (1.f - f) + lut[i + 1] * f + (e - c);
}

#define LO_GLSL_TONE                                                                 \
    "vec3 lo_tone(float ye, float mid, float w) { float t = smoothstep(mid - w, mid + w, ye);\n" \
    "    return vec3((1.0 - t) * (1.0 - t), 2.0 * t * (1.0 - t), t * t); }\n"
#define LO_GLSL_SPOW "vec3 lo_spow(vec3 v, vec3 p) { return sign(v) * pow(abs(v), p); }\n"
#define LO_LUMA "vec3(0.2627, 0.6780, 0.0593)"

// --- Curves -------------------------------------------------------------------------

class CurvesOp final : public Op {
public:
    CurvesOp(OpType type, const CurveSet& set) : type_(type) {
        const CurvePoints* curves[5] = {&set.rgb, &set.red, &set.green, &set.blue, &set.luma};
        lut_.assign(static_cast<std::size_t>(kLutSize) * 5 * 4, 0.f);
        std::vector<float> row(kLutSize);
        for (int r = 0; r < 5; ++r) {
            has_[r] = curves[r]->size() >= 2 && !isIdentityCurve(*curves[r]);
            if (!has_[r]) continue;
            grade::curveLut(*curves[r], row.data());
            rows_[r] = row;
            for (int i = 0; i < kLutSize; ++i) lut_[(static_cast<std::size_t>(r) * kLutSize + i) * 4] = row[i];
        }
    }
    OpType type() const override { return type_; }
    bool isIdentity() const override { return !(has_[0] || has_[1] || has_[2] || has_[3] || has_[4]); }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_ENCODE "uniform float u_has[5];",
                          "vec3 e = lo_enc(c.rgb);\n"
                          "if (u_has[1] > 0.5) e.r = lo_lut1(1, e.r);\n"
                          "if (u_has[2] > 0.5) e.g = lo_lut1(2, e.g);\n"
                          "if (u_has[3] > 0.5) e.b = lo_lut1(3, e.b);\n"
                          "if (u_has[0] > 0.5) e = vec3(lo_lut1(0, e.r), lo_lut1(0, e.g), lo_lut1(0, e.b));\n"
                          "vec3 rgb = lo_dec(e);\n"
                          "if (u_has[4] > 0.5) {\n"
                          "    float y = dot(rgb, " LO_LUMA ");\n"
                          "    if (abs(y) > 1e-6) rgb *= lo_dec1(lo_lut1(4, lo_enc1(y))) / y;\n"
                          "}\n"
                          "return vec4(rgb, c.a);");
        float flags[5];
        for (int i = 0; i < 5; ++i) flags[i] = has_[i] ? 1.f : 0.f;
        context.setUniform1fv("u_has", flags, 5);
        context.setLut(lut_.data(), kLutSize, 5);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float* p = in + 4 * i;
            float* q = out + 4 * i;
            float e[3] = {encode1(p[0]), encode1(p[1]), encode1(p[2])};
            for (int c = 0; c < 3; ++c)
                if (has_[c + 1]) e[c] = lutSample(rows_[c + 1].data(), e[c]);
            if (has_[0])
                for (float& x : e) x = lutSample(rows_[0].data(), x);
            float rgb[3] = {decode1(e[0]), decode1(e[1]), decode1(e[2])};
            if (has_[4]) {
                const float y = luma(rgb);
                if (std::fabs(y) > kLumaEps) {
                    const float ratio = decode1(lutSample(rows_[4].data(), encode1(y))) / y;
                    for (float& x : rgb) x *= ratio;
                }
            }
            q[0] = rgb[0];
            q[1] = rgb[1];
            q[2] = rgb[2];
            q[3] = p[3];
        }
    }

private:
    static bool isIdentityCurve(const CurvePoints& p) {
        for (const auto& [x, y] : p)
            if (x != y) return false;
        return true;
    }

    OpType type_;
    bool has_[5] = {};
    std::vector<float> rows_[5];
    std::vector<float> lut_;
};

// --- Levels -------------------------------------------------------------------------

float levelsChannel(float e, const float* v) {
    const float t = (e - v[0]) / std::max(v[2] - v[0], 0.01f);
    return v[3] + (v[4] - v[3]) * spow(t, 1.f / std::max(v[1], 0.01f));
}

class LevelsOp final : public Op {
public:
    explicit LevelsOp(const std::array<float, 20>& v) : v_(v) {
        constexpr float kIdentity[5] = {0.f, 1.f, 1.f, 0.f, 1.f};
        for (int i = 0; i < 20; ++i)
            if (v_[i] != kIdentity[i % 5]) identity_ = false;
    }
    OpType type() const override { return OpType::Levels; }
    bool isIdentity() const override { return identity_; }
    void gpu(GpuOpContext& context) const override {
        static const std::string body = [] {
            std::string b = "vec3 e = lo_enc(c.rgb);\n";
            const char* ch[3] = {"r", "g", "b"};
            char line[256];
            for (int c = 0; c < 3; ++c) {
                const int o = 5 * (c + 1);
                std::snprintf(line, sizeof(line), "e.%s = lo_lv(e.%s, u_lv[%d], u_lv[%d], u_lv[%d], u_lv[%d], u_lv[%d]);\n", ch[c], ch[c],
                              o, o + 1, o + 2, o + 3, o + 4);
                b += line;
            }
            for (int c = 0; c < 3; ++c) {
                std::snprintf(line, sizeof(line), "e.%s = lo_lv(e.%s, u_lv[0], u_lv[1], u_lv[2], u_lv[3], u_lv[4]);\n", ch[c], ch[c]);
                b += line;
            }
            return b + "return vec4(lo_dec(e), c.a);";
        }();
        context.setShader(LO_GLSL_ENCODE
                          "uniform float u_lv[20];\n"
                          "float lo_lv(float e, float ib, float g, float iw, float ob, float ow) {\n"
                          "    float t = (e - ib) / max(iw - ib, 0.01);\n"
                          "    return ob + (ow - ob) * sign(t) * pow(abs(t), 1.0 / max(g, 0.01)); }\n",
                          body.c_str());
        context.setUniform1fv("u_lv", v_.data(), 20);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            for (int c = 0; c < 3; ++c) {
                float e = levelsChannel(encode1(in[4 * i + c]), &v_[5 * (c + 1)]);
                e = levelsChannel(e, &v_[0]);
                out[4 * i + c] = decode1(e);
            }
            out[4 * i + 3] = in[4 * i + 3];
        }
    }

private:
    std::array<float, 20> v_;
    bool identity_ = true;
};

// --- Color grading ------------------------------------------------------------------

class ColorGradingOp final : public Op {
public:
    ColorGradingOp(const GradeWheel& s, const GradeWheel& m, const GradeWheel& h, const GradeWheel& g, float blending, float balance) {
        identity_ = s.saturation == 0.f && s.luminance == 0.f && m.saturation == 0.f && m.luminance == 0.f &&
                    h.saturation == 0.f && h.luminance == 0.f && g.saturation == 0.f && g.luminance == 0.f;
        float vs[3], vm[3], vh[3], vg[3];
        tintDirection(s.hue, vs);
        tintDirection(m.hue, vm);
        tintDirection(h.hue, vh);
        tintDirection(g.hue, vg);
        u_[0] = 0.5f - 0.25f * balance / 100.f;   // mid
        u_[1] = 0.15f + 0.35f * blending / 100.f;  // width
        for (int c = 0; c < 3; ++c) {
            u_[2 + c] = kHighlightTint * h.saturation / 100.f * vh[c];                    // slope tint
            u_[5 + c] = 0.08f * s.saturation / 100.f * vs[c] + 0.1f * s.luminance / 100.f;  // shadow offset
            u_[8 + c] = 0.6f * m.saturation / 100.f * vm[c] + 0.5f * m.luminance / 100.f;   // power
            u_[11 + c] = 0.05f * g.saturation / 100.f * vg[c] + 0.1f * g.luminance / 100.f; // global offset
        }
        u_[14] = 0.5f * h.luminance / 100.f;  // slope luminance
    }
    OpType type() const override { return OpType::ColorGrading; }
    bool isIdentity() const override { return identity_; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_ENCODE LO_GLSL_TONE LO_GLSL_SPOW "uniform float u_g[15];",
                          "vec3 e = lo_enc(c.rgb);\n"
                          "float ye = lo_enc1(dot(c.rgb, " LO_LUMA "));\n"
                          "vec3 w = lo_tone(ye, u_g[0], u_g[1]);\n"
                          "float fade = 1.0 - smoothstep(0.9, 1.05, ye);\n"
                          "vec3 slope = 1.0 + w.z * (fade * vec3(u_g[2], u_g[3], u_g[4]) + u_g[14]);\n"
                          "vec3 offset = w.x * vec3(u_g[5], u_g[6], u_g[7]) + vec3(u_g[11], u_g[12], u_g[13]);\n"
                          "vec3 power = 1.0 / max(1.0 + w.y * vec3(u_g[8], u_g[9], u_g[10]), vec3(0.05));\n"
                          "return vec4(lo_dec(lo_spow(e * slope + offset, power)), c.a);");
        context.setUniform1fv("u_g", u_, 15);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float* p = in + 4 * i;
            float w[3];
            const float ye = encode1(luma(p));
            toneWeights(ye, u_[0], u_[1], w);
            const float fade = 1.f - smoothstepf(kWhiteFadeFrom, kWhiteFadeTo, ye);
            for (int c = 0; c < 3; ++c) {
                const float slope = 1.f + w[2] * (fade * u_[2 + c] + u_[14]);
                const float offset = w[0] * u_[5 + c] + u_[11 + c];
                const float power = 1.f / std::max(1.f + w[1] * u_[8 + c], 0.05f);
                out[4 * i + c] = decode1(spow(encode1(p[c]) * slope + offset, power));
            }
            out[4 * i + 3] = p[3];
        }
    }

private:
    // Highlights tint strength (slope per 100 % saturation), and the encoded-luminance range
    // over which it fades out so that white and HDR highlights stay neutral (as reference.py).
    static constexpr float kHighlightTint = 0.2f;
    static constexpr float kWhiteFadeFrom = 0.9f;
    static constexpr float kWhiteFadeTo = 1.05f;
    float u_[15]{};
    bool identity_ = true;
};

// Keeps the linear luminance of the input (Color Balance, Photo Filter).
void preserveLuminance(const float* before, float* rgb) {
    const float y0 = luma(before), y1 = luma(rgb);
    if (std::fabs(y1) > kLumaEps)
        for (int c = 0; c < 3; ++c) rgb[c] *= y0 / y1;
}

#define LO_GLSL_PRESERVE                                                                   \
    "vec3 lo_keepY(vec3 before, vec3 rgb) { float y1 = dot(rgb, " LO_LUMA ");\n"            \
    "    return abs(y1) > 1e-6 ? rgb * (dot(before, " LO_LUMA ") / y1) : rgb; }\n"

// --- Color balance ------------------------------------------------------------------

class ColorBalanceOp final : public Op {
public:
    ColorBalanceOp(const std::array<float, 3>& s, const std::array<float, 3>& m, const std::array<float, 3>& h, bool preserve)
        : preserve_(preserve) {
        for (int c = 0; c < 3; ++c) {
            u_[c] = s[c];
            u_[3 + c] = m[c];
            u_[6 + c] = h[c];
            if (s[c] != 0.f || m[c] != 0.f || h[c] != 0.f) identity_ = false;
        }
    }
    OpType type() const override { return OpType::ColorBalance; }
    bool isIdentity() const override { return identity_; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_ENCODE LO_GLSL_TONE "uniform float u_b[9];\nuniform float u_keep;",
                          "vec3 w = lo_tone(lo_enc1(dot(c.rgb, " LO_LUMA ")), 0.5, 0.3);\n"
                          "vec3 d = 0.15 * (w.x * vec3(u_b[0], u_b[1], u_b[2]) + w.y * vec3(u_b[3], u_b[4], u_b[5])\n"
                          "    + w.z * vec3(u_b[6], u_b[7], u_b[8])) / 100.0;\n"
                          "vec3 rgb = lo_dec(lo_enc(c.rgb) + d);\n"
                          "if (u_keep > 0.5) rgb += dot(c.rgb - rgb, " LO_LUMA ");\n"
                          "return vec4(rgb, c.a);");
        context.setUniform1fv("u_b", u_, 9);
        context.setUniform1f("u_keep", preserve_ ? 1.f : 0.f);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float* p = in + 4 * i;
            float w[3];
            toneWeights(encode1(luma(p)), 0.5f, 0.3f, w);
            float rgb[3];
            for (int c = 0; c < 3; ++c) {
                const float d = 0.15f * (w[0] * u_[c] + w[1] * u_[3 + c] + w[2] * u_[6 + c]) / 100.f;
                rgb[c] = decode1(encode1(p[c]) + d);
            }
            if (preserve_) {
                // Additive grey correction: exact and well conditioned near zero luminance.
                const float dy = luma(p) - luma(rgb);
                for (float& x : rgb) x += dy;
            }
            std::copy(rgb, rgb + 3, out + 4 * i);
            out[4 * i + 3] = p[3];
        }
    }

private:
    float u_[9]{};
    bool preserve_;
    bool identity_ = true;
};

// --- Selective color ----------------------------------------------------------------

void selectiveAmounts(const float* e, float a[9]) {
    const float r = std::clamp(e[0], 0.f, 1.f), g = std::clamp(e[1], 0.f, 1.f), b = std::clamp(e[2], 0.f, 1.f);
    const float mx = std::max({r, g, b}), mn = std::min({r, g, b});
    a[0] = std::max(0.f, r - std::max(g, b));
    a[1] = std::max(0.f, std::min(r, g) - b);
    a[2] = std::max(0.f, g - std::max(r, b));
    a[3] = std::max(0.f, std::min(g, b) - r);
    a[4] = std::max(0.f, b - std::max(r, g));
    a[5] = std::max(0.f, std::min(r, b) - g);
    a[6] = std::max(0.f, 2.f * mn - 1.f);
    a[7] = std::max(0.f, 1.f - std::fabs(mx + mn - 1.f) - (mx - mn));
    a[8] = std::max(0.f, 1.f - 2.f * mx);
}

class SelectiveColorOp final : public Op {
public:
    SelectiveColorOp(const std::array<float, 36>& v, bool relative) : relative_(relative) {
        for (int i = 0; i < 36; ++i) {
            v_[i] = v[i] / 100.f;
            if (v[i] != 0.f) identity_ = false;
        }
    }
    OpType type() const override { return OpType::SelectiveColor; }
    bool isIdentity() const override { return identity_; }
    void gpu(GpuOpContext& context) const override {
        static const std::string body = [] {
            std::string b =
                "vec3 e = lo_enc(c.rgb); vec3 ink = 1.0 - e; vec3 k = clamp(e, 0.0, 1.0);\n"
                "float mx = max(k.r, max(k.g, k.b)); float mn = min(k.r, min(k.g, k.b));\n"
                "float a[9];\n"
                "a[0] = max(0.0, k.r - max(k.g, k.b)); a[1] = max(0.0, min(k.r, k.g) - k.b);\n"
                "a[2] = max(0.0, k.g - max(k.r, k.b)); a[3] = max(0.0, min(k.g, k.b) - k.r);\n"
                "a[4] = max(0.0, k.b - max(k.r, k.g)); a[5] = max(0.0, min(k.r, k.b) - k.g);\n"
                "a[6] = max(0.0, 2.0 * mn - 1.0); a[7] = max(0.0, 1.0 - abs(mx + mn - 1.0) - (mx - mn));\n"
                "a[8] = max(0.0, 1.0 - 2.0 * mx);\n"
                "vec3 scale = u_rel > 0.5 ? ink : vec3(1.0);\nvec3 delta = vec3(0.0);\n";
            char line[256];
            for (int f = 0; f < 9; ++f) {
                std::snprintf(line, sizeof(line),
                              "delta += a[%d] * scale * (vec3(u_s[%d], u_s[%d], u_s[%d]) + u_s[%d]);\n", f, 4 * f, 4 * f + 1,
                              4 * f + 2, 4 * f + 3);
                b += line;
            }
            return b + "return vec4(lo_dec(1.0 - (ink + delta)), c.a);";
        }();
        context.setShader(LO_GLSL_ENCODE "uniform float u_s[36];\nuniform float u_rel;", body.c_str());
        context.setUniform1fv("u_s", v_, 36);
        context.setUniform1f("u_rel", relative_ ? 1.f : 0.f);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float* p = in + 4 * i;
            const float e[3] = {encode1(p[0]), encode1(p[1]), encode1(p[2])};
            float a[9];
            selectiveAmounts(e, a);
            for (int c = 0; c < 3; ++c) {
                const float ink = 1.f - e[c];
                float delta = 0.f;
                for (int f = 0; f < 9; ++f) delta += a[f] * (relative_ ? ink : 1.f) * (v_[4 * f + c] + v_[4 * f + 3]);
                out[4 * i + c] = decode1(1.f - (ink + delta));
            }
            out[4 * i + 3] = p[3];
        }
    }

private:
    float v_[36]{};
    bool relative_;
    bool identity_ = true;
};

// --- Matrix ops (channel mixer, calibration) -------------------------------------------

class MatrixOp final : public Op {
public:
    MatrixOp(OpType type, const float m[9], const float k[3], bool identity) : type_(type), identity_(identity) {
        std::copy(m, m + 9, m_);
        std::copy(k, k + 3, k_);
    }
    OpType type() const override { return type_; }
    bool isIdentity() const override { return identity_; }
    void gpu(GpuOpContext& context) const override {
        context.setShader("uniform mat3 u_m;\nuniform float u_k[3];", "return vec4(u_m * c.rgb + vec3(u_k[0], u_k[1], u_k[2]), c.a);");
        context.setUniformMat3("u_m", m_);
        context.setUniform1fv("u_k", k_, 3);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float r = in[4 * i], g = in[4 * i + 1], b = in[4 * i + 2];
            for (int c = 0; c < 3; ++c) out[4 * i + c] = m_[3 * c] * r + m_[3 * c + 1] * g + m_[3 * c + 2] * b + k_[c];
            out[4 * i + 3] = in[4 * i + 3];
        }
    }

private:
    OpType type_;
    float m_[9];
    float k_[3];
    bool identity_;
};

// --- Black & white ------------------------------------------------------------------

constexpr float kBwCentres[6] = {29.2f, 109.8f, 142.5f, 194.8f, 264.1f, 328.4f};

void bandWeights6(float h, float w[6]) {
    std::fill(w, w + 6, 0.f);
    h = std::fmod(h, 360.f);
    if (h < 0.f) h += 360.f;
    for (int i = 0; i < 6; ++i) {
        const float c0 = kBwCentres[i];
        const float c1 = kBwCentres[(i + 1) % 6] + (i == 5 ? 360.f : 0.f);
        float d = std::fmod(h - c0, 360.f);
        if (d < 0.f) d += 360.f;
        if (d < c1 - c0) {
            const float a = 0.5f * (1.f + std::cos(static_cast<float>(kPi) * d / (c1 - c0)));
            w[i] += a;
            w[(i + 1) % 6] += 1.f - a;
            return;
        }
    }
}

class BlackAndWhiteOp final : public Op {
public:
    BlackAndWhiteOp(const std::array<float, 6>& mix, float tintHue, float tintSaturation) {
        for (int i = 0; i < 6; ++i) mix_[i] = mix[i] / 100.f;
        float v[3];
        tintDirection(tintHue, v);
        for (int c = 0; c < 3; ++c) tint_[c] = 0.5f * tintSaturation / 100.f * v[c];
    }
    OpType type() const override { return OpType::BlackAndWhite; }
    bool isIdentity() const override { return false; }
    void gpu(GpuOpContext& context) const override {
        static const std::string body = [] {
            std::string b =
                "vec3 lab = lo_toLab(c.rgb); float C = length(lab.yz); float h = lo_hue(lab.yz);\n"
                "float m = 0.0; float d; float a;\n";
            char line[384];
            for (int i = 0; i < 6; ++i) {
                const int j = (i + 1) % 6;
                const float c0 = kBwCentres[i];
                const float width = kBwCentres[j] + (j == 0 ? 360.f : 0.f) - c0;
                std::snprintf(line, sizeof(line),
                              "d = mod(h - %.4f, 360.0);\n"
                              "if (d < %.4f) { a = 0.5 * (1.0 + cos(3.14159265358979 * d / %.4f)); m = a * u_mix[%d] + (1.0 - a) * u_mix[%d]; }\n",
                              c0, width, width, i, j);
                b += line;
            }
            return b +
                   "float grey = dot(c.rgb, " LO_LUMA ") * exp2(smoothstep(0.0, 0.03, C) * m);\n"
                   "return vec4(grey * (1.0 + vec3(u_tint[0], u_tint[1], u_tint[2])), c.a);";
        }();
        context.setShader(LO_GLSL_OKLAB "uniform float u_mix[6];\nuniform float u_tint[3];", body.c_str());
        context.setUniform1fv("u_mix", mix_, 6);
        context.setUniform1fv("u_tint", tint_, 3);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float* p = in + 4 * i;
            float lab[3];
            colorops::toOklab(p, lab);
            const float chroma = std::hypot(lab[1], lab[2]);
            float h = std::atan2(lab[2], lab[1]) * static_cast<float>(180.0 / kPi);
            if (h < 0.f) h += 360.f;
            float w[6];
            bandWeights6(h, w);
            float m = 0.f;
            for (int k = 0; k < 6; ++k) m += w[k] * mix_[k];
            const float grey = luma(p) * std::exp2(smoothstepf(0.f, 0.03f, chroma) * m);
            for (int c = 0; c < 3; ++c) out[4 * i + c] = grey * (1.f + tint_[c]);
            out[4 * i + 3] = p[3];
        }
    }

private:
    float mix_[6]{};
    float tint_[3]{};
};

// --- Photo filter -------------------------------------------------------------------

class PhotoFilterOp final : public Op {
public:
    PhotoFilterOp(float hue, float saturation, float density, bool preserve) : density_(density), preserve_(preserve) {
        const double r = hue * kPi / 180.0, c = 0.15 * saturation / 100.0;
        const double lab[3] = {0.75, c * std::cos(r), c * std::sin(r)};
        double rgb[3];
        fromLab(lab, rgb);
        const double mx = std::max({rgb[0], rgb[1], rgb[2]});
        for (int k = 0; k < 3; ++k) f_[k] = static_cast<float>(1.0 + density / 100.0 * (rgb[k] / mx - 1.0));
    }
    OpType type() const override { return OpType::PhotoFilter; }
    bool isIdentity() const override { return density_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_PRESERVE "uniform float u_f[3];\nuniform float u_keep;",
                          "vec3 rgb = c.rgb * vec3(u_f[0], u_f[1], u_f[2]);\n"
                          "if (u_keep > 0.5) rgb = lo_keepY(c.rgb, rgb);\n"
                          "return vec4(rgb, c.a);");
        context.setUniform1fv("u_f", f_, 3);
        context.setUniform1f("u_keep", preserve_ ? 1.f : 0.f);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float* p = in + 4 * i;
            float rgb[3] = {p[0] * f_[0], p[1] * f_[1], p[2] * f_[2]};
            if (preserve_) preserveLuminance(p, rgb);
            std::copy(rgb, rgb + 3, out + 4 * i);
            out[4 * i + 3] = p[3];
        }
    }

private:
    float density_;
    bool preserve_;
    float f_[3]{};
};

// --- Gradient map -------------------------------------------------------------------

class GradientMapOp final : public Op {
public:
    explicit GradientMapOp(std::vector<std::array<float, 4>> stops) {
        std::stable_sort(stops.begin(), stops.end(), [](const auto& a, const auto& b) { return a[0] < b[0]; });
        lut_.resize(static_cast<std::size_t>(kLutSize) * 4);
        for (int i = 0; i < kLutSize; ++i) {
            const double t = i / (kLutSize - 1.0);
            double lab[3];
            for (int k = 0; k < 3; ++k) lab[k] = interp(stops, t, k + 1);
            double rgb[3];
            fromLab(lab, rgb);
            for (int k = 0; k < 3; ++k) lut_[4 * static_cast<std::size_t>(i) + k] = static_cast<float>(rgb[k]);
            lut_[4 * static_cast<std::size_t>(i) + 3] = 1.f;
        }
    }
    OpType type() const override { return OpType::GradientMap; }
    bool isIdentity() const override { return false; }
    void gpu(GpuOpContext& context) const override {
        context.setShader(LO_GLSL_ENCODE,
                          "float t = clamp(lo_enc1(dot(c.rgb, " LO_LUMA ")), 0.0, 1.0);\n"
                          "return vec4(lo_lut3(0, t), c.a);");
        context.setLut(lut_.data(), kLutSize, 1);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float t = std::clamp(encode1(luma(in + 4 * i)), 0.f, 1.f);
            const float pos = t * (kLutSize - 1);
            const int j = std::min(static_cast<int>(std::floor(pos)), kLutSize - 2);
            const float f = pos - static_cast<float>(j);
            for (int c = 0; c < 3; ++c) out[4 * i + c] = lut_[4 * j + c] * (1.f - f) + lut_[4 * (j + 1) + c] * f;
            out[4 * i + 3] = in[4 * i + 3];
        }
    }

private:
    // numpy.interp: clamped at the ends, linear between stops.
    static double interp(const std::vector<std::array<float, 4>>& s, double t, int k) {
        if (s.empty()) return 0.0;
        if (t <= s.front()[0]) return s.front()[k];
        if (t >= s.back()[0]) return s.back()[k];
        for (std::size_t i = 0; i + 1 < s.size(); ++i) {
            if (t <= s[i + 1][0]) {
                const double span = s[i + 1][0] - s[i][0];
                if (span <= 0.0) return s[i + 1][k];
                const double f = (t - s[i][0]) / span;
                return s[i][k] + (s[i + 1][k] - s[i][k]) * f;
            }
        }
        return s.back()[k];
    }

    std::vector<float> lut_;
};

}  // namespace

namespace grade {

void curveLut(const CurvePoints& points, float out[kLutSize]) {
    std::vector<double> xs, ys;
    for (const auto& [x, y] : points) {
        if (!xs.empty() && x <= xs.back() + 1e-6) continue;  // x must increase
        xs.push_back(x);
        ys.push_back(y);
    }
    const std::size_t n = xs.size();
    if (n < 2) {
        for (int i = 0; i < kLutSize; ++i) out[i] = n == 1 ? static_cast<float>(ys[0]) : i / (kLutSize - 1.f);
        return;
    }
    std::vector<double> d(n - 1), m(n);
    for (std::size_t k = 0; k + 1 < n; ++k) d[k] = (ys[k + 1] - ys[k]) / (xs[k + 1] - xs[k]);
    m[0] = d[0];
    m[n - 1] = d[n - 2];
    for (std::size_t k = 1; k + 1 < n; ++k) m[k] = d[k - 1] * d[k] <= 0.0 ? 0.0 : 0.5 * (d[k - 1] + d[k]);
    for (std::size_t k = 0; k + 1 < n; ++k) {
        if (d[k] == 0.0) {
            m[k] = 0.0;
            m[k + 1] = 0.0;
            continue;
        }
        const double a = m[k] / d[k], b = m[k + 1] / d[k];
        const double s = a * a + b * b;
        if (s > 9.0) {
            const double t = 3.0 / std::sqrt(s);
            m[k] = t * a * d[k];
            m[k + 1] = t * b * d[k];
        }
    }
    std::size_t k = 0;
    for (int i = 0; i < kLutSize; ++i) {
        const double x = i / (kLutSize - 1.0);
        if (x <= xs[0]) {
            out[i] = static_cast<float>(ys[0]);
            continue;
        }
        if (x >= xs[n - 1]) {
            out[i] = static_cast<float>(ys[n - 1]);
            continue;
        }
        while (k + 2 < n && x >= xs[k + 1]) ++k;
        const double h = xs[k + 1] - xs[k];
        const double t = std::clamp((x - xs[k]) / h, 0.0, 1.0);
        const double t2 = t * t, t3 = t2 * t;
        out[i] = static_cast<float>((2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] +
                                    (t3 - t2) * h * m[k + 1]);
    }
}

CurvePoints parametricPoints(float highlights, float lights, float darks, float shadows, float split1, float split2, float split3) {
    const float edges[5] = {0.f, split1, split2, split3, 1.f};
    const float amounts[4] = {shadows, darks, lights, highlights};
    CurvePoints p{{0.f, 0.f}};
    for (int i = 0; i < 4; ++i) {
        const float lo = edges[i], hi = edges[i + 1];
        const float c = 0.5f * (lo + hi);
        p.emplace_back(c, c + amounts[i] / 100.f * 0.45f * (hi - lo));
        p.emplace_back(hi, hi);
    }
    return p;
}

void calibrationMatrix(const std::array<float, 3>& hue, const std::array<float, 3>& saturation, double out[9]) {
    double cols[3][3];
    for (int i = 0; i < 3; ++i) {
        double e[3] = {0, 0, 0};
        e[i] = 1.0;
        double lab[3];
        toLab(e, lab);
        const double c = std::hypot(lab[1], lab[2]) * (1.0 + saturation[i] / 100.0);
        const double h = std::atan2(lab[2], lab[1]) + hue[i] / 100.0 * 30.0 * kPi / 180.0;
        const double lab2[3] = {lab[0], c * std::cos(h), c * std::sin(h)};
        fromLab(lab2, cols[i]);
    }
    // m (columns = new primaries); k = m^-1 * (1, 1, 1); out = m * diag(k).
    const double m[9] = {cols[0][0], cols[1][0], cols[2][0], cols[0][1], cols[1][1], cols[2][1], cols[0][2], cols[1][2], cols[2][2]};
    const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    const double inv[9] = {(m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
                           (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                           (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det};
    double k[3];
    for (int r = 0; r < 3; ++r) k[r] = inv[3 * r] + inv[3 * r + 1] + inv[3 * r + 2];
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) out[3 * r + c] = m[3 * r + c] * k[c];
}

void channelPercentiles(const float* rgba, std::size_t pixels, float low, float high, float out[6], std::size_t stride) {
    stride = std::max<std::size_t>(stride, 1);
    std::vector<float> v;
    v.reserve(pixels / stride + 1);
    for (int c = 0; c < 3; ++c) {
        v.clear();
        for (std::size_t i = 0; i < pixels; i += stride) v.push_back(encode1(rgba[4 * i + c]));
        if (v.empty()) {
            out[2 * c] = 0.f;
            out[2 * c + 1] = 1.f;
            continue;
        }
        auto at = [&](float q) {
            const auto k = static_cast<std::size_t>(std::floor(std::clamp(q, 0.f, 1.f) * (v.size() - 1) + 0.5f));
            std::nth_element(v.begin(), v.begin() + static_cast<std::ptrdiff_t>(k), v.end());
            return v[k];
        };
        out[2 * c] = at(low);
        out[2 * c + 1] = at(high);
    }
}

}  // namespace grade

std::unique_ptr<Op> makeCurves(const CurveSet& curves) { return std::make_unique<CurvesOp>(OpType::Curves, curves); }

std::unique_ptr<Op> makeParametricCurve(float highlights, float lights, float darks, float shadows, float split1, float split2,
                                        float split3) {
    CurveSet set;
    if (highlights != 0.f || lights != 0.f || darks != 0.f || shadows != 0.f) {
        set.rgb = grade::parametricPoints(highlights, lights, darks, shadows, split1, split2, split3);
    }
    return std::make_unique<CurvesOp>(OpType::ParametricCurve, set);
}

std::unique_ptr<Op> makeLevels(const std::array<float, 20>& values) { return std::make_unique<LevelsOp>(values); }

std::unique_ptr<Op> makeColorGrading(const GradeWheel& shadows, const GradeWheel& midtones, const GradeWheel& highlights,
                                     const GradeWheel& global, float blending, float balance) {
    return std::make_unique<ColorGradingOp>(shadows, midtones, highlights, global, blending, balance);
}

std::unique_ptr<Op> makeColorBalance(const std::array<float, 3>& shadows, const std::array<float, 3>& midtones,
                                     const std::array<float, 3>& highlights, bool preserveLuminosity) {
    return std::make_unique<ColorBalanceOp>(shadows, midtones, highlights, preserveLuminosity);
}

std::unique_ptr<Op> makeSelectiveColor(const std::array<float, 36>& values, bool relative) {
    return std::make_unique<SelectiveColorOp>(values, relative);
}

std::unique_ptr<Op> makeChannelMixer(const std::array<float, 9>& matrixPercent, const std::array<float, 3>& constantPercent,
                                     bool monochrome) {
    float m[9], k[3];
    for (int r = 0; r < 3; ++r) {
        const int src = monochrome ? 0 : r;
        for (int c = 0; c < 3; ++c) m[3 * r + c] = matrixPercent[3 * src + c] / 100.f;
        k[r] = constantPercent[src] / 100.f;
    }
    bool identity = true;
    for (int i = 0; i < 9; ++i)
        if (m[i] != (i % 4 == 0 ? 1.f : 0.f)) identity = false;
    for (float x : k)
        if (x != 0.f) identity = false;
    return std::make_unique<MatrixOp>(OpType::ChannelMixer, m, k, identity);
}

std::unique_ptr<Op> makeBlackAndWhite(const std::array<float, 6>& mix, float tintHue, float tintSaturation) {
    return std::make_unique<BlackAndWhiteOp>(mix, tintHue, tintSaturation);
}

std::unique_ptr<Op> makePhotoFilter(float hue, float saturation, float density, bool preserveLuminosity) {
    return std::make_unique<PhotoFilterOp>(hue, saturation, density, preserveLuminosity);
}

std::unique_ptr<Op> makeGradientMap(const std::vector<std::array<float, 4>>& stops) { return std::make_unique<GradientMapOp>(stops); }

std::unique_ptr<Op> makeCalibration(const std::array<float, 3>& hue, const std::array<float, 3>& saturation) {
    double md[9];
    grade::calibrationMatrix(hue, saturation, md);
    float m[9];
    for (int i = 0; i < 9; ++i) m[i] = static_cast<float>(md[i]);
    const float k[3] = {0.f, 0.f, 0.f};
    const bool identity = hue == std::array<float, 3>{} && saturation == std::array<float, 3>{};
    return std::make_unique<MatrixOp>(OpType::Calibration, m, k, identity);
}

}  // namespace lo
