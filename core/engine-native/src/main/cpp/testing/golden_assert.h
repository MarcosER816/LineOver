// GoldenAssert: compares an image with a reference within a tolerance and, on
// failure, writes a diff heatmap PNG. Mirrors the Kotlin GoldenAssert.
#pragma once

#include <limits>
#include <string>

#include "image.h"

namespace lo::testing {

// Limits on the absolute per-sample difference, in normalised units (1.0 == 255/255).
struct Tolerance {
    double maxAbs;
    double meanAbs;
};

// 8-bit results: max |diff| <= 2/255 and mean |diff| <= 0.5/255.
inline constexpr Tolerance kToleranceU8{2.0 / 255.0, 0.5 / 255.0};
// Float operations: max |diff| <= 1e-3.
inline constexpr Tolerance kToleranceFloat{1e-3, std::numeric_limits<double>::infinity()};
// GPU result against the CPU reference: max |diff| <= 3/255.
inline constexpr Tolerance kToleranceGpuVsCpu{3.0 / 255.0, std::numeric_limits<double>::infinity()};

struct GoldenResult {
    bool passed = false;
    double maxAbs = 0.0;   // normalised
    double meanAbs = 0.0;  // normalised
    std::string message;   // human-readable summary; names the heatmap on failure
    std::string heatmapPath;

    explicit operator bool() const { return passed; }
};

// `name` identifies the comparison and becomes the heatmap file name
// (<LO_GOLDEN_DIFF_DIR>/<name>_diff.png). The environment variable
// LO_GOLDEN_DIFF_DIR overrides the compiled-in directory.
GoldenResult compareGolden(const ImageU8& actual, const ImageU8& expected, const std::string& name,
                           Tolerance tolerance = kToleranceU8);
GoldenResult compareGolden(const ImageF32& actual, const ImageF32& expected, const std::string& name,
                           Tolerance tolerance = kToleranceFloat);

// Absolute path of a file below testdata/golden.
std::string goldenPath(const std::string& relative);

}  // namespace lo::testing
