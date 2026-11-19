// Detail and Effects (Stage 10). Formulas: tools/golden/detail_reference.py; the C++ here,
// the GLSL below and the Python change together. CPU work is done in double (the
// reference computes in float64); the GPU runs the same arithmetic in float on RGBA16F.
#include "lineover/detail.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <functional>
#include <numeric>
#include <string>
#include <vector>

#include "op_util.h"

namespace lo {
namespace {

using opsutil::boxMean;

constexpr double kLuma[3] = {0.2627, 0.6780, 0.0593};
constexpr double kLumaEps = 1e-6;
constexpr int kMaxTaps = 12;

// Sharpen
constexpr double kSharpenLimitMin = 0.01;
constexpr double kSharpenLimitRange = 0.25;
constexpr double kSharpenMaskEdge = 0.02;
// Noise reduction
constexpr int kNrLevels = 4;
constexpr double kNrBase[kNrLevels] = {0.035, 0.02, 0.012, 0.006};
constexpr double kNrDetailKeep = 0.6;
constexpr double kNrContrast = 0.6;
constexpr double kB3[5] = {1.0 / 16, 4.0 / 16, 6.0 / 16, 4.0 / 16, 1.0 / 16};
constexpr double kYccR = 1.4746;
constexpr double kYccB = 1.8814;
constexpr double kNrSigmaY = 0.1;
// Clarity / Texture
constexpr double kClarityRadius = 0.02;
constexpr double kClarityEps = 0.04;
constexpr double kClarityStrength = 1.0;
constexpr double kTextureRadius = 0.003;
constexpr double kTextureEps = 0.005;
constexpr double kTextureStrength = 0.7;
// Dehaze (dehaze_reference.py)
constexpr double kOmega = 0.95;
constexpr double kT0 = 0.1;
constexpr double kTopFraction = 0.001;
constexpr double kPatchFraction = 0.01;
constexpr double kGuidedEps = 1e-3;
constexpr double kAirlightMin = 0.05;
// Grain
constexpr std::uint32_t kGrainSeed = 0x2545F491u;
constexpr double kGrainStrength = 0.12;

double enc(double x) {
    const double a = std::fabs(x);
    const double e = a <= 0.0031308 ? a * 12.92 : 1.055 * std::pow(a, 1.0 / 2.4) - 0.055;
    return x < 0 ? -e : (x > 0 ? e : 0.0);
}

double dec(double e) {
    const double a = std::fabs(e);
    const double x = a <= 0.04045 ? a / 12.92 : std::pow((a + 0.055) / 1.055, 2.4);
    return e < 0 ? -x : (e > 0 ? x : 0.0);
}

double smooth(double e0, double e1, double x) {
    const double t = std::clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

double luma(const float* p) { return kLuma[0] * p[0] + kLuma[1] * p[1] + kLuma[2] * p[2]; }

// A plane of doubles with clamped access, the buffer of reference.fetch / bilinear.
struct Plane {
    int w = 0, h = 0;
    std::vector<double> v;
    Plane(int width, int height) : w(width), h(height), v(static_cast<std::size_t>(width) * height) {}
    double& operator()(int x, int y) { return v[static_cast<std::size_t>(y) * w + x]; }
    double at(int x, int y) const {
        x = std::clamp(x, 0, w - 1);
        y = std::clamp(y, 0, h - 1);
        return v[static_cast<std::size_t>(y) * w + x];
    }
    double bilinear(double x, double y) const {
        const double x0 = std::floor(x), y0 = std::floor(y);
        const double fx = x - x0, fy = y - y0;
        const int ix = static_cast<int>(x0), iy = static_cast<int>(y0);
        const double top = at(ix, iy) * (1 - fx) + at(ix + 1, iy) * fx;
        const double bottom = at(ix, iy + 1) * (1 - fx) + at(ix + 1, iy + 1) * fx;
        return top * (1 - fy) + bottom * fy;
    }
};

std::vector<double> gaussianWeights(double sigma) {
    if (sigma < 0.01) return {1.0};
    const int k = std::min(kMaxTaps, static_cast<int>(std::ceil(3.0 * sigma)));
    std::vector<double> w(2 * k + 1);
    for (int i = -k; i <= k; ++i) w[i + k] = std::exp(-double(i) * i / (2.0 * sigma * sigma));
    const double sum = std::accumulate(w.begin(), w.end(), 0.0);
    for (double& x : w) x /= sum;
    return w;
}

Plane gaussian(const Plane& a, double sigma) {
    const std::vector<double> w = gaussianWeights(sigma);
    const int k = static_cast<int>(w.size()) / 2;
    Plane h(a.w, a.h), out(a.w, a.h);
    for (int y = 0; y < a.h; ++y)
        for (int x = 0; x < a.w; ++x) {
            double s = 0;
            for (int i = 0; i < static_cast<int>(w.size()); ++i) s += w[i] * a.at(x + i - k, y);
            h(x, y) = s;
        }
    for (int y = 0; y < a.h; ++y)
        for (int x = 0; x < a.w; ++x) {
            double s = 0;
            for (int i = 0; i < static_cast<int>(w.size()); ++i) s += w[i] * h.at(x, y + i - k);
            out(x, y) = s;
        }
    return out;
}

void scaleRgb(const float* p, float* q, double yNew, double yOld) {
    const double r = yOld > kLumaEps ? yNew / yOld : 1.0;
    for (int c = 0; c < 3; ++c) q[c] = static_cast<float>(p[c] * r);
    q[3] = p[3];
}

// The image uv of buffer pixel (i, j).
void uvOf(const OpRegion& r, int i, int j, float* u, float* v) {
    *u = r.rect[0] + (static_cast<float>(i) + 0.5f) / r.width * r.rect[2];
    *v = r.rect[1] + (static_cast<float>(j) + 0.5f) / r.height * r.rect[3];
}

// GLSL: luminance and encoded luminance.
#define LO_GLSL_DETAIL                                                \
    LO_GLSL_ENCODE                                                    \
    "const vec3 LO_LUMA = vec3(0.2627, 0.6780, 0.0593);\n"            \
    "float lo_pl(vec4 c) { return lo_enc1(max(dot(c.rgb, LO_LUMA), 0.0)); }\n"

// Spatial ops have no neighbours in cpuRef(pixels): run as one row.
#define LO_CPUREF_AS_ROW                                                               \
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {     \
        OpRegion r;                                                                   \
        r.width = static_cast<int>(pixels);                                           \
        r.height = 1;                                                                 \
        cpuRefRegion(in, out, r);                                                     \
    }

// ------------------------------------------------------------------------- Sharpen

class SharpenOp final : public Op {
public:
    SharpenOp(float amount, float radius, float detail, float masking, bool showMask)
        : amount_(std::clamp(amount, 0.f, 150.f)),
          radius_(std::clamp(radius, 0.5f, 3.f)),
          detail_(std::clamp(detail, 0.f, 100.f)),
          masking_(std::clamp(masking, 0.f, 100.f)),
          showMask_(showMask) {}
    OpType type() const override { return OpType::Sharpen; }
    bool isIdentity() const override { return amount_ == 0.f && !showMask_; }
    int apron() const override { return std::min(kMaxTaps, static_cast<int>(std::ceil(3.0 * radius_))) + 2; }
    LO_CPUREF_AS_ROW
    void cpuRefRegion(const float* in, float* out, const OpRegion& region) const override {
        const int w = region.width, h = region.height;
        const double scale = region.scale;
        Plane p(w, h);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) p(x, y) = enc(std::max(luma(in + 4 * (std::size_t(y) * w + x)), 0.0));
        const Plane b = gaussian(p, radius_ * scale);
        const double limit = kSharpenLimitMin + kSharpenLimitRange * std::pow(detail_ / 100.0, 2);
        const double t = masking_ / 100.0 * kSharpenMaskEdge;
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                double mask = 1.0;
                if (masking_ > 0.f) {
                    const double gx = (b.at(x + 1, y - 1) + 2 * b.at(x + 1, y) + b.at(x + 1, y + 1) - b.at(x - 1, y - 1) -
                                       2 * b.at(x - 1, y) - b.at(x - 1, y + 1)) / 8.0;
                    const double gy = (b.at(x - 1, y + 1) + 2 * b.at(x, y + 1) + b.at(x + 1, y + 1) - b.at(x - 1, y - 1) -
                                       2 * b.at(x, y - 1) - b.at(x + 1, y - 1)) / 8.0;
                    mask = smooth(0.5 * t, 1.5 * t, std::sqrt(gx * gx + gy * gy) * scale);
                }
                const std::size_t k = 4 * (std::size_t(y) * w + x);
                if (showMask_) {
                    const float m = static_cast<float>(dec(mask));
                    out[k] = out[k + 1] = out[k + 2] = m;
                    out[k + 3] = in[k + 3];
                    continue;
                }
                const double d = limit * std::tanh((p(x, y) - b.at(x, y)) / limit);
                const double p2 = p(x, y) + amount_ / 100.0 * d * mask;
                scaleRgb(in + k, out + k, dec(p2), luma(in + k));
            }
    }
    void gpu(GpuOpContext& c) const override {
        // Pass 0 / 1: the separable Gaussian of P (horizontal, then vertical) in .r.
        static const char* kDecl = LO_GLSL_DETAIL
            "uniform float u_radius;\nuniform float u_amount;\nuniform float u_limit;\nuniform float u_maskT;\n"
            "uniform float u_showMask;\n"
            "float lo_gauss(highp sampler2D s, ivec2 p, ivec2 dir, bool fromColor) {\n"
            "    float sigma = u_radius * u_scale;\n"
            "    if (sigma < 0.01) return fromColor ? lo_pl(lo_at(s, p)) : lo_at(s, p).r;\n"
            "    float k = min(12.0, ceil(3.0 * sigma));\n"
            "    float sum = 0.0, ws = 0.0;\n"
            "    for (int i = -12; i <= 12; ++i) {\n"
            "        float x = float(i);\n"
            "        if (abs(x) > k) continue;\n"
            "        float w = exp(-x * x / (2.0 * sigma * sigma));\n"
            "        vec4 t = lo_at(s, p + dir * i);\n"
            "        sum += w * (fromColor ? lo_pl(t) : t.r);\n"
            "        ws += w;\n"
            "    }\n"
            "    return sum / ws;\n"
            "}\n";
        c.addPass(kDecl, "return vec4(lo_gauss(u_source, lo_px(), ivec2(1, 0), true), 0.0, 0.0, 1.0);");
        c.addPass(kDecl, "return vec4(lo_gauss(u_prev, lo_px(), ivec2(0, 1), false), 0.0, 0.0, 1.0);");
        c.addPass(kDecl,
                  "ivec2 p = lo_px();\n"
                  "vec4 c = lo_at(u_source, p);\n"
                  "float b = lo_at(u_prev, p).r;\n"
                  "float mask = 1.0;\n"
                  "if (u_maskT > 0.0) {\n"
                  "    float b00 = lo_at(u_prev, p + ivec2(-1, -1)).r, b10 = lo_at(u_prev, p + ivec2(0, -1)).r;\n"
                  "    float b20 = lo_at(u_prev, p + ivec2(1, -1)).r, b01 = lo_at(u_prev, p + ivec2(-1, 0)).r;\n"
                  "    float b21 = lo_at(u_prev, p + ivec2(1, 0)).r, b02 = lo_at(u_prev, p + ivec2(-1, 1)).r;\n"
                  "    float b12 = lo_at(u_prev, p + ivec2(0, 1)).r, b22 = lo_at(u_prev, p + ivec2(1, 1)).r;\n"
                  "    float gx = (b20 + 2.0 * b21 + b22 - b00 - 2.0 * b01 - b02) / 8.0;\n"
                  "    float gy = (b02 + 2.0 * b12 + b22 - b00 - 2.0 * b10 - b20) / 8.0;\n"
                  "    mask = smoothstep(0.5 * u_maskT, 1.5 * u_maskT, length(vec2(gx, gy)) * u_scale);\n"
                  "}\n"
                  "if (u_showMask > 0.5) return vec4(vec3(lo_dec1(mask)), c.a);\n"
                  "float y = dot(c.rgb, LO_LUMA);\n"
                  "if (y <= 1e-6) return c;\n"
                  "float pl = lo_enc1(y);\n"
                  "float d = u_limit * tanh((pl - b) / u_limit);\n"
                  "return vec4(c.rgb * (lo_dec1(pl + u_amount * d * mask) / y), c.a);");
        c.setUniform1f("u_radius", radius_);
        c.setUniform1f("u_amount", amount_ / 100.f);
        c.setUniform1f("u_limit", static_cast<float>(kSharpenLimitMin + kSharpenLimitRange * std::pow(detail_ / 100.0, 2)));
        c.setUniform1f("u_maskT", static_cast<float>(masking_ / 100.0 * kSharpenMaskEdge));
        c.setUniform1f("u_showMask", showMask_ ? 1.f : 0.f);
    }

private:
    float amount_, radius_, detail_, masking_;
    bool showMask_;
};

// ----------------------------------------------------------------- Noise reduction

class NoiseReductionOp final : public Op {
public:
    NoiseReductionOp(float luminance, float detail, float contrast, float color)
        : lum_(std::clamp(luminance, 0.f, 100.f)),
          detail_(std::clamp(detail, 0.f, 100.f)),
          contrast_(std::clamp(contrast, 0.f, 100.f)),
          color_(std::clamp(color, 0.f, 100.f)) {}
    OpType type() const override { return OpType::NoiseReduction; }
    bool isIdentity() const override { return !wavelets() && color_ == 0.f; }
    int apron() const override {
        // a-trous: 2 * (1 + 2 + 4 + 8) plus the bilinear tap; bilateral: its radius plus a tap.
        return 2 * 15 + 2 + static_cast<int>(std::ceil(bilateralRadius())) + 1;
    }
    LO_CPUREF_AS_ROW
    void cpuRefRegion(const float* in, float* out, const OpRegion& region) const override {
        const int w = region.width, h = region.height;
        const double scale = region.scale;
        Plane ey(w, h), cb(w, h), cr(w, h);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const float* p = in + 4 * (std::size_t(y) * w + x);
                const double e[3] = {enc(p[0]), enc(p[1]), enc(p[2])};
                const double l = kLuma[0] * e[0] + kLuma[1] * e[1] + kLuma[2] * e[2];
                ey(x, y) = l;
                cb(x, y) = (e[2] - l) / kYccB;
                cr(x, y) = (e[0] - l) / kYccR;
            }
        Plane ey2 = ey;
        if (wavelets()) {
            Plane c = ey, acc(w, h), removed(w, h);
            for (int k = 0; k < kNrLevels; ++k) {
                const double spacing = std::exp2(k) * scale;
                Plane next(w, h);
                for (int y = 0; y < h; ++y)
                    for (int x = 0; x < w; ++x) {
                        double s = 0;
                        for (int j = 0; j < 5; ++j)
                            for (int i = 0; i < 5; ++i)
                                s += kB3[i] * kB3[j] * c.bilinear(x + (i - 2) * spacing, y + (j - 2) * spacing);
                        next(x, y) = s;
                    }
                const double keep = k < 2 ? 1.0 - kNrDetailKeep * detail_ / 100.0 : 1.0;
                const double t = lum_ / 100.0 * kNrBase[k] * keep;
                for (std::size_t i = 0; i < c.v.size(); ++i) {
                    const double wv = c.v[i] - next.v[i];
                    const double s = (wv < 0 ? -1.0 : (wv > 0 ? 1.0 : 0.0)) * std::max(std::fabs(wv) - t, 0.0);
                    acc.v[i] += s;
                    if (k >= 2) removed.v[i] += wv - s;
                }
                c = std::move(next);
            }
            for (std::size_t i = 0; i < c.v.size(); ++i)
                ey2.v[i] = c.v[i] + acc.v[i] + contrast_ / 100.0 * kNrContrast * removed.v[i];
        }
        Plane cb2 = cb, cr2 = cr;
        if (color_ > 0.f) {
            const double radius = bilateralRadius();
            const double step = radius * scale / 3.0;
            const double sigmaS = radius / 2.0;
            const double sigmaC = 0.01 + 0.05 * color_ / 100.0;
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    double nb = 0, nr = 0, den = 0;
                    const double b0 = cb(x, y), r0 = cr(x, y), y0 = ey(x, y);
                    for (int j = -3; j <= 3; ++j)
                        for (int i = -3; i <= 3; ++i) {
                            const double sx = x + i * step, sy = y + j * step;
                            const double sb = cb.bilinear(sx, sy), sr = cr.bilinear(sx, sy), sl = ey.bilinear(sx, sy);
                            const double dist2 = (i * i + j * j) * (radius / 3.0) * (radius / 3.0);
                            const double wgt = std::exp(-dist2 / (2 * sigmaS * sigmaS)) *
                                               std::exp(-((sb - b0) * (sb - b0) + (sr - r0) * (sr - r0)) / (2 * sigmaC * sigmaC)) *
                                               std::exp(-((sl - y0) * (sl - y0)) / (2 * kNrSigmaY * kNrSigmaY));
                            nb += wgt * sb;
                            nr += wgt * sr;
                            den += wgt;
                        }
                    cb2(x, y) = nb / den;
                    cr2(x, y) = nr / den;
                }
        }
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const std::size_t k = 4 * (std::size_t(y) * w + x);
                const double l = ey2(x, y);
                const double er = l + kYccR * cr2(x, y), eb = l + kYccB * cb2(x, y);
                const double eg = (l - kLuma[0] * er - kLuma[2] * eb) / kLuma[1];
                const float a = in[k + 3];
                out[k] = static_cast<float>(dec(er));
                out[k + 1] = static_cast<float>(dec(eg));
                out[k + 2] = static_cast<float>(dec(eb));
                out[k + 3] = a;
            }
    }
    void gpu(GpuOpContext& c) const override {
        static const char* kDecl = LO_GLSL_DETAIL
            "uniform float u_lum;\nuniform float u_keep;\nuniform float u_contrast;\nuniform float u_color;\n"
            "uniform float u_bRadius;\nuniform float u_sigmaC;\n"
            "const float LO_B3[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);\n"
            "vec4 lo_level(float level, float base, bool first) {\n"
            "    ivec2 p = lo_px();\n"
            "    vec4 prev = lo_at(u_prev, p);\n"
            "    float sp = exp2(level) * u_scale;\n"
            "    vec2 x = vec2(p);\n"
            "    float nxt = 0.0;\n"
            "    for (int j = 0; j < 5; ++j)\n"
            "        for (int i = 0; i < 5; ++i)\n"
            "            nxt += LO_B3[i] * LO_B3[j] * lo_bilinear(u_prev, x + vec2(float(i - 2), float(j - 2)) * sp).r;\n"
            "    float acc = first ? 0.0 : prev.g, removed = first ? 0.0 : prev.b;\n"
            "    float w = prev.r - nxt;\n"
            "    float t = u_lum * base * (level < 2.0 ? u_keep : 1.0);\n"
            "    float s = sign(w) * max(abs(w) - t, 0.0);\n"
            "    return vec4(nxt, acc + s, removed + (level >= 2.0 ? w - s : 0.0), 1.0);\n"
            "}\n";
        c.addPass(kDecl,
                  "vec4 c = lo_at(u_source, lo_px());\n"
                  "vec3 e = lo_enc(c.rgb);\n"
                  "float l = dot(e, LO_LUMA);\n"
                  "return vec4(l, (e.b - l) / 1.8814, (e.r - l) / 1.4746, 1.0);");
        const bool wave = wavelets();
        if (wave) {
            c.addPass(kDecl, "return lo_level(0.0, 0.035, true);");
            c.addPass(kDecl, "return lo_level(1.0, 0.02, false);");
            c.addPass(kDecl, "return lo_level(2.0, 0.012, false);");
            c.addPass(kDecl, "return lo_level(3.0, 0.006, false);");
        }
        const std::string last = std::string("ivec2 p = lo_px();\n"
                                             "vec4 c = lo_at(u_source, p);\n"
                                             "vec4 y0 = lo_at(u_pass0, p);\n") +
                                 (wave ? "vec4 lv = lo_at(u_prev, p);\n"
                                         "float l = lv.r + lv.g + u_contrast * 0.6 * lv.b;\n"
                                       : "float l = y0.r;\n") +
                                 "vec2 ch = y0.gb;\n"
                                 "if (u_color > 0.0) {\n"
                                 "    float step = u_bRadius * u_scale / 3.0;\n"
                                 "    float sigS = u_bRadius / 2.0;\n"
                                 "    vec3 num = vec3(0.0);\n"
                                 "    for (int j = -3; j <= 3; ++j)\n"
                                 "        for (int i = -3; i <= 3; ++i) {\n"
                                 "            vec3 s = lo_bilinear(u_pass0, vec2(p) + vec2(float(i), float(j)) * step).rgb;\n"
                                 "            float d2 = float(i * i + j * j) * (u_bRadius / 3.0) * (u_bRadius / 3.0);\n"
                                 "            vec2 dc = s.gb - y0.gb;\n"
                                 "            float dy = s.r - y0.r;\n"
                                 "            float w = exp(-d2 / (2.0 * sigS * sigS)) * exp(-dot(dc, dc) / (2.0 * u_sigmaC * u_sigmaC))\n"
                                 "                * exp(-dy * dy / 0.02);\n"
                                 "            num += w * vec3(s.gb, 1.0);\n"
                                 "        }\n"
                                 "    ch = num.xy / num.z;\n"
                                 "}\n"
                                 "float er = l + 1.4746 * ch.y, eb = l + 1.8814 * ch.x;\n"
                                 "float eg = (l - 0.2627 * er - 0.0593 * eb) / 0.6780;\n"
                                 "return vec4(lo_dec(vec3(er, eg, eb)), c.a);";
        c.addPass(kDecl, last.c_str());
        c.setUniform1f("u_lum", lum_ / 100.f);
        c.setUniform1f("u_keep", static_cast<float>(1.0 - kNrDetailKeep * detail_ / 100.0));
        c.setUniform1f("u_contrast", contrast_ / 100.f);
        c.setUniform1f("u_color", color_);
        c.setUniform1f("u_bRadius", static_cast<float>(bilateralRadius()));
        c.setUniform1f("u_sigmaC", static_cast<float>(0.01 + 0.05 * color_ / 100.0));
    }

