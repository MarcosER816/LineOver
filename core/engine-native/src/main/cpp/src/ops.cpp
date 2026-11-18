#include "lineover/ops.h"

#include <cmath>
#include <cstring>

namespace lo {
namespace {

class IdentityOp final : public Op {
public:
    OpType type() const override { return OpType::Identity; }
    bool isIdentity() const override { return true; }
    void gpu(GpuOpContext& context) const override { context.setShader("", "return c;"); }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        if (in != out) std::memcpy(out, in, pixels * 4 * sizeof(float));
    }
};

class ExposureTestOp final : public Op {
public:
    explicit ExposureTestOp(float stops) : stops_(stops), gain_(std::exp2(stops)) {}
    OpType type() const override { return OpType::ExposureTest; }
    bool isIdentity() const override { return stops_ == 0.0f; }
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
    float stops_;
    float gain_;
};

}  // namespace

std::unique_ptr<Op> makeIdentity() { return std::make_unique<IdentityOp>(); }

std::unique_ptr<Op> makeExposureTest(float stops) { return std::make_unique<ExposureTestOp>(stops); }

bool OpList::isIdentity() const {
    for (const auto& op : ops_) {
        if (!op->isIdentity()) return false;
    }
    return true;
}

void OpList::cpuRefRegion(const float* in, float* out, const OpRegion& region) const {
    if (in != out) std::memcpy(out, in, region.pixels() * 4 * sizeof(float));
    for (const auto& op : ops_) op->cpuRefRegion(out, out, region);
}

void sampleGuided(const GuidedCoefficients& c, float u, float v, float* a, float* b) {
    // Same arithmetic as lo_guided() in gpu_pipeline.cpp.
    const float px = u * static_cast<float>(c.width) - 0.5f;
    const float py = v * static_cast<float>(c.height) - 0.5f;
    const float fx = std::floor(px), fy = std::floor(py);
    const float tx = px - fx, ty = py - fy;
    const int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy);
    auto at = [&](int x, int y, int k) {
        x = x < 0 ? 0 : (x >= c.width ? c.width - 1 : x);
        y = y < 0 ? 0 : (y >= c.height ? c.height - 1 : y);
        return c.ab[2 * (static_cast<std::size_t>(y) * c.width + x) + k];
    };
    auto mix = [](float p, float q, float t) { return p * (1.f - t) + q * t; };
    for (int k = 0; k < 2; ++k) {
        const float top = mix(at(x0, y0, k), at(x0 + 1, y0, k), tx);
        const float bottom = mix(at(x0, y0 + 1, k), at(x0 + 1, y0 + 1, k), tx);
        (k == 0 ? *a : *b) = mix(top, bottom, ty);
    }
}

void OpList::cpuRef(const float* in, float* out, std::size_t pixels) const {
    if (in != out) std::memcpy(out, in, pixels * 4 * sizeof(float));
    for (const auto& op : ops_) op->cpuRef(out, out, pixels);
}

}  // namespace lo
