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
    Exposure = 2,
    Contrast = 3,
    Brightness = 4,
    Whites = 5,
    Blacks = 6,
    HighlightsShadows = 7,
    WhiteBalance = 8,
    Vibrance = 9,
    Saturation = 10,
    HslMixer = 11,
    Invert = 12,
    Posterize = 13,
    Threshold = 14,
    Curves = 15,
    ParametricCurve = 16,
    Levels = 17,
    ColorGrading = 18,
    ColorBalance = 19,
    SelectiveColor = 20,
    ChannelMixer = 21,
    BlackAndWhite = 22,
    PhotoFilter = 23,
    GradientMap = 24,
    Calibration = 25,
    Vignetting = 26,
    // Stage 10
    NoiseReduction = 27,
    Clarity = 28,
    Texture = 29,
    Dehaze = 30,
    Sharpen = 31,
};

// Guided-filter coefficients of an image's log2 luminance, at preview resolution:
// base = A * L + B (see light.h). `ab` holds A, B interleaved, row-major.
struct GuidedCoefficients {
    int width = 0;
    int height = 0;
    std::vector<float> ab;
};

// Image-wide fields of the unedited preview for Clarity, Texture and Dehaze (Stage 10,
// detail.h): fast guided-filter coefficients (base = a * guide + b), each at preview size,
// and the haze model's atmospheric light (encoded RGB).
struct DetailFields {
    GuidedCoefficients clarity;  // guide: log2 luminance
    GuidedCoefficients texture;  // guide: log2 luminance
    GuidedCoefficients haze;     // guide: encoded grey; a * grey + b = refined dark channel of I / A
    float airlight[3] = {1.f, 1.f, 1.f};
};

// Where a buffer of pixels lies in the image, for ops that read image-wide data.
struct OpRegion {
    int width = 0;   // pixels of the buffer
    int height = 0;
    // The buffer's rect in normalised image coordinates (x, y, w, h); the preview is
    // (0, 0, 1, 1). Pixel (i, j) has image uv = rect.xy + ((i, j) + 0.5) / size * rect.zw.
    float rect[4] = {0.f, 0.f, 1.f, 1.f};
    const GuidedCoefficients* coefficients = nullptr;  // null: none yet (base = L)
    // Stage 10: buffer pixels per full-resolution pixel (radii are given in full-resolution
    // pixels: 1 at export, < 1 on the preview and zoomed-out tiles).
    float scale = 1.f;
    const DetailFields* fields = nullptr;  // null: Clarity / Texture / Dehaze do nothing
    // The buffer as it entered the first op that reads the original (OpList fills it).
    const float* original = nullptr;

    std::size_t pixels() const { return static_cast<std::size_t>(width) * static_cast<std::size_t>(height); }
};

// What gpu() talks to: implemented by the GL backend, and by a recorder in host tests.
class GpuOpContext {
public:
    virtual ~GpuOpContext() = default;
    // GLSL (ES 3.00) function body of the op: `vec4 lo_op(vec4 c) { ... }` where `c` is
    // working-space RGBA. Uniforms are declared by the op inside `declarations`.
    // The backend also provides `vec2 lo_guided()`: the bilinearly sampled guided-filter
    // coefficients (A, B) at the current pixel, sampled exactly as sampleGuided() does.
    virtual void setShader(const char* declarations, const char* body) = 0;
    virtual void setUniform1f(const char* name, float value) = 0;
    // A mat3 uniform, row-major.
    virtual void setUniformMat3(const char* name, const float* rowMajor9) {
        (void)name;
        (void)rowMajor9;
    }
    // A float[count] uniform.
    virtual void setUniform1fv(const char* name, const float* values, int count) {
        (void)name;
        (void)values;
        (void)count;
    }
    // A multi-pass op (Stage 10): pass k is a full-screen draw into a buffer the size of the
    // target; `body` is the body of `vec4 lo_pass()`, which reads u_source (the op's input),
    // u_prev (pass k - 1's output) and u_original with lo_at(sampler, ivec2) (clamped) or
    // lo_bilinear(sampler, vec2 pixel position), at lo_px(). The last pass is the op's output.
    // Uniforms set by the op apply to every pass.
    virtual void addPass(const char* declarations, const char* body) {
        (void)declarations;
        (void)body;
    }
    // A lookup table the shader reads with lo_lut1(row, e) / lo_lut3(row, t): `width` x
    // `height` RGBA float texels, row-major (the backend caches it by content).
    virtual void setLut(const float* rgba, int width, int height) {
        (void)rgba;
        (void)width;
        (void)height;
    }
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
    // Ops that read image-wide data run here as if no such data existed.
    virtual void cpuRef(const float* in, float* out, std::size_t pixels) const = 0;
    // Reference implementation on a region of the image (tiles, the preview). Point ops
    // only forward to cpuRef(); ops that read image-wide data override it.
    virtual void cpuRefRegion(const float* in, float* out, const OpRegion& region) const {
        cpuRef(in, out, region.pixels());
    }
    // Stage 10. How far (full-resolution pixels) the op reads around a pixel: tiles carry an
    // apron this wide (summed over the list) so they match the whole image.
    virtual int apron() const { return 0; }
    // True when the op reads the image as it entered the first such op (OpRegion::original,
    // GLSL lo_original()): Clarity, Texture and Dehaze take their base from it.
    virtual bool readsOriginal() const { return false; }
};

// Bilinear sample of `c` at image uv (pixel centres at (i + 0.5) / size, edges clamped).
// Also implemented in GLSL by the backend's lo_guided().
void sampleGuided(const GuidedCoefficients& c, float u, float v, float* a, float* b);

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
    // Applies every op's cpuRefRegion in order (in == out is allowed). Before the first op
    // that readsOriginal() the buffer is copied and handed to the rest as region.original.
    void cpuRefRegion(const float* in, float* out, const OpRegion& region) const;
    // The sum of the ops' aprons (full-resolution pixels).
    int apron() const;

private:
    std::vector<std::unique_ptr<Op>> ops_;
};

}  // namespace lo