private:
    bool wavelets() const { return lum_ > 0.f || contrast_ > 0.f; }
    double bilateralRadius() const { return 1.0 + 6.0 * color_ / 100.0; }
    float lum_, detail_, contrast_, color_;
};

// ----------------------------------------------------------- Clarity and Texture

class LocalContrastOp final : public Op {
public:
    LocalContrastOp(OpType type, float amount) : type_(type), amount_(std::clamp(amount, -100.f, 100.f)) {}
    OpType type() const override { return type_; }
    bool isIdentity() const override { return amount_ == 0.f; }
    bool readsOriginal() const override { return true; }
    // Without fields there is no base: unchanged.
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        if (in != out) std::memcpy(out, in, pixels * 4 * sizeof(float));
    }
    void cpuRefRegion(const float* in, float* out, const OpRegion& region) const override {
        const GuidedCoefficients* f = field(region.fields);
        if (f == nullptr || f->ab.empty()) {
            cpuRef(in, out, region.pixels());
            return;
        }
        const float* orig = region.original != nullptr ? region.original : in;
        const bool clarity = type_ == OpType::Clarity;
        const double k = amount_ / 100.0 * (clarity ? kClarityStrength : kTextureStrength);
        for (int j = 0; j < region.height; ++j)
            for (int i = 0; i < region.width; ++i) {
                float u, v, a, b;
                uvOf(region, i, j, &u, &v);
                sampleGuided(*f, u, v, &a, &b);
                const std::size_t p = 4 * (std::size_t(j) * region.width + i);
                const double l0 = std::log2(std::max(luma(orig + p), kLumaEps));
                const double d = l0 - (a * l0 + b);
                const double y = luma(in + p);
                double weight = 1.0;
                if (clarity) {
                    const double pc = std::clamp(enc(std::max(y, 0.0)), 0.0, 1.0);
                    weight = 4.0 * pc * (1.0 - pc);
                }
                const double l = std::log2(std::max(y, kLumaEps)) + k * d * weight;
                scaleRgb(in + p, out + p, std::exp2(l), y);
            }
    }
    void gpu(GpuOpContext& c) const override {
        const bool clarity = type_ == OpType::Clarity;
        c.setShader(LO_GLSL_DETAIL "uniform float u_k;",
                    clarity ? "vec2 ab = lo_detailFields().xy;\n"
                              "float l0 = log2(max(dot(lo_original().rgb, LO_LUMA), 1e-6));\n"
                              "float y = dot(c.rgb, LO_LUMA);\n"
                              "if (y <= 1e-6) return c;\n"
                              "float pc = clamp(lo_enc1(y), 0.0, 1.0);\n"
                              "float l = log2(y) + u_k * (l0 - (ab.x * l0 + ab.y)) * 4.0 * pc * (1.0 - pc);\n"
                              "return vec4(c.rgb * (exp2(l) / y), c.a);"
                            : "vec2 ab = lo_detailFields().zw;\n"
                              "float l0 = log2(max(dot(lo_original().rgb, LO_LUMA), 1e-6));\n"
                              "float y = dot(c.rgb, LO_LUMA);\n"
                              "if (y <= 1e-6) return c;\n"
                              "float l = log2(y) + u_k * (l0 - (ab.x * l0 + ab.y));\n"
                              "return vec4(c.rgb * (exp2(l) / y), c.a);");
        c.setUniform1f("u_k", static_cast<float>(amount_ / 100.0 * (clarity ? kClarityStrength : kTextureStrength)));
    }

