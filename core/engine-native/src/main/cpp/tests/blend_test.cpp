// Layers (Stage 13) against tools/golden/blend_reference.py: every blend mode in both
// blending spaces (float tolerance 1e-3), the brief's edge cases, and the compositor scene
// (raster, smart object, adjustment, pass-through and isolated groups, hidden and text
// layers) at full size, at preview scale and in a region; the layered graph JSON round trip.
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/graph.h"
#include "lineover/layers.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;
using lo::layers::BlendMode;

namespace {

std::string readText(const std::string& path) {
    std::ifstream file(path);
    std::stringstream s;
    s << file.rdbuf();
    return s.str();
}

lo::Graph graphFrom(const std::string& goldenFile) {
    lo::Graph g;
    std::string error;
    const bool ok = lo::parseGraph(readText(goldenPath(goldenFile)), &g, &error);
    LO_CHECK(ok);
    if (!ok) std::cerr << "  " << error << "\n";
    return g;
}

void registerSource() {
    const ImageF32 src = readNpyF32(goldenPath("blend/layer_source_f32.npy"));
    auto image = std::make_shared<lo::ImageF32>();
    image->width = src.width;
    image->height = src.height;
    image->rgba = src.data;
    lo::layers::registerLayerImage("s1", image, false);
}

ImageF32 composite(const std::string& graphFile, const ImageF32& background, double scale, double ox = 0, double oy = 0) {
    ImageF32 out = background;
    lo::layers::Region region;
    region.width = out.width;
    region.height = out.height;
    region.scale = scale;
    region.originX = ox;
    region.originY = oy;
    std::string skipped;
    lo::layers::compositeLayers(graphFrom(graphFile), out.data.data(), region, false, &skipped);
    LO_CHECK(skipped.empty());
    if (!skipped.empty()) std::cerr << "  skipped: " << skipped << "\n";
    return out;
}

ImageF32 every2(const ImageF32& in) {
    ImageF32 out;
    out.width = (in.width + 1) / 2;
    out.height = (in.height + 1) / 2;
    out.channels = 4;
    for (int y = 0; y < in.height; y += 2)
        for (int x = 0; x < in.width; x += 2)
            for (int c = 0; c < 4; ++c) out.data.push_back(in.data[(static_cast<std::size_t>(y) * in.width + x) * 4 + c]);
    return out;
}

}  // namespace

LO_TEST(every_blend_mode_matches_the_reference_in_both_spaces) {
    for (const char* space : {"gamma", "linear"}) {
        const std::string s(space);
        const ImageF32 back = readNpyF32(goldenPath("blend/" + s + "_backdrop_f32.npy"));
        const ImageF32 src = readNpyF32(goldenPath("blend/" + s + "_source_f32.npy"));
        for (int m = 0; m < lo::layers::kBlendModeCount; ++m) {
            const auto mode = static_cast<BlendMode>(m);
            ImageF32 out = back;
            for (int y = 0; y < back.height; ++y)
                for (int x = 0; x < back.width; ++x) {
                    const std::size_t i = (static_cast<std::size_t>(y) * back.width + x) * 4;
                    lo::layers::compositePixel(mode, 1.f, s == "gamma", &back.data[i], &src.data[i], x + 0.5, y + 0.5, &out.data[i]);
                }
            const std::string name = std::string("blend/") + s + "_" + lo::layers::blendModeName(mode) + "_f32.npy";
            LO_CHECK_GOLDEN(compareGolden(out, readNpyF32(goldenPath(name)), "blend_" + s + "_" + lo::layers::blendModeName(mode)));
        }
    }
}

LO_TEST(the_briefs_edge_cases_hold) {
    const float zero[3] = {0, 0, 0}, one[3] = {1, 1, 1}, half[3] = {0.5f, 0.5f, 0.5f};
    float out[3];
    lo::layers::blendColor(BlendMode::ColorDodge, zero, one, out);  // dodge(0, 1) = 0
    LO_CHECK(out[0] == 0.f && out[1] == 0.f && out[2] == 0.f);
    lo::layers::blendColor(BlendMode::ColorBurn, one, zero, out);  // burn(1, 0) = 1
    LO_CHECK(out[0] == 1.f && out[1] == 1.f && out[2] == 1.f);
    lo::layers::blendColor(BlendMode::ColorDodge, half, one, out);
    LO_CHECK(out[0] == 1.f);
    lo::layers::blendColor(BlendMode::ColorBurn, half, zero, out);
    LO_CHECK(out[0] == 0.f);
    const float a[3] = {0.7f, 0.7f, 0.7f}, b[3] = {0.6f, 0.6f, 0.6f};
    lo::layers::blendColor(BlendMode::LinearBurn, a, b, out);  // a + b - 1
    LO_CHECK(std::fabs(out[0] - 0.3f) < 1e-6f);
    lo::layers::blendColor(BlendMode::LinearDodge, b, half, out);  // a + b, clipped at 1
    LO_CHECK(out[0] == 1.f);
    // Names round-trip; there are 27.
    for (int m = 0; m < lo::layers::kBlendModeCount; ++m) {
        BlendMode back;
        LO_CHECK(lo::layers::blendModeFromName(lo::layers::blendModeName(static_cast<BlendMode>(m)), &back));
        LO_CHECK(static_cast<int>(back) == m);
    }
    BlendMode unused;
    LO_CHECK(!lo::layers::blendModeFromName("not_a_mode", &unused));
}

