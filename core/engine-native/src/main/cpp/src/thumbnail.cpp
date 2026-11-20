#include "lineover/thumbnail.h"

#include <algorithm>
#include <cmath>

#include "lineover/color.h"
#include "lineover/detail.h"
#include "lineover/geometry.h"
#include "lineover/graph.h"
#include "lineover/layers.h"

namespace lo {
namespace {
constexpr int kMaxThumbnailEdge = 512;
}  // namespace

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

bool renderGraphImage(const ImageF32& source, const GuidedCoefficients* coefficients, const Graph& graph, ImageF32* out,
                      const DetailFields* fields, int fullWidth, int maxEdge, const std::string* maskLayer, int maskView) {
    std::string skipped;
    const auto ops = buildOps(graph, &skipped);
    std::vector<float> working = source.rgba;
    Geometry g;
    const bool hasGeometry = geometryOf(graph, &g);
    if (fullWidth <= 0) fullWidth = hasGeometry && g.sourceWidth > 0 ? g.sourceWidth : source.width;
    const double sourceScale = static_cast<double>(source.width) / fullWidth;
    OpRegion region;
    region.width = source.width;
    region.height = source.height;
    region.coefficients = coefficients;
    region.fields = fields;
    region.scale = static_cast<float>(sourceScale);
    ops->cpuRefRegion(working.data(), working.data(), region);
    int width = source.width, height = source.height;
    // The output in full-resolution pixels and the buffer's pixels per output pixel.
    double outW = fullWidth, outH = static_cast<double>(source.height) / sourceScale, outScale = sourceScale;
    if (hasGeometry) {
        // One resample from the (already edited) source copy to the output, at its scale.
        const int fullW = g.sourceWidth > 0 ? g.sourceWidth : source.width;
        const int fullH = g.sourceHeight > 0 ? g.sourceHeight : source.height;
        const double scale = static_cast<double>(source.width) / fullW;
        const double outputScale = std::min(scale, maxEdge / static_cast<double>(std::max(g.outputWidth, g.outputHeight)));
        width = std::max(1, static_cast<int>(std::lround(g.outputWidth * outputScale)));
        height = std::max(1, static_cast<int>(std::lround(g.outputHeight * outputScale)));
        WarpSource s;
        s.rgba = working.data();
        s.width = source.width;
        s.height = source.height;
        s.scale = scale;
        std::vector<float> warped(static_cast<std::size_t>(width) * height * 4);
        warp(g, fullW, fullH, s, static_cast<double>(width) / g.outputWidth, 0, 0, width, height, warped.data());
        working.swap(warped);
        outW = g.outputWidth;
        outH = g.outputHeight;
        outScale = static_cast<double>(width) / g.outputWidth;
    }
    // Stage 13: the layers above the photo, in output space; then the finishing over them all.
    layers::Region layerRegion;
    layerRegion.width = width;
    layerRegion.height = height;
    layerRegion.scale = outScale;
    std::vector<float> background;  // local mask components read the photo under the layers (Stage 17)
    if (maskLayer != nullptr && maskView != 0) background = working;
    layers::compositeLayers(graph, working.data(), layerRegion, false, nullptr);
    Finishing finishing;
    if (maskLayer != nullptr && maskView != 0) {
        layers::showMask(graph, *maskLayer, static_cast<layers::MaskView>(maskView), working.data(), layerRegion,
                         background.data());
    } else if (finishingOf(graph, &finishing)) applyFinishing(finishing, working.data(), width, height, outW, outH, 0, 0, outScale);
    out->width = width;
    out->height = height;
    out->rgba.swap(working);
    return true;
}

bool renderThumbnail(const ImageF32& source, const GuidedCoefficients* coefficients, const std::string& graphJson,
                     std::vector<std::uint8_t>* out, std::string* error, int* width, int* height, const DetailFields* fields,
                     int fullWidth) {
    Graph graph;
    if (!parseGraph(graphJson, &graph, error)) return false;
    ImageF32 image;
    renderGraphImage(source, coefficients, graph, &image, fields, fullWidth, kMaxThumbnailEdge);
    *width = image.width;
    *height = image.height;
    out->resize(image.rgba.size());
    color::workingToDisplay8(image.rgba.data(), out->data(), image.rgba.size() / 4, color::Display::Srgb);
    return true;
}

}  // namespace lo
