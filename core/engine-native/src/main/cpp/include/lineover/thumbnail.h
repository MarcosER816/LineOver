// Small renders of an edit graph for thumbnails (History → Versions, the Home grid, the
// .loproj thumbnails/): the CPU reference ops on a box-filtered copy of the preview, then
// the sRGB display transform (gamut-mapped). Portable, no GL: runs on any thread.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "lineover/ops.h"

namespace lo {

struct ImageF32 {
    int width = 0;
    int height = 0;
    std::vector<float> rgba;  // working space, straight alpha
};

// Box-filters `rgba` (width x height) by the smallest integer factor that brings the long
// edge to at most `maxEdge`; edge blocks average the pixels they have.
ImageF32 boxDownsample(const float* rgba, int width, int height, int maxEdge);

// Runs `graphJson` on `source` (the preview's guided-filter `coefficients` for Highlights /
// Shadows and its Stage 10 `fields`, either may be null), then its geometry (crop, rotation,
// lens) at the source's scale (long edge <= 512) and the post-crop finishing (vignette,
// grain), and writes sRGB display RGBA8 to `out` with its size. `fullWidth` is the
// full-resolution image width (radii scale by source.width / fullWidth; 0: the geometry's
// source width, else the source's own). False with `error` set when the graph cannot be read.
bool renderThumbnail(const ImageF32& source, const GuidedCoefficients* coefficients, const std::string& graphJson,
                     std::vector<std::uint8_t>* out, std::string* error, int* width, int* height,
                     const DetailFields* fields = nullptr, int fullWidth = 0);

struct Graph;

// The edit as a float image in output space (working colours): the background's ops on
// [source], its geometry, the layers above it (Stage 13, preview layer images) and the
// finishing over them all, at most [maxEdge] pixels on the long edge when there is a
// geometry (else the source's size). The canvas shows this for layered documents.
bool renderGraphImage(const ImageF32& source, const GuidedCoefficients* coefficients, const Graph& graph, ImageF32* out,
                      const DetailFields* fields, int fullWidth, int maxEdge);

}  // namespace lo