private:
    const GuidedCoefficients* field(const DetailFields* f) const {
        if (f == nullptr) return nullptr;
        return type_ == OpType::Clarity ? &f->clarity : &f->texture;
    }
    OpType type_;
    float amount_;
};

// --------------------------------------------------------------------------- Dehaze

class DehazeOp final : public Op {
public:
    explicit DehazeOp(float amount) : amount_(std::clamp(amount, -100.f, 100.f)) {}
    OpType type() const override { return OpType::Dehaze; }
    bool isIdentity() const override { return amount_ == 0.f; }
    bool readsOriginal() const override { return true; }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        if (in != out) std::memcpy(out, in, pixels * 4 * sizeof(float));
    }
    void cpuRefRegion(const float* in, float* out, const OpRegion& region) const override {
        const DetailFields* f = region.fields;
        if (f == nullptr || f->haze.ab.empty()) {
            cpuRef(in, out, region.pixels());
            return;
        }
        const float* orig = region.original != nullptr ? region.original : in;
        const double amount = amount_ / 100.0;
        for (int j = 0; j < region.height; ++j)
            for (int i = 0; i < region.width; ++i) {
                float u, v, a, b;
                uvOf(region, i, j, &u, &v);
                sampleGuided(f->haze, u, v, &a, &b);
                const std::size_t p = 4 * (std::size_t(j) * region.width + i);
                double grey = 0;
                for (int c = 0; c < 3; ++c) grey += kLuma[c] * std::clamp(enc(orig[p + c]), 0.0, 1.0);
                const double g = a * grey + b;
                const double t = amount >= 0 ? std::max(1.0 - kOmega * amount * g, kT0)
                                             : 1.0 - (-amount) * (0.25 + 0.5 * std::clamp(g, 0.0, 1.0));
                for (int c = 0; c < 3; ++c) {
                    const double e = enc(in[p + c]);
                    const double air = f->airlight[c];
                    const double jv = amount >= 0 ? (e - air) / t + air : e * t + air * (1.0 - t);
                    out[p + c] = static_cast<float>(dec(jv));
                }
                out[p + 3] = in[p + 3];
            }
    }
    void gpu(GpuOpContext& c) const override {
        c.setShader(LO_GLSL_DETAIL "uniform float u_amount;",
                    "vec2 ab = lo_hazeField();\n"
                    "float grey = dot(clamp(lo_enc(lo_original().rgb), 0.0, 1.0), LO_LUMA);\n"
                    "float g = ab.x * grey + ab.y;\n"
                    "vec3 e = lo_enc(c.rgb);\n"
                    "vec3 j;\n"
                    "if (u_amount >= 0.0) {\n"
                    "    float t = max(1.0 - 0.95 * u_amount * g, 0.1);\n"
                    "    j = (e - u_air) / t + u_air;\n"
                    "} else {\n"
                    "    float t = 1.0 + u_amount * (0.25 + 0.5 * clamp(g, 0.0, 1.0));\n"
                    "    j = e * t + u_air * (1.0 - t);\n"
                    "}\n"
                    "return vec4(lo_dec(j), c.a);");
        c.setUniform1f("u_amount", amount_ / 100.f);
    }

