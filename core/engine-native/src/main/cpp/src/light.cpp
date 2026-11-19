#include "lineover/light.h"

#include "lineover/color.h"
#include "op_util.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace lo {
namespace {

using namespace light;
using opsutil::boxMean;

// Shared by every tone op: `y` and the early return for black pixels.
#define LO_GLSL_LUMA                                                   \
    "float y = dot(c.rgb, vec3(0.2627, 0.6780, 0.0593));\n"           \
    "if (y <= 1e-6) return c;\n"
#define LO_GLSL_LOG "float l = log2(y / 0.18);\n"

// A tone op: RGB scaled by ratio(Y) where Y > 1e-6.
class ToneOp : public Op {
public:
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            const float* p = in + 4 * i;
            float* q = out + 4 * i;
            const float y = luminance(p);
            const float r = y > kLumaEps ? ratio(y) : 1.f;
            q[0] = p[0] * r;
            q[1] = p[1] * r;
            q[2] = p[2] * r;
            q[3] = p[3];
        }
    }

protected:
    virtual float ratio(float y) const = 0;
};

class ExposureOp final : public Op {
public:
    explicit ExposureOp(float ev) : ev_(std::clamp(ev, -kExposureRange, kExposureRange)), gain_(std::exp2(ev_)) {}
    OpType type() const override { return OpType::Exposure; }
    bool isIdentity() const override { return ev_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader("uniform float u_gain;", "return vec4(c.rgb * u_gain, c.a);");
        context.setUniform1f("u_gain", gain_);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) {
            out[4 * i + 0] = in[4 * i + 0] * gain_;
            out[4 * i + 1] = in[4 * i + 1] * gain_;
            out[4 * i + 2] = in[4 * i + 2] * gain_;
            out[4 * i + 3] = in[4 * i + 3];
        }
    }

private:
    float ev_;
    float gain_;
};

float amountOf(float amount) { return std::clamp(amount, -kAmountRange, kAmountRange); }

// l' = l + k * l / (1 + (l / 2.5)^2), l = log2(Y / 0.18): an S-curve around mid grey.
class ContrastOp final : public ToneOp {
public:
    explicit ContrastOp(float amount) : amount_(amountOf(amount)), k_(amount_ / kAmountRange * kContrastStrength) {}
    OpType type() const override { return OpType::Contrast; }
    bool isIdentity() const override { return amount_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader("uniform float u_k;", LO_GLSL_LUMA LO_GLSL_LOG
                          "float u = l / 2.5;\n"
                          "return vec4(c.rgb * exp2(u_k * l / (1.0 + u * u)), c.a);");
        context.setUniform1f("u_k", k_);
    }

protected:
    float ratio(float y) const override {
        const float l = std::log2(y / kMidGrey);
        const float u = l / kContrastWidth;
        return std::exp2(k_ * l / (1.f + u * u));
    }

private:
    float amount_;
    float k_;
};

// Y' = Y^g on 0..1 (g = 2^(-amount/100)), continued with slope g above 1.
class BrightnessOp final : public ToneOp {
public:
    explicit BrightnessOp(float amount) : amount_(amountOf(amount)), g_(std::exp2(-amount_ / kAmountRange)) {}
    OpType type() const override { return OpType::Brightness; }
    bool isIdentity() const override { return amount_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader("uniform float u_g;", LO_GLSL_LUMA
                          "float t = y <= 1.0 ? pow(y, u_g) : 1.0 + u_g * (y - 1.0);\n"
                          "return vec4(c.rgb * (t / y), c.a);");
        context.setUniform1f("u_g", g_);
    }

protected:
    float ratio(float y) const override {
        const float t = y <= 1.f ? std::pow(y, g_) : 1.f + g_ * (y - 1.f);
        return t / y;
    }

private:
    float amount_;
    float g_;
};

