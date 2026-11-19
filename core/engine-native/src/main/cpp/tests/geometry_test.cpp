// Stage 9 geometry: the CPU warp against OpenCV (warpPerspective / remap, bicubic), the
// vignetting correction against Python, footprints and the gain map warp.
#include <cmath>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/color.h"
#include "lineover/geometry.h"
#include "lineover/graph.h"
#include "lineover/thumbnail.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

namespace {

std::string readText(const std::string& path) {
    std::ifstream in(path);
    std::stringstream s;
    s << in.rdbuf();
    return s.str();
}

struct Case {
    const char* name;
    const char* input;
};
constexpr Case kCases[] = {{"rotate", "pattern"}, {"perspective", "scene"}, {"flip_rotate_crop", "scene"}, {"lens", "scene"}, {"lens_profile", "colors"},
                           {"chromatic", "colors"}};

lo::Graph graphOf(const char* name) {
    lo::Graph graph;
    std::string error;
    LO_CHECK(lo::parseGraph(readText(goldenPath(std::string("geometry/") + name + ".graph.json")), &graph, &error));
    return graph;
}

ImageF32 warpAll(const lo::Geometry& g, const ImageF32& input) {
    ImageF32 out;
    out.width = g.outputWidth;
    out.height = g.outputHeight;
    out.channels = 4;
    out.data.resize(static_cast<std::size_t>(out.width) * out.height * 4);
    lo::WarpSource s;
    s.rgba = input.data.data();
    s.width = input.width;
    s.height = input.height;
    lo::warp(g, input.width, input.height, s, 1.0, 0, 0, out.width, out.height, out.data.data());
    return out;
}

}  // namespace

LO_TEST(warp_matches_opencv_for_every_case) {
    for (const Case& c : kCases) {
        const ImageF32 input = readNpyF32(goldenPath(std::string("input/") + c.input + "_rec2020_f32.npy"));
        lo::Geometry g;
        LO_CHECK(lo::geometryOf(graphOf(c.name), &g));
        const ImageF32 actual = warpAll(g, input);
        LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath(std::string("geometry/") + c.name + "_f32.npy")),
                                      std::string("geometry_") + c.name, kToleranceFloat));
    }
}

LO_TEST(a_tile_rendered_from_its_footprint_equals_the_full_warp) {
    const ImageF32 input = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    for (const char* name : {"perspective", "lens", "flip_rotate_crop"}) {
        lo::Geometry g;
        LO_CHECK(lo::geometryOf(graphOf(name), &g));
        const ImageF32 full = warpAll(g, input);
        const int tx = 17, ty = 9, tw = 31, th = 23;
        int fp[4];
        lo::footprint(g, input.width, input.height, tx, ty, tw, th, fp);
        std::vector<float> part(static_cast<std::size_t>(fp[2]) * fp[3] * 4);
        for (int y = 0; y < fp[3]; ++y)
            for (int x = 0; x < fp[2]; ++x)
                for (int k = 0; k < 4; ++k)
                    part[4 * (static_cast<std::size_t>(y) * fp[2] + x) + k] =
                        input.data[4 * (static_cast<std::size_t>(fp[1] + y) * input.width + fp[0] + x) + k];
        lo::WarpSource s;
        s.rgba = part.data();
        s.width = fp[2];
        s.height = fp[3];
        s.x0 = fp[0];
        s.y0 = fp[1];
        std::vector<float> tile(static_cast<std::size_t>(tw) * th * 4);
        lo::warp(g, input.width, input.height, s, 1.0, tx, ty, tw, th, tile.data());
        for (int y = 0; y < th; ++y)
            for (int x = 0; x < tw; ++x)
                for (int k = 0; k < 4; ++k) {
                    const float a = tile[4 * (static_cast<std::size_t>(y) * tw + x) + k];
                    const float b = full.data[4 * (static_cast<std::size_t>(ty + y) * full.width + tx + x) + k];
                    LO_CHECK(a == b);
                }
    }
}

LO_TEST(vignetting_matches_python_at_three_settings) {
    const ImageF32 input = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    const float settings[3][2] = {{50.f, 50.f}, {-40.f, 20.f}, {100.f, 80.f}};
    for (int i = 0; i < 3; ++i) {
        auto op = lo::makeVignetting(settings[i][0], settings[i][1], static_cast<float>(input.width) / input.height);
        ImageF32 actual = input;
        lo::OpRegion r;
        r.width = input.width;
        r.height = input.height;
        op->cpuRefRegion(input.data.data(), actual.data.data(), r);
        LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath("geometry/vignetting_" + std::to_string(i + 1) + "_f32.npy")),
                                      "vignetting_" + std::to_string(i + 1), kToleranceFloat));
    }
}

