// The op interface every tool plugs into.
//
// An op transforms working-space RGBA (linear Rec.2020, straight alpha). Each op has
// two implementations that must agree:
//  * gpu():    the GLSL body of the op plus its uniforms, run by the GL backend on
//              RGBA16F tiles (the interactive path);
//  * cpuRef(): plain C++, float, scalar, NEON-free. It is the golden reference the GPU
//              path is tested against (GPU vs CPU within 3/255) and the Python
//              reference in tools/golden is tested against it.
#pragma once

#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace lo {

// Stable numeric ids of op types (also used across JNI). Never renumber.
enum class OpType : int {
    Identity = 0,
    ExposureTest = 1,
};

// What gpu() talks to: implemented by the GL backend, and by a recorder in host tests.
class GpuOpContext {
public:
    virtual ~GpuOpContext() = default;
    // GLSL (ES 3.00) function body of the op: `vec4 lo_op(vec4 c) { ... }` where `c` is
    // working-space RGBA. Uniforms are declared by the op inside `declarations`.
    virtual void setShader(const char* declarations, const char* body) = 0;
    virtual void setUniform1f(const char* name, float value) = 0;
};

class Op {
public:
    virtual ~Op() = default;
    virtual OpType type() const = 0;
    // True when the op leaves every pixel unchanged, so the pipeline can skip it.
    virtual bool isIdentity() const = 0;
    // Describes the op to the GPU backend (shader + uniforms).
    virtual void gpu(GpuOpContext& context) const = 0;
    // Reference implementation on `pixels` RGBA float pixels; in == out is allowed.
    virtual void cpuRef(const float* in, float* out, std::size_t pixels) const = 0;
};

// Leaves the image unchanged. Used to validate the pipeline end to end.
std::unique_ptr<Op> makeIdentity();

// Multiplies RGB by 2^stops (alpha unchanged). A test op proving params reach both
// paths; the real Exposure tool arrives in Stage 5.
std::unique_ptr<Op> makeExposureTest(float stops);

// An ordered list of ops, applied first to last.
class OpList {
public:
    void add(std::unique_ptr<Op> op) { ops_.push_back(std::move(op)); }
    const std::vector<std::unique_ptr<Op>>& ops() const { return ops_; }
    bool isIdentity() const;
    // Applies every op's cpuRef in order (in == out is allowed).
    void cpuRef(const float* in, float* out, std::size_t pixels) const;

private:
    std::vector<std::unique_ptr<Op>> ops_;
};

}  // namespace lo
