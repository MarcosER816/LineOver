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

void OpList::cpuRef(const float* in, float* out, std::size_t pixels) const {
    if (in != out) std::memcpy(out, in, pixels * 4 * sizeof(float));
    for (const auto& op : ops_) op->cpuRef(out, out, pixels);
}

}  // namespace lo