private:
    float amount_;
};

// --------------------------------------------------------------------------- fields

int fieldRadius(double fraction, int w, int h) {
    return std::max(1, static_cast<int>(std::floor(fraction * std::max(w, h) + 0.5)));
}

GuidedCoefficients pack(const std::vector<double>& a, const std::vector<double>& b, int w, int h) {
    GuidedCoefficients out;
    out.width = w;
    out.height = h;
    out.ab.resize(2 * a.size());
    for (std::size_t i = 0; i < a.size(); ++i) {
        out.ab[2 * i] = static_cast<float>(a[i]);
        out.ab[2 * i + 1] = static_cast<float>(b[i]);
    }
    return out;
}

// Guided filter of p with guide g (grey): coefficients a, b box-filtered (as
// dehaze_reference.guided_coefficients; p == g is the self-guided filter).
GuidedCoefficients guided(const std::vector<double>& g, const std::vector<double>& p, int w, int h, int r, double eps) {
    const std::size_t n = g.size();
    std::vector<double> mi = g, mp = p, mip(n), mii(n);
    for (std::size_t i = 0; i < n; ++i) {
        mip[i] = g[i] * p[i];
        mii[i] = g[i] * g[i];
    }
    boxMean(mi, w, h, r);
    boxMean(mp, w, h, r);
    boxMean(mip, w, h, r);
    boxMean(mii, w, h, r);
    for (std::size_t i = 0; i < n; ++i) {
        const double cov = mip[i] - mi[i] * mp[i];
        const double var = mii[i] - mi[i] * mi[i];
        const double a = cov / (var + eps);
        mip[i] = a;
        mii[i] = mp[i] - a * mi[i];
    }
    boxMean(mip, w, h, r);
    boxMean(mii, w, h, r);
    return pack(mip, mii, w, h);
}

