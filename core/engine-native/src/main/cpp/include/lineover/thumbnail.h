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
// Shadows, may be null) and writes sRGB display RGBA8 to `out`. False with `error` set
// when the graph cannot be read.
bool renderThumbnail(const ImageF32& source, const GuidedCoefficients* coefficients, const std::string& graphJson,
                     std::vector<std::uint8_t>* out, std::string* error);

}  // namespace lo
