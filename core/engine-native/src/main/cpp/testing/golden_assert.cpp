#include "golden_assert.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <sstream>

#include "png_io.h"

namespace lo::testing {
namespace {

std::string diffDirectory() {
    const char* fromEnvironment = std::getenv("LO_GOLDEN_DIFF_DIR");
    return fromEnvironment != nullptr && fromEnvironment[0] != '\0' ? fromEnvironment : LO_GOLDEN_DIFF_DIR;
}

// Heatmap colour for a per-pixel error given as a multiple of the allowed
// maximum: black = identical, blue = within tolerance, red -> yellow -> white =
// 1x .. 4x the tolerance and beyond.
void heatColor(double ratio, std::uint8_t* rgb) {
    const auto channel = [](double v) { return static_cast<std::uint8_t>(std::lround(std::clamp(v, 0.0, 1.0) * 255.0)); };
    if (ratio <= 0.0) {
        rgb[0] = rgb[1] = rgb[2] = 0;
    } else if (ratio <= 1.0) {
        rgb[0] = 0;
        rgb[1] = channel(0.3 * ratio);
        rgb[2] = channel(0.35 + 0.65 * ratio);
    } else {
        const double t = std::min((ratio - 1.0) / 3.0, 1.0);
        rgb[0] = 255;
        rgb[1] = channel(t * 2.0);
        rgb[2] = channel(t * 2.0 - 1.0);
    }
}

// Shared implementation. `scale` converts a sample difference to normalised units.
template <typename T>
GoldenResult compareImpl(const Image<T>& actual, const Image<T>& expected, const std::string& name,
                         Tolerance tolerance, double scale) {
    GoldenResult result;
    std::ostringstream message;
    message.precision(6);

    if (!actual.sameShape(expected)) {
        message << name << ": shape mismatch, actual " << actual.width << "x" << actual.height << "x"
                << actual.channels << " vs expected " << expected.width << "x" << expected.height << "x"
                << expected.channels;
        result.message = message.str();
        return result;
    }

    const std::size_t pixels = static_cast<std::size_t>(actual.width) * static_cast<std::size_t>(actual.height);
    const std::size_t channels = static_cast<std::size_t>(actual.channels);
    std::vector<double> pixelError(pixels, 0.0);
    double sum = 0.0;
    double maximum = 0.0;
    for (std::size_t p = 0; p < pixels; ++p) {
        for (std::size_t c = 0; c < channels; ++c) {
            const double a = static_cast<double>(actual.data[p * channels + c]);
            const double e = static_cast<double>(expected.data[p * channels + c]);
            // A NaN on either side must fail, never pass silently.
            double diff = std::abs(a - e) * scale;
            if (std::isnan(diff)) diff = std::numeric_limits<double>::infinity();
            sum += diff;
            pixelError[p] = std::max(pixelError[p], diff);
        }
        maximum = std::max(maximum, pixelError[p]);
    }
    result.maxAbs = maximum;
    result.meanAbs = pixels * channels > 0 ? sum / static_cast<double>(pixels * channels) : 0.0;
    result.passed = result.maxAbs <= tolerance.maxAbs && result.meanAbs <= tolerance.meanAbs;

    message << name << ": max |diff| " << result.maxAbs << " (limit " << tolerance.maxAbs << "), mean |diff| "
            << result.meanAbs << " (limit " << tolerance.meanAbs << ")";

    if (!result.passed) {
        ImageU8 heatmap;
        heatmap.width = actual.width;
        heatmap.height = actual.height;
        heatmap.channels = 3;
        heatmap.data.resize(pixels * 3);
        for (std::size_t p = 0; p < pixels; ++p) {
            heatColor(pixelError[p] / tolerance.maxAbs, &heatmap.data[p * 3]);
        }
        const std::filesystem::path directory = diffDirectory();
        std::filesystem::create_directories(directory);
        result.heatmapPath = (directory / (name + "_diff.png")).string();
        writePng(result.heatmapPath, heatmap);
        message << "; heatmap: " << result.heatmapPath;
    }
    result.message = message.str();
    return result;
}

}  // namespace

GoldenResult compareGolden(const ImageU8& actual, const ImageU8& expected, const std::string& name,
                           Tolerance tolerance) {
    return compareImpl(actual, expected, name, tolerance, 1.0 / 255.0);
}

GoldenResult compareGolden(const ImageF32& actual, const ImageF32& expected, const std::string& name,
                           Tolerance tolerance) {
    return compareImpl(actual, expected, name, tolerance, 1.0);
}

std::string goldenPath(const std::string& relative) {
    return (std::filesystem::path(LO_GOLDEN_DIR) / relative).string();
}

}  // namespace lo::testing
