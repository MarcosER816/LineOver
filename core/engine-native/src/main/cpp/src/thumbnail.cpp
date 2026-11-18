#include "lineover/thumbnail.h"

#include <algorithm>

#include "lineover/color.h"
#include "lineover/graph.h"

namespace lo {

ImageF32 boxDownsample(const float* rgba, int width, int height, int maxEdge) {
    ImageF32 out;
    if (width <= 0 || height <= 0 || maxEdge <= 0) return out;
    const int longEdge = std::max(width, height);
    const int factor = std::max(1, (longEdge + maxEdge - 1) / maxEdge);
    out.width = (width + factor - 1) / factor;
    out.height = (height + factor - 1) / factor;
    out.rgba.assign(static_cast<std::size_t>(out.width) * out.height * 4, 0.f);
    for (int by = 0; by < out.height; ++by) {
        for (int bx = 0; bx < out.width; ++bx) {
            double sum[4] = {0.0, 0.0, 0.0, 0.0};
            int count = 0;
            for (int y = by * factor; y < std::min(height, (by + 1) * factor); ++y) {
                for (int x = bx * factor; x < std::min(width, (bx + 1) * factor); ++x) {
                    const float* p = rgba + 4 * (static_cast<std::size_t>(y) * width + x);
                    for (int c = 0; c < 4; ++c) sum[c] += p[c];
                    ++count;
                }
            }
            float* o = out.rgba.data() + 4 * (static_cast<std::size_t>(by) * out.width + bx);
            for (int c = 0; c < 4; ++c) o[c] = static_cast<float>(sum[c] / count);
        }
    }
    return out;
}

bool renderThumbnail(const ImageF32& source, const GuidedCoefficients* coefficients, const std::string& graphJson,
                     std::vector<std::uint8_t>* out, std::string* error) {
    Graph graph;
    if (!parseGraph(graphJson, &graph, error)) return false;
    std::string skipped;
    const auto ops = buildOps(graph, &skipped);
    std::vector<float> working = source.rgba;
    OpRegion region;
    region.width = source.width;
    region.height = source.height;
    region.coefficients = coefficients;
    ops->cpuRefRegion(working.data(), working.data(), region);
    out->resize(working.size());
    color::workingToDisplay8(working.data(), out->data(), working.size() / 4, color::Display::Srgb);
    return true;
}

}  // namespace lo