LO_TEST(the_compositor_scene_matches_the_reference) {
    registerSource();
    const ImageF32 scene = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    LO_CHECK_GOLDEN(compareGolden(composite("blend/scene_graph.json", scene, 1.0), readNpyF32(goldenPath("blend/scene_gamma_f32.npy")),
                                  "blend_scene"));
    LO_CHECK_GOLDEN(compareGolden(composite("blend/scene_passthrough_graph.json", scene, 1.0),
                                  readNpyF32(goldenPath("blend/scene_passthrough_f32.npy")), "blend_scene_passthrough"));
    LO_CHECK_GOLDEN(compareGolden(composite("blend/scene_linear_graph.json", scene, 1.0),
                                  readNpyF32(goldenPath("blend/scene_linear_f32.npy")), "blend_scene_linear"));
    // The preview: half the document.
    LO_CHECK_GOLDEN(compareGolden(composite("blend/scene_graph.json", every2(scene), 0.5), readNpyF32(goldenPath("blend/scene_half_f32.npy")),
                                  "blend_scene_half"));
}

LO_TEST(isolation_changes_the_result_and_regions_match_the_whole) {
    registerSource();
    const ImageF32 scene = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    const ImageF32 isolated = composite("blend/scene_graph.json", scene, 1.0);
    const ImageF32 through = composite("blend/scene_passthrough_graph.json", scene, 1.0);
    double worst = 0.0;
    for (std::size_t i = 0; i < isolated.data.size(); ++i) worst = std::max(worst, std::fabs(static_cast<double>(isolated.data[i]) - through.data[i]));
    LO_CHECK(worst > 0.1);  // an isolated group differs from a pass-through one
    // An export band (rows 30..69, columns 20..99) equals that part of the whole.
    ImageF32 band;
    band.width = 80;
    band.height = 40;
    band.channels = 4;
    for (int y = 30; y < 70; ++y)
        for (int x = 20; x < 100; ++x)
            for (int c = 0; c < 4; ++c) band.data.push_back(scene.data[(static_cast<std::size_t>(y) * scene.width + x) * 4 + c]);
    const ImageF32 part = composite("blend/scene_graph.json", band, 1.0, 20, 30);
    double diff = 0.0;
    for (int y = 0; y < 40; ++y)
        for (int x = 0; x < 80; ++x)
            for (int c = 0; c < 4; ++c)
                diff = std::max(diff, std::fabs(static_cast<double>(part.data[(static_cast<std::size_t>(y) * 80 + x) * 4 + c]) -
                                                isolated.data[(static_cast<std::size_t>(y + 30) * scene.width + x + 20) * 4 + c]));
    LO_CHECK(diff < 1e-6);
}

LO_TEST(missing_images_and_hidden_layers) {
    lo::Graph g;
    std::string error;
    LO_CHECK(lo::parseGraph(R"({"schemaVersion":1,"layers":[{"id":"background","ops":[]},{"id":"x","ops":[],"type":"raster","source":"nope"}]})", &g, &error));
    LO_CHECK(lo::layers::hasLayers(g));
    std::vector<float> rgba(4 * 4, 0.5f);
    lo::layers::Region region;
    region.width = 2;
    region.height = 2;
    std::string skipped;
    lo::layers::compositeLayers(g, rgba.data(), region, false, &skipped);
    LO_CHECK(skipped == "layer:nope");
    LO_CHECK(rgba[0] == 0.5f);
    LO_CHECK(lo::parseGraph(R"({"schemaVersion":1,"layers":[{"id":"background","ops":[]},{"id":"x","ops":[],"type":"raster","visible":false},{"id":"t","ops":[],"type":"text"}]})", &g, &error));
    LO_CHECK(!lo::layers::hasLayers(g));
    // Only the background's ops become the develop OpList.
    LO_CHECK(lo::parseGraph(R"({"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"invert"}]},{"id":"a","ops":[{"op":"invert"}],"type":"adjustment"}]})", &g, &error));
    LO_CHECK(lo::buildOps(g, nullptr)->ops().size() == 1);
}

LO_TEST(layered_graph_json_round_trips) {
    const std::string fixture = readText(std::string(LO_TESTDATA_DIR) + "/graph/edit_graph_layers.json");
    const std::string text = fixture.substr(0, fixture.find_last_not_of("\n") + 1);
    lo::Graph g;
    std::string error;
    LO_CHECK(lo::parseGraph(text, &g, &error));
    LO_CHECK(g.layers.size() == 3 && g.layers[2].children.size() == 1 && g.blendSpace == "linear");
    LO_CHECK(!g.layers[2].visible && g.layers[2].locked && g.layers[2].isolated);
    const std::string again = lo::serializeGraph(g);
    LO_CHECK(again == text);
    if (again != text) std::cerr << "  " << again << "\n  " << text << "\n";
}

int main() { return runAllTests(); }