// l' = l + k * clamp(l / l_p, 0, 1)^2 with l_p the endpoint in stops from mid grey.
class EndpointOp final : public ToneOp {
public:
    EndpointOp(OpType type, float amount, float point)
        : type_(type), amount_(amountOf(amount)), k_(amount_ / kAmountRange), inv_(1.f / std::log2(point / kMidGrey)) {}
    OpType type() const override { return type_; }
    bool isIdentity() const override { return amount_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader("uniform float u_k;\nuniform float u_inv;", LO_GLSL_LUMA LO_GLSL_LOG
                          "float t = clamp(l * u_inv, 0.0, 1.0);\n"
                          "return vec4(c.rgb * exp2(u_k * t * t), c.a);");
        context.setUniform1f("u_k", k_);
        context.setUniform1f("u_inv", inv_);
    }

protected:
    float ratio(float y) const override {
        const float t = std::clamp(std::log2(y / kMidGrey) * inv_, 0.f, 1.f);
        return std::exp2(k_ * t * t);
    }

private:
    OpType type_;
    float amount_;
    float k_;
    float inv_;
};

float smoothstep(float e0, float e1, float x) {
    const float t = std::clamp((x - e0) / (e1 - e0), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

// Y' = Y * 2^delta(base), base = A * L + B the guided-filter base of L = log2 Y.
class HighlightsShadowsOp final : public Op {
public:
    HighlightsShadowsOp(float highlights, float shadows)
        : highlights_(amountOf(highlights)),
          shadows_(amountOf(shadows)),
          h_(highlights_ / kAmountRange * kToneStops),
          s_(shadows_ / kAmountRange * kToneStops) {}
    OpType type() const override { return OpType::HighlightsShadows; }
    bool isIdentity() const override { return highlights_ == 0.f && shadows_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader("uniform float u_h;\nuniform float u_s;", LO_GLSL_LUMA
                          "vec2 ab = lo_guided();\n"
                          "float rel = ab.x * log2(y) + ab.y + 2.4739311883324122;\n"
                          "float mh = smoothstep(0.0, 2.5, rel);\n"
                          "float ms = 1.0 - smoothstep(-5.0, 0.0, rel);\n"
                          "return vec4(c.rgb * exp2(u_h * mh + u_s * ms), c.a);");
        context.setUniform1f("u_h", h_);
        context.setUniform1f("u_s", s_);
    }
    // Without image-wide coefficients the base is L itself (A = 1, B = 0).
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        for (std::size_t i = 0; i < pixels; ++i) apply(in + 4 * i, out + 4 * i, 1.f, 0.f);
    }
    void cpuRefRegion(const float* in, float* out, const OpRegion& region) const override {
        const GuidedCoefficients* c = region.coefficients;
        if (c == nullptr || c->ab.empty()) {
            cpuRef(in, out, region.pixels());
            return;
        }
        for (int j = 0; j < region.height; ++j) {
            const float v = region.rect[1] + (static_cast<float>(j) + 0.5f) / region.height * region.rect[3];
            for (int i = 0; i < region.width; ++i) {
                const float u = region.rect[0] + (static_cast<float>(i) + 0.5f) / region.width * region.rect[2];
                float a, b;
                sampleGuided(*c, u, v, &a, &b);
                const std::size_t k = 4 * (static_cast<std::size_t>(j) * region.width + i);
                apply(in + k, out + k, a, b);
            }
        }
    }

private:
    void apply(const float* p, float* q, float a, float b) const {
        const float y = luminance(p);
        float r = 1.f;
        if (y > kLumaEps) {
            const float rel = a * std::log2(y) + b - std::log2(kMidGrey);
            const float mh = smoothstep(0.f, kHighlightRange, rel);
            const float ms = 1.f - smoothstep(-kShadowRange, 0.f, rel);
            r = std::exp2(h_ * mh + s_ * ms);
        }
        q[0] = p[0] * r;
        q[1] = p[1] * r;
        q[2] = p[2] * r;
        q[3] = p[3];
    }

    float highlights_, shadows_;
    float h_, s_;
};

}  // namespace

std::unique_ptr<Op> makeExposure(float ev) { return std::make_unique<ExposureOp>(ev); }
std::unique_ptr<Op> makeContrast(float amount) { return std::make_unique<ContrastOp>(amount); }
std::unique_ptr<Op> makeBrightness(float amount) { return std::make_unique<BrightnessOp>(amount); }
std::unique_ptr<Op> makeWhites(float amount) {
    return std::make_unique<EndpointOp>(OpType::Whites, amount, kWhitePoint);
}
std::unique_ptr<Op> makeBlacks(float amount) {
    return std::make_unique<EndpointOp>(OpType::Blacks, amount, kBlackPoint);
}
std::unique_ptr<Op> makeHighlightsShadows(float highlights, float shadows) {
    return std::make_unique<HighlightsShadowsOp>(highlights, shadows);
}

