// GPU side of the ops, the display transform and the histogram. Render thread only.
#pragma once

#include <GLES3/gl31.h>

#include <cstdint>
#include <list>
#include <map>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

#include "lineover/color.h"
#include "lineover/detail.h"
#include "lineover/ops.h"

namespace lo::android {

// Image-wide inputs of an op pass (see OpRegion).
struct OpPassInputs {
    float rect[4] = {0.f, 0.f, 1.f, 1.f};  // the target's rect in normalised image coordinates
    GLuint guided = 0;                     // RG32F guided-filter coefficients (createGuidedTexture)
    int guidedWidth = 1;
    int guidedHeight = 1;
    // Stage 10: target pixels per full-resolution pixel, and the preview's fields
    // (createFieldTextures) with the haze model's airlight.
    float scale = 1.f;
    GLuint fields = 0;  // RGBA32F clarity a, b, texture a, b
    int fieldsWidth = 1, fieldsHeight = 1;
    GLuint haze = 0;    // RG32F a, b
    int hazeWidth = 1, hazeHeight = 1;
    float air[3] = {1.f, 1.f, 1.f};
};

// The geometry of one draw (Stage 9, kGeometrySource): matrices row major.
struct GeometryDraw {
    float matrix[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // output normalised -> source pixels
    float outSize[2] = {1, 1};                        // output pixels
    float srcSize[2] = {1, 1};                        // source pixels
    float lens[4] = {0, 0, 0, 0};                     // k1, k2, caRed, caBlue
    float dist[4] = {0, 0, 0, 0};                     // lens profile distortion p1..p4
    float tcaRed[3] = {0, 0, 0};                      // lens profile TCA (tv, tc, tb)
    float tcaBlue[3] = {0, 0, 0};
    float srcRect[4] = {0, 0, 1, 1};                  // the texture's rect, normalised source coordinates
    float ownRect[4] = {0, 0, 1, 1};                  // a tile's own rect (the texture may carry an apron)
    float drawRect[4] = {0, 0, 1, 1};                 // the drawn quad, output pixels
    bool clampTile = false;                           // a tile: clamp taps, discard outside its rect
};

struct DisplayOptions {
    bool clipping = false;  // paint clipped highlights clipHi and crushed shadows clipLo
    // The texture's part drawn in the quad (plain path; a tile with an apron draws its middle).
    float texRect[4] = {0.f, 0.f, 1.f, 1.f};
    // Stage 10 output-space finishing (vignette, grain) and the output's size in
    // full-resolution pixels (the geometry's output size, else the image's).
    Finishing finishing;
    float outSize[2] = {1.f, 1.f};
};

class GpuPipeline {
public:
    static constexpr int kHistogramBins = 256;
    static constexpr int kHistogramChannels = 4;  // R, G, B, L
    static constexpr int kHistogramSize = kHistogramBins * kHistogramChannels;

    bool init(std::string* error);
    void release();

    bool halfFloat() const { return halfFloat_; }
    // GLES 3.1 compute shaders are available (the histogram needs them).
    bool computeSupported() const { return histogramProgram_ != 0; }
    const std::string& computeError() const { return histogramError_; }

    // Runs `ops` on `source` (width x height) into `target` (same size), one pass per
    // non-identity op (ping-pong through a scratch texture). With no work to do it copies.
    bool runOps(const OpList& ops, GLuint source, GLuint target, int width, int height, const OpPassInputs& inputs,
                std::string* error);

    // Draws `texture` (working space) as the quad image-pixel rect `rect` (x, y, w, h)
    // through `imageToNdc` (row-major 3x3 mapping original-image pixels to NDC), with the
    // display transform: Rec.2020 -> display primaries, clip, and the sRGB transfer when
    // `encode` (false when the EGL surface encodes itself). Output is premultiplied.
    // With `geometry`, `rect` is in OUTPUT pixels and each fragment samples the texture
    // through the geometry (one bicubic resample).
    void drawImage(GLuint texture, const float rect[4], const float imageToNdc[9], color::Display display, bool encode,
                   const DisplayOptions& options = {}, const GeometryDraw* geometry = nullptr);
    // Renders the geometry of `texture` into `target` (width x height, the whole output at
    // that size), working-space values: the output's histogram and the GPU self test.
    bool warp(GLuint texture, const GeometryDraw& geometry, GLuint target, int width, int height, std::string* error,
              const Finishing& finishing = {});

    // Starts counting the 256-bin R, G, B, L histogram of `texture` (working space;
    // sRGB display values) into the histogram buffer. False without compute support.
    bool dispatchHistogram(GLuint texture, int width, int height);
    // Copies the last dispatched histogram (channel-major: bins[c * 256 + i]). Waits for
    // the GPU: call once its fence has signalled.
    bool readHistogram(std::uint32_t out[kHistogramSize]);

    // GLES 3.0 path (no compute): asynchronous float readback of `texture` into a pixel
    // pack buffer; finishReadback() copies it out once its fence has signalled. Needs a
    // float colour buffer (halfFloat()).
    bool startReadback(GLuint texture, int width, int height);
    bool finishReadback(std::vector<float>* out);
    void releaseReadback();
    // Synchronous float readback (test hook).
    bool readFloat(GLuint texture, int width, int height, float* out);
    // Float readback of a region (x, y in texture rows from the bottom-left = row 0 of
    // the uploaded data).
    bool readRegion(GLuint texture, int x, int y, int width, int height, float* out);

private:
    // A single-pass op (`vec4 lo_op(vec4 c)`) or, with `pass`, one pass of a multi-pass op
    // (`vec4 lo_pass()`).
    GLuint programFor(const std::string& declarations, const std::string& body, bool pass, std::string* error);
    // Scratch texture `slot` of the size (runOps keeps up to five alive at once).
    GLuint scratch(int width, int height, int slot);
    // An RGBA32F texture holding an op's LUT (cached by content, most recent first).
    GLuint lutTexture(const float* rgba, int width, int height);
    static constexpr std::size_t kMaxLuts = 8;
    std::list<std::pair<std::uint64_t, GLuint>> luts_;

    bool halfFloat_ = true;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint fbo_ = 0;
    GLuint displayProgram_ = 0;
    GLuint displayGeomProgram_ = 0;
    GLuint warpProgram_ = 0;
    std::string histogramError_;
    GLuint histogramProgram_ = 0;
    GLuint histogramBuffer_ = 0;
    GLint histogramToSrgb_ = -1, histogramSize_ = -1;
    GLuint packBuffer_ = 0;
    std::size_t packBytes_ = 0;
    int packWidth_ = 0, packHeight_ = 0;
    std::map<std::string, GLuint> opPrograms_;
    std::map<std::tuple<int, int, int>, GLuint> scratch_;
};

}  // namespace lo::android