// Minimum over the (2r+1)^2 window clipped to the image, separably (in place).
void minFilter(std::vector<double>& a, int w, int h, int r) {
    std::vector<double> line;
    for (int y = 0; y < h; ++y) {
        line.assign(a.begin() + std::ptrdiff_t(y) * w, a.begin() + std::ptrdiff_t(y + 1) * w);
        for (int x = 0; x < w; ++x) {
            double m = line[x];
            for (int k = std::max(0, x - r); k <= std::min(w - 1, x + r); ++k) m = std::min(m, line[k]);
            a[std::size_t(y) * w + x] = m;
        }
    }
    for (int x = 0; x < w; ++x) {
        line.resize(h);
        for (int y = 0; y < h; ++y) line[y] = a[std::size_t(y) * w + x];
        for (int y = 0; y < h; ++y) {
            double m = line[y];
            for (int k = std::max(0, y - r); k <= std::min(h - 1, y + r); ++k) m = std::min(m, line[k]);
            a[std::size_t(y) * w + x] = m;
        }
    }
}

std::vector<double> darkChannel(const std::vector<double>& rgb, int w, int h, int r, const double* divide) {
    std::vector<double> d(rgb.size() / 3);
    for (std::size_t i = 0; i < d.size(); ++i)
        d[i] = std::min({rgb[3 * i] / divide[0], rgb[3 * i + 1] / divide[1], rgb[3 * i + 2] / divide[2]});
    minFilter(d, w, h, r);
    return d;
}

}  // namespace

