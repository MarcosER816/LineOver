// GPU side of the ops, the display transform and the histogram. Render thread only.
#pragma once

#include <GLES3/gl31.h>

#include <cstdint>
#include <map>
#include <string>
#include <utility>

#include "lineover/color.h"
#include "lineover/ops.h"

namespace lo::android {

// Image-wide inputs of an op pass (see OpRegion).
struct OpPassInputs {
    float rect[4] = {0.f, 0.f, 1.f, 1.f};  // the target's rect in normalised image coordinates
    GLuint guided = 0;                     // RG32F guided-filter coefficients (createGuidedTexture)
    int guidedWidth = 1;
    int guidedHeight = 1;
};

struct DisplayOptions {
    bool clipping = false;  // paint clipped highlights clipHi and crushed shadows clipLo
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
    void drawImage(GLuint texture, const float rect[4], const float imageToNdc[9], color::Display display, bool encode,
                   const DisplayOptions& options = {});

    // Starts counting the 256-bin R, G, B, L histogram of `texture` (working space;
    // sRGB display values) into the histogram buffer. False without compute support.
    bool dispatchHistogram(GLuint texture, int width, int height);
    // Copies the last dispatched histogram (channel-major: bins[c * 256 + i]). Waits for
    // the GPU: call once its fence has signalled.
    bool readHistogram(std::uint32_t out[kHistogramSize]);

private:
    GLuint programFor(const std::string& declarations, const std::string& body, std::string* error);
    GLuint scratch(int width, int height);

    bool halfFloat_ = true;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint fbo_ = 0;
    GLuint displayProgram_ = 0;
    GLint displayTex_ = -1, displayMatrix_ = -1, displayToDisplay_ = -1, displayEncode_ = -1, displayClip_ = -1, displayClipHi_ = -1, displayClipLo_ = -1;
    std::string histogramError_;
    GLuint histogramProgram_ = 0;
    GLuint histogramBuffer_ = 0;
    GLint histogramToSrgb_ = -1, histogramSize_ = -1;
    std::map<std::string, GLuint> opPrograms_;
    std::map<std::pair<int, int>, GLuint> scratch_;
};

}  // namespace lo::android
