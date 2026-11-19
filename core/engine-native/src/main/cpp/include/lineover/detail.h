// Detail and Effects (Stage 10): Sharpen, Noise reduction, Clarity, Texture, Dehaze (source
// space ops) and the post-crop Vignette and Grain (output-space finishing, applied where
// pixels are sampled for display and export). Formulas: tools/golden/detail_reference.py and
// dehaze_reference.py (He, Sun and Tang 2009, guided-filter refinement).
//
// Radii are given in full-resolution pixels and multiplied by OpRegion::scale, so the preview
// shows what the export will look like. Clarity, Texture and Dehaze read DetailFields (the
// fast guided filter of the unedited preview) and the image as it entered the first of them.
#pragma once

#include <cstdint>
#include <memory>

#include "lineover/graph.h"
#include "lineover/ops.h"

namespace lo {

// The fields of an unedited preview (working-space RGBA, width x height).
DetailFields computeDetailFields(const float* rgba, int width, int height);

std::unique_ptr<Op> makeSharpen(float amount, float radius, float detail, float masking, bool showMask);
std::unique_ptr<Op> makeNoiseReduction(float luminance, float detail, float contrast, float color);
std::unique_ptr<Op> makeClarity(float amount);
std::unique_ptr<Op> makeTexture(float amount);
std::unique_ptr<Op> makeDehaze(float amount);

// Output-space finishing: post-crop vignette, then grain.
struct Finishing {
    float vignette = 0.f, midpoint = 50.f, roundness = 0.f, feather = 50.f, highlights = 0.f;
    float grain = 0.f, grainSize = 25.f, roughness = 50.f;

    bool active() const { return vignette != 0.f || grain != 0.f; }
};

// The finishing ops of the bottom layer ("post_crop_vignette", "grain"); false when none.
bool finishingOf(const Graph& graph, Finishing* out);

// Applies `f` to a buffer of output pixels: buffer pixel (i, j) is at full-resolution
// output position origin + ((i, j) + 0.5) / scale; the output is outWidth x outHeight
// full-resolution pixels. Working-space RGBA in place.
void applyFinishing(const Finishing& f, float* rgba, int width, int height, double outWidth, double outHeight, double originX,
                    double originY, double scale);

// GLSL of the finishing: `vec3 lo_finish(vec3 rgb, vec2 outPx, float footprint)` with
// uniforms u_vig[5] (amount, midpoint, roundness, feather, highlights), u_grain[3]
// (amount, size, roughness) and u_finOut (output size, full-resolution pixels). outPx is the
// full-resolution output position, footprint the output pixels one fragment covers.
extern const char* const kFinishingGlsl;

}  // namespace lo