DetailFields computeDetailFields(const float* rgba, int width, int height) {
    DetailFields out;
    if (width <= 0 || height <= 0) return out;
    const std::size_t n = std::size_t(width) * height;
    std::vector<double> lum(n), rgb(3 * n), grey(n);
    for (std::size_t i = 0; i < n; ++i) {
        const float* p = rgba + 4 * i;
        lum[i] = std::log2(std::max(luma(p), kLumaEps));
        for (int c = 0; c < 3; ++c) rgb[3 * i + c] = std::clamp(enc(p[c]), 0.0, 1.0);
    }
    out.clarity = guided(lum, lum, width, height, fieldRadius(kClarityRadius, width, height), kClarityEps);
    out.texture = guided(lum, lum, width, height, fieldRadius(kTextureRadius, width, height), kTextureEps);

    // Haze model (dehaze_reference.haze_model).
    const int r = fieldRadius(kPatchFraction, width, height);
    const double one[3] = {1.0, 1.0, 1.0};
    const std::vector<double> dark = darkChannel(rgb, width, height, r, one);
    // The brightest 0.1 % of the dark channel in stable descending order.
    const std::size_t top = std::max<std::size_t>(1, static_cast<std::size_t>(std::floor(kTopFraction * n)));
    std::vector<double> sorted = dark;
    std::nth_element(sorted.begin(), sorted.begin() + std::ptrdiff_t(top - 1), sorted.end(), std::greater<double>());
    const double threshold = sorted[top - 1];
    std::vector<std::size_t> picked;
    for (std::size_t i = 0; i < n; ++i)
        if (dark[i] > threshold) picked.push_back(i);
    for (std::size_t i = 0; i < n && picked.size() < top; ++i)
        if (dark[i] == threshold) picked.push_back(i);
    std::stable_sort(picked.begin(), picked.end(), [&](std::size_t a, std::size_t b) {
        return dark[a] > dark[b] || (dark[a] == dark[b] && a < b);
    });
    std::size_t best = picked[0];
    double bestSum = -1.0;
    for (std::size_t i : picked) {
        const double s = rgb[3 * i] + rgb[3 * i + 1] + rgb[3 * i + 2];
        if (s > bestSum) {
            bestSum = s;
            best = i;
        }
    }
    double air[3];
    for (int c = 0; c < 3; ++c) {
        air[c] = std::max(rgb[3 * best + c], kAirlightMin);
        out.airlight[c] = static_cast<float>(air[c]);
    }
    const std::vector<double> normalised = darkChannel(rgb, width, height, r, air);
    for (std::size_t i = 0; i < n; ++i) grey[i] = kLuma[0] * rgb[3 * i] + kLuma[1] * rgb[3 * i + 1] + kLuma[2] * rgb[3 * i + 2];
    out.haze = guided(grey, normalised, width, height, 4 * r, kGuidedEps);
    return out;
}