// A lens profile's "pa" vignetting on top of a manual amount (reference.VIGNETTING_PROFILE).
LO_TEST(profile_vignetting_matches_python) {
    const ImageF32 input = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    const float profile[3] = {-0.42f, 0.11f, -0.04f};
    auto op = lo::makeVignetting(20.f, 50.f, static_cast<float>(input.width) / input.height, profile);
    LO_CHECK(!op->isIdentity());
    ImageF32 actual = input;
    lo::OpRegion r;
    r.width = input.width;
    r.height = input.height;
    op->cpuRefRegion(input.data.data(), actual.data.data(), r);
    LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath("geometry/vignetting_profile_f32.npy")), "vignetting_profile",
                                  kToleranceFloat));
    // Through the graph: the geometry op's vignetteProfile array alone adds the op.
    lo::Graph graph;
    std::string error;
    LO_CHECK(lo::parseGraph(R"({"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"geometry","sourceWidth":4.0,)"
                            R"("sourceHeight":3.0,"outputWidth":4.0,"outputHeight":3.0,"vignetteProfile":[-0.42,0.11,-0.04],)"
                            R"("matrix":[4.0,0.0,0.0,0.0,3.0,0.0,0.0,0.0,1.0]}]}]})",
                            &graph, &error));
    std::string skipped;
    auto ops = lo::buildOps(graph, &skipped);
    LO_CHECK(skipped.empty());
    LO_CHECK(!ops->isIdentity());
}

LO_TEST(the_geometry_op_puts_vignetting_first_and_is_not_an_unknown_op) {
    lo::Graph graph;
    std::string error;
    LO_CHECK(lo::parseGraph(
        R"({"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"geometry","sourceWidth":128.0,"sourceHeight":96.0,)"
        R"("outputWidth":128.0,"outputHeight":96.0,"vignette":50.0,"vignetteMid":50.0,"matrix":[128,0,0,0,96,0,0,0,1]},)"
        R"({"op":"exposure","ev":1.0}]}]})",
        &graph, &error));
    std::string skipped;
    const auto ops = lo::buildOps(graph, &skipped);
    LO_CHECK(skipped.empty());
    LO_CHECK(ops->ops().size() == 2u);
    LO_CHECK(ops->ops()[0]->type() == lo::OpType::Vignetting);
}

LO_TEST(the_gain_map_follows_the_geometry) {
    // An 8 x 6 "gain map" of a 128 x 96 photo, flipped and rotated with the photo.
    std::vector<std::uint8_t> map(8 * 6 * 4);
    for (int y = 0; y < 6; ++y)
        for (int x = 0; x < 8; ++x)
            for (int k = 0; k < 4; ++k) map[4 * (y * 8 + x) + k] = static_cast<std::uint8_t>(k == 3 ? 255 : 30 * x + 5 * y);
    lo::Geometry identity;
    identity.matrix[0] = 128;
    identity.matrix[4] = 96;
    identity.outputWidth = 128;
    identity.outputHeight = 96;
    LO_CHECK(lo::warpRgba8(identity, 128, 96, map.data(), 8, 6, 8, 6) == map);
    // A horizontal flip: output column x reads source column 7 - x.
    lo::Geometry flip = identity;
    flip.matrix[0] = -128;
    flip.matrix[2] = 128;
    const auto flipped = lo::warpRgba8(flip, 128, 96, map.data(), 8, 6, 8, 6);
    for (int y = 0; y < 6; ++y)
        for (int x = 0; x < 8; ++x) LO_CHECK(flipped[4 * (y * 8 + x)] == map[4 * (y * 8 + 7 - x)]);
}

LO_TEST(thumbnails_apply_the_geometry) {
    const ImageF32 input = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    lo::ImageF32 source;
    source.width = input.width;
    source.height = input.height;
    source.rgba = input.data;
    std::vector<std::uint8_t> out;
    int w = 0, h = 0;
    std::string error;
    LO_CHECK(lo::renderThumbnail(source, nullptr, readText(goldenPath("geometry/flip_rotate_crop.graph.json")), &out, &error, &w, &h));
    LO_CHECK(w == 60 && h == 80);
    std::vector<std::uint8_t> expected(out.size());
    const ImageF32 golden = readNpyF32(goldenPath("geometry/flip_rotate_crop_f32.npy"));
    lo::color::workingToDisplay8(golden.data.data(), expected.data(), golden.data.size() / 4, lo::color::Display::Srgb);
    int worst = 0;
    for (std::size_t i = 0; i < out.size(); ++i) worst = std::max(worst, std::abs(int(out[i]) - int(expected[i])));
    LO_CHECK(worst <= 1);
}

LO_TEST(the_forward_map_inverts_the_sampling_map) {
    for (const char* name : {"rotate", "perspective", "flip_rotate_crop", "lens"}) {
        lo::Geometry g;
        LO_CHECK(lo::geometryOf(graphOf(name), &g));
        const int sw = g.sourceWidth, sh = g.sourceHeight;
        for (double u : {0.1, 0.5, 0.83})
            for (double v : {0.2, 0.5, 0.9}) {
                double x, y, ox, oy;
                lo::mapToSource(g, sw, sh, u, v, 1, &x, &y);
                LO_CHECK(lo::sourceToOutput(g, sw, sh, x, y, &ox, &oy));
                LO_CHECK(std::fabs(ox - u * g.outputWidth) < 1e-6 && std::fabs(oy - v * g.outputHeight) < 1e-6);
            }
        // A source rect maps to an output rect containing the mapped points of its interior.
        const double rect[4] = {sw * 0.3, sh * 0.25, sw * 0.4, sh * 0.5};
        double out[4];
        if (lo::outputRectOf(g, sw, sh, rect, out)) {
            double ox, oy;
            LO_CHECK(lo::sourceToOutput(g, sw, sh, rect[0] + rect[2] / 2, rect[1] + rect[3] / 2, &ox, &oy));
            LO_CHECK(ox >= out[0] && ox <= out[0] + out[2] && oy >= out[1] && oy <= out[1] + out[3]);
        }
    }
}

int main() { return runAllTests(); }
