// Thumbnails: the box filter and a graph rendered on the CPU path.
#include <cmath>
#include <string>
#include <vector>

#include "lineover/color.h"
#include "lineover/thumbnail.h"
#include "lo_test.h"

namespace {

std::vector<float> ramp(int w, int h) {
    std::vector<float> rgba(static_cast<std::size_t>(w) * h * 4);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float* p = rgba.data() + 4 * (static_cast<std::size_t>(y) * w + x);
            p[0] = x / static_cast<float>(w);
            p[1] = y / static_cast<float>(h);
            p[2] = 0.25f;
            p[3] = 1.f;
        }
    return rgba;
}

}  // namespace

LO_TEST(box_downsample_brings_the_long_edge_under_the_limit) {
    const auto rgba = ramp(1000, 750);
    const lo::ImageF32 small = lo::boxDownsample(rgba.data(), 1000, 750, 256);
    LO_CHECK(small.width == 250 && small.height == 188);  // factor 4; the last row averages 2 rows
    // The first block averages x = 0..3: (0 + 1 + 2 + 3) / 4 / 1000.
    LO_CHECK(std::fabs(small.rgba[0] - 1.5f / 1000.f) < 1e-6f);
    LO_CHECK(std::fabs(small.rgba[3] - 1.f) < 1e-6f);
    const lo::ImageF32 same = lo::boxDownsample(rgba.data(), 200, 100, 256);
    LO_CHECK(same.width == 200 && same.height == 100);
}

LO_TEST(a_graph_renders_like_its_ops_then_the_display_transform) {
    const auto rgba = ramp(64, 48);
    const lo::ImageF32 source = lo::boxDownsample(rgba.data(), 64, 48, 64);
    std::vector<std::uint8_t> identity, brighter, expected(source.rgba.size());
    std::string error;
    LO_CHECK(lo::renderThumbnail(source, nullptr, R"({"schemaVersion":1,"layers":[]})", &identity, &error));
    lo::color::workingToDisplay8(source.rgba.data(), expected.data(), source.rgba.size() / 4, lo::color::Display::Srgb);
    LO_CHECK(identity == expected);
    LO_CHECK(lo::renderThumbnail(source, nullptr,
                                 R"({"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"exposure","ev":1.0}]}]})",
                                 &brighter, &error));
    std::vector<float> doubled = source.rgba;
    for (std::size_t i = 0; i < doubled.size(); ++i)
        if (i % 4 != 3) doubled[i] *= 2.f;
    lo::color::workingToDisplay8(doubled.data(), expected.data(), doubled.size() / 4, lo::color::Display::Srgb);
    int worst = 0;
    for (std::size_t i = 0; i < expected.size(); ++i) worst = std::max(worst, std::abs(int(brighter[i]) - int(expected[i])));
    LO_CHECK(worst <= 1);
    LO_CHECK(!lo::renderThumbnail(source, nullptr, "not json", &brighter, &error));
    LO_CHECK(!error.empty());
}

int main() { return lo::testing::runAllTests(); }