std::unique_ptr<Op> makeSharpen(float amount, float radius, float detail, float masking, bool showMask) {
    return std::make_unique<SharpenOp>(amount, radius, detail, masking, showMask);
}
std::unique_ptr<Op> makeNoiseReduction(float luminance, float detail, float contrast, float color) {
    return std::make_unique<NoiseReductionOp>(luminance, detail, contrast, color);
}
std::unique_ptr<Op> makeClarity(float amount) { return std::make_unique<LocalContrastOp>(OpType::Clarity, amount); }
std::unique_ptr<Op> makeTexture(float amount) { return std::make_unique<LocalContrastOp>(OpType::Texture, amount); }
std::unique_ptr<Op> makeDehaze(float amount) { return std::make_unique<DehazeOp>(amount); }

// ------------------------------------------------------------------------ finishing

bool finishingOf(const Graph& graph, Finishing* out) {
    Finishing f;
    if (graph.layers.empty()) return false;
    for (const OpParams& p : graph.layers.front().ops) {
        auto v = [&](const char* key, float fallback) {
            for (const auto& x : p.params)
                if (x.first == key) return static_cast<float>(x.second);
            return fallback;
        };
        if (p.op == "post_crop_vignette") {
            f.vignette = std::clamp(v("amount", 0.f), -100.f, 100.f);
            f.midpoint = std::clamp(v("midpoint", 50.f), 0.f, 100.f);
            f.roundness = std::clamp(v("roundness", 0.f), -100.f, 100.f);
            f.feather = std::clamp(v("feather", 50.f), 0.f, 100.f);
            f.highlights = std::clamp(v("highlights", 0.f), 0.f, 100.f);
        } else if (p.op == "grain") {
            f.grain = std::clamp(v("amount", 0.f), 0.f, 100.f);
            f.grainSize = std::clamp(v("size", 25.f), 0.f, 100.f);
            f.roughness = std::clamp(v("roughness", 50.f), 0.f, 100.f);
        }
    }
    if (out != nullptr) *out = f;
    return f.active();
}

namespace {

std::uint32_t grainHash(std::int64_t ix, std::int64_t iy, std::uint32_t seed) {
    std::uint32_t h = static_cast<std::uint32_t>(ix) * 0x8DA6B343u;
    h ^= static_cast<std::uint32_t>(iy) * 0xD8163841u;
    h ^= seed * 0xCB1AB31Fu;
    h ^= h >> 16;
    h *= 0x7FEB352Du;
    h ^= h >> 15;
    h *= 0x846CA68Bu;
    h ^= h >> 16;
    return h;
}

constexpr double kDiag = 0.7071067811865476;
constexpr double kGradients[8][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {kDiag, kDiag}, {-kDiag, kDiag}, {kDiag, -kDiag}, {-kDiag, -kDiag}};

double perlin(double x, double y, std::uint32_t seed) {
    const double x0 = std::floor(x), y0 = std::floor(y);
    const double fx = x - x0, fy = y - y0;
    const auto ix = static_cast<std::int64_t>(x0), iy = static_cast<std::int64_t>(y0);
    auto corner = [&](int dx, int dy) {
        const double* g = kGradients[grainHash(ix + dx, iy + dy, seed) & 7u];
        return g[0] * (fx - dx) + g[1] * (fy - dy);
    };
    auto fade = [](double t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); };
    const double u = fade(fx), v = fade(fy);
    const double c00 = corner(0, 0), c10 = corner(1, 0), c01 = corner(0, 1), c11 = corner(1, 1);
    const double top = c00 + (c10 - c00) * u;
    const double bottom = c01 + (c11 - c01) * u;
    return top + (bottom - top) * v;
}

}  // namespace

void applyFinishing(const Finishing& f, float* rgba, int width, int height, double outWidth, double outHeight, double originX,
                    double originY, double scale) {
    if (!f.active() || outWidth <= 0 || outHeight <= 0 || scale <= 0) return;
    const double aspect = outWidth / outHeight;
    const double rho = f.roundness / 100.0;
    const double sx = aspect >= 1.0 ? 1.0 + (aspect - 1.0) * std::max(rho, 0.0) : 1.0;
    const double sy = aspect < 1.0 ? 1.0 + (1.0 / aspect - 1.0) * std::max(rho, 0.0) : 1.0;
    const double pw = 2.0 + 6.0 * std::max(-rho, 0.0);
    const double r0 = 0.3 + 0.9 * f.midpoint / 100.0;
    const double r1 = r0 + 0.05 + 0.95 * f.feather / 100.0;
    const double k = f.vignette / 100.0;
    const double footprint = 1.0 / scale;
    const double cell = 1.0 + 3.0 * f.grainSize / 100.0;
    const double rough = f.roughness / 100.0;
    const double att1 = std::min(1.0, cell / footprint), att2 = std::min(1.0, 0.5 * cell / footprint);
    for (int j = 0; j < height; ++j)
        for (int i = 0; i < width; ++i) {
            float* p = rgba + 4 * (std::size_t(j) * width + i);
            const double xf = originX + (i + 0.5) / scale, yf = originY + (j + 0.5) / scale;
            double e[3] = {enc(p[0]), enc(p[1]), enc(p[2])};
            if (k != 0.0) {
                const double qx = std::fabs(xf / outWidth - 0.5) * 2.0, qy = std::fabs(yf / outHeight - 0.5) * 2.0;
                const double d = std::pow(std::pow(qx * sx, pw) + std::pow(qy * sy, pw), 1.0 / pw);
                const double t = smooth(r0, r1, d);
                if (k < 0) {
                    const double ey = std::clamp(kLuma[0] * e[0] + kLuma[1] * e[1] + kLuma[2] * e[2], 0.0, 1.0);
                    const double keep = f.highlights / 100.0 * smooth(0.7, 1.0, ey);
                    for (double& c : e) {
                        const double darker = c * (1.0 + k * t);
                        c = darker + (c - darker) * keep;
                    }
                } else {
                    for (double& c : e) c = c + std::max(1.0 - c, 0.0) * k * t;
                }
            }
            if (f.grain != 0.f) {
                const double n1 = perlin(xf / cell, yf / cell, kGrainSeed) * att1;
                const double n2 = perlin(2.0 * xf / cell, 2.0 * yf / cell, kGrainSeed + 1) * att2;
                const double n = (n1 + rough * n2) / (1.0 + 0.5 * rough);
                const double ey = std::clamp(kLuma[0] * e[0] + kLuma[1] * e[1] + kLuma[2] * e[2], 0.0, 1.0);
                const double weight = 0.25 + 3.0 * ey * (1.0 - ey);
                for (double& c : e) c += f.grain / 100.0 * kGrainStrength * n * weight;
            }
            for (int c = 0; c < 3; ++c) p[c] = static_cast<float>(dec(e[c]));
        }
}