int guidedRadius(int width, int height) {
    const double r = std::floor(static_cast<double>(kGuidedRadiusFraction) * std::max(width, height) + 0.5);
    return std::max(1, static_cast<int>(r));
}

GuidedCoefficients guidedCoefficients(const float* rgba, int width, int height) {
    GuidedCoefficients out;
    if (width <= 0 || height <= 0) return out;
    const std::size_t n = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    const int r = guidedRadius(width, height);
    // Python reference: log2(max(Y, eps)) of float32 inputs evaluated in float64.
    std::vector<double> mean(n), mean2(n);
    for (std::size_t i = 0; i < n; ++i) {
        const float* p = rgba + 4 * i;
        const double y = 0.2627 * p[0] + 0.6780 * p[1] + 0.0593 * p[2];
        const double l = std::log2(std::max(y, 1e-6));
        mean[i] = l;
        mean2[i] = l * l;
    }
    boxMean(mean, width, height, r);
    boxMean(mean2, width, height, r);
    // a and b reuse the two buffers.
    for (std::size_t i = 0; i < n; ++i) {
        const double var = mean2[i] - mean[i] * mean[i];
        const double a = var / (var + kGuidedEps);
        mean2[i] = mean[i] - a * mean[i];  // b
        mean[i] = a;
    }
    boxMean(mean, width, height, r);
    boxMean(mean2, width, height, r);
    out.width = width;
    out.height = height;
    out.ab.resize(2 * n);
    for (std::size_t i = 0; i < n; ++i) {
        out.ab[2 * i] = static_cast<float>(mean[i]);
        out.ab[2 * i + 1] = static_cast<float>(mean2[i]);
    }
    return out;
}

void luminancePercentiles(const float* rgba, std::size_t pixels, float low, float high, float* lowValue,
                          float* highValue, std::size_t stride) {
    stride = std::max<std::size_t>(stride, 1);
    std::vector<float> y;
    y.reserve(pixels / stride + 1);
    for (std::size_t i = 0; i < pixels; i += stride) y.push_back(luminance(rgba + 4 * i));
    if (y.empty()) {
        *lowValue = *highValue = 0.f;
        return;
    }
    auto at = [&](float q) {
        const auto k = static_cast<std::size_t>(std::floor(std::clamp(q, 0.f, 1.f) * (y.size() - 1) + 0.5f));
        std::nth_element(y.begin(), y.begin() + static_cast<std::ptrdiff_t>(k), y.end());
        return y[k];
    };
    *lowValue = at(low);
    *highValue = at(high);
}

void histogramSrgb(const float* rgba, std::size_t pixels, std::uint32_t* out) {
    std::fill(out, out + 1024, 0u);
    auto bin = [](float v) {
        const float encoded = color::srgbOetf(std::clamp(v, 0.f, 1.f));
        return std::min(static_cast<int>(std::floor(encoded * 256.f)), 255);
    };
    float rgb[4];
    for (std::size_t i = 0; i < pixels; ++i) {
        const float* p = rgba + 4 * i;
        color::applyMatrix(color::kRec2020ToSrgb, p, rgb, 1);
        ++out[bin(rgb[0])];
        ++out[256 + bin(rgb[1])];
        ++out[512 + bin(rgb[2])];
        ++out[768 + bin(luminance(p))];
    }
}

AutoLight autoLight(float lowValue, float highValue) {
    AutoLight result;
    if (highValue > kLumaEps) result.exposure = std::clamp(std::log2(1.f / highValue), -kExposureRange, kExposureRange);
    const float low = lowValue * std::exp2(result.exposure);
    if (low > kLumaEps) {
        const float l = std::log2(low / kMidGrey);
        const float lb = std::log2(kBlackPoint / kMidGrey);
        const float t = std::clamp(l / lb, 0.f, 1.f);
        if (t * t > 1e-6f) result.blacks = std::clamp(kAmountRange * (lb - l) / (t * t), -kAmountRange, kAmountRange);
    }
    return result;
}

}  // namespace lo