const char* const kFinishingGlsl =
    "uniform float u_vig[5];\nuniform float u_grain[3];\nuniform vec2 u_finOut;\n"
    "float lo_fEnc(float x) { float a = abs(x);\n"
    "    return sign(x) * (a <= 0.0031308 ? a * 12.92 : 1.055 * pow(a, 1.0 / 2.4) - 0.055); }\n"
    "float lo_fDec(float e) { float a = abs(e);\n"
    "    return sign(e) * (a <= 0.04045 ? a / 12.92 : pow((a + 0.055) / 1.055, 2.4)); }\n"
    "uint lo_grainHash(int ix, int iy, uint seed) {\n"
    "    uint h = uint(ix) * 0x8DA6B343u;\n"
    "    h ^= uint(iy) * 0xD8163841u;\n"
    "    h ^= seed * 0xCB1AB31Fu;\n"
    "    h ^= h >> 16; h *= 0x7FEB352Du; h ^= h >> 15; h *= 0x846CA68Bu; h ^= h >> 16;\n"
    "    return h;\n"
    "}\n"
    "float lo_corner(int ix, int iy, vec2 f, int dx, int dy, uint seed) {\n"
    "    uint k = lo_grainHash(ix + dx, iy + dy, seed) & 7u;\n"
    "    const float D = 0.7071067811865476;\n"
    "    vec2 g = k == 0u ? vec2(1.0, 0.0) : k == 1u ? vec2(-1.0, 0.0) : k == 2u ? vec2(0.0, 1.0) : k == 3u ? vec2(0.0, -1.0)\n"
    "        : k == 4u ? vec2(D, D) : k == 5u ? vec2(-D, D) : k == 6u ? vec2(D, -D) : vec2(-D, -D);\n"
    "    return dot(g, f - vec2(float(dx), float(dy)));\n"
    "}\n"
    "float lo_perlin(vec2 x, uint seed) {\n"
    "    vec2 x0 = floor(x);\n"
    "    vec2 f = x - x0;\n"
    "    int ix = int(x0.x), iy = int(x0.y);\n"
    "    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);\n"
    "    float c00 = lo_corner(ix, iy, f, 0, 0, seed), c10 = lo_corner(ix, iy, f, 1, 0, seed);\n"
    "    float c01 = lo_corner(ix, iy, f, 0, 1, seed), c11 = lo_corner(ix, iy, f, 1, 1, seed);\n"
    "    float top = c00 + (c10 - c00) * u.x;\n"
    "    float bottom = c01 + (c11 - c01) * u.x;\n"
    "    return top + (bottom - top) * u.y;\n"
    "}\n"
    "vec3 lo_finish(vec3 rgb, vec2 outPx, float footprint) {\n"
    "    if (u_vig[0] == 0.0 && u_grain[0] == 0.0) return rgb;\n"
    "    const vec3 L = vec3(0.2627, 0.6780, 0.0593);\n"
    "    vec3 e = vec3(lo_fEnc(rgb.r), lo_fEnc(rgb.g), lo_fEnc(rgb.b));\n"
    "    float k = u_vig[0] / 100.0;\n"
    "    if (k != 0.0) {\n"
    "        float aspect = u_finOut.x / u_finOut.y;\n"
    "        float rho = u_vig[2] / 100.0;\n"
    "        float sx = aspect >= 1.0 ? 1.0 + (aspect - 1.0) * max(rho, 0.0) : 1.0;\n"
    "        float sy = aspect < 1.0 ? 1.0 + (1.0 / aspect - 1.0) * max(rho, 0.0) : 1.0;\n"
    "        float pw = 2.0 + 6.0 * max(-rho, 0.0);\n"
    "        vec2 q = abs(outPx / u_finOut - 0.5) * 2.0;\n"
    "        float d = pow(pow(q.x * sx, pw) + pow(q.y * sy, pw), 1.0 / pw);\n"
    "        float r0 = 0.3 + 0.9 * u_vig[1] / 100.0;\n"
    "        float t = smoothstep(r0, r0 + 0.05 + 0.95 * u_vig[3] / 100.0, d);\n"
    "        if (k < 0.0) {\n"
    "            vec3 darker = e * (1.0 + k * t);\n"
    "            float keep = u_vig[4] / 100.0 * smoothstep(0.7, 1.0, clamp(dot(e, L), 0.0, 1.0));\n"
    "            e = darker + (e - darker) * keep;\n"
    "        } else {\n"
    "            e = e + max(1.0 - e, 0.0) * (k * t);\n"
    "        }\n"
    "    }\n"
    "    if (u_grain[0] != 0.0) {\n"
    "        float cell = 1.0 + 3.0 * u_grain[1] / 100.0;\n"
    "        float rough = u_grain[2] / 100.0;\n"
    "        float n1 = lo_perlin(outPx / cell, 0x2545F491u) * min(1.0, cell / footprint);\n"
    "        float n2 = lo_perlin(2.0 * outPx / cell, 0x2545F492u) * min(1.0, 0.5 * cell / footprint);\n"
    "        float n = (n1 + rough * n2) / (1.0 + 0.5 * rough);\n"
    "        float ey = clamp(dot(e, L), 0.0, 1.0);\n"
    "        e += u_grain[0] / 100.0 * 0.12 * n * (0.25 + 3.0 * ey * (1.0 - ey));\n"
    "    }\n"
    "    return vec3(lo_fDec(e.r), lo_fDec(e.g), lo_fDec(e.b));\n"
    "}\n";

}  // namespace lo
