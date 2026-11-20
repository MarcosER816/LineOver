// Layer masks and clipping (Stage 14) against tools/golden/blend_reference.py: the rasterised
// mask (full and preview scale), its feather against OpenCV's GaussianBlur, R16F storage,
// regions that meet without seams, the compositor scene with masks and clipping (full, half,
// linear, in export tiles), the mask views and the mask JSON round trip.
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
#include "lineover/masks.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

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

// The mask of tools/golden/blend_reference.test_mask().
lo::LayerMask testMask() {
    lo::Graph g;
    std::string error;
    LO_CHECK(lo::parseGraph(
        R"({"schemaVersion":1,"layers":[{"id":"background","ops":[]},{"id":"m","ops":[],"type":"adjustment","mask":{"paint":[)"
        R"({"kind":"stroke","value":0.0,"radius":14.0,"hardness":0.4,"opacity":100.0,"points":[10.3,20.1,60.7,35.2,110.9,80.4]},)"
        R"({"kind":"stroke","value":1.0,"radius":4.5,"hardness":1.0,"opacity":100.0,"points":[30.2,28.6,70.4,40.3]},)"
        R"({"kind":"stroke","value":0.0,"radius":9.0,"hardness":0.0,"opacity":50.0,"points":[3.4,90.2]}]}}]})",
        &g, &error));
    LO_CHECK(g.layers.size() == 2 && g.layers[1].hasMask);
    return g.layers[1].mask;
}

ImageF32 single(int w, int h, const std::vector<float>& v) {
    ImageF32 out;
    out.width = w;
    out.height = h;
    out.channels = 1;
    out.data = v;
    return out;
}

ImageF32 composite(const lo::Graph& graph, const ImageF32& background, double scale, int ox = 0, int oy = 0, int docW = 0,
                   int docH = 0) {
    ImageF32 out = background;
    lo::layers::Region region;
    region.width = out.width;
    region.height = out.height;
    region.scale = scale;
    region.originX = ox;
    region.originY = oy;
    region.docWidth = docW;
    region.docHeight = docH;
    std::string skipped;
    lo::layers::compositeLayers(graph, out.data.data(), region, false, &skipped);
    LO_CHECK(skipped.empty());
    return out;
}

ImageF32 crop(const ImageF32& in, int x0, int y0, int w, int h) {
    ImageF32 out;
    out.width = w;
    out.height = h;
    out.channels = in.channels;
    for (int y = y0; y < y0 + h; ++y)
        for (int x = x0; x < x0 + w; ++x)
            for (int c = 0; c < in.channels; ++c)
                out.data.push_back(in.data[(static_cast<std::size_t>(y) * in.width + x) * in.channels + c]);
    return out;
}

double maxDiff(const ImageF32& a, const ImageF32& b) {
    double d = 0.0;
    for (std::size_t i = 0; i < a.data.size(); ++i) d = std::max(d, std::fabs(static_cast<double>(a.data[i]) - b.data[i]));
    return d;
}

}  // namespace

LO_TEST(the_mask_rasterises_like_the_reference) {
    const lo::LayerMask mask = testMask();
    std::vector<float> raw(128 * 96);
    lo::masks::rasterize(mask, 0, 0, 128, 96, 1.0, raw.data());
    LO_CHECK_GOLDEN(compareGolden(single(128, 96, raw), readNpyF32(goldenPath("mask/raster_f32.npy")), "mask_raster"));
    std::vector<float> half(64 * 48);
    lo::masks::rasterize(mask, 0, 0, 64, 48, 0.5, half.data());
    LO_CHECK_GOLDEN(compareGolden(single(64, 48, half), readNpyF32(goldenPath("mask/raster_half_f32.npy")), "mask_raster_half"));
}

LO_TEST(feather_matches_opencv_gaussian_blur) {
    const ImageF32 raw = readNpyF32(goldenPath("mask/raster_f32.npy"));
    for (const char* sigma : {"0.8", "2.5", "6.0"}) {
        std::vector<float> out(raw.data.size());
        lo::masks::gaussianBlur(raw.data.data(), raw.width, raw.height, std::stod(sigma), out.data());
        LO_CHECK_GOLDEN(compareGolden(single(raw.width, raw.height, out),
                                      readNpyF32(goldenPath(std::string("mask/feather_") + sigma + "_f32.npy")),
                                      std::string("mask_feather_") + sigma));
    }
    // The rendered mask (R16F) of a feathered mask is the same blur, within half precision.
    lo::LayerMask mask = testMask();
    mask.feather = 2.5;
    lo::masks::MaskTiles tiles;
    lo::masks::MaskRegion region;
    region.width = 128;
    region.height = 96;
    lo::masks::render(mask, region, &tiles);
    std::vector<float> v(128 * 96);
    for (int y = 0; y < 96; ++y)
        for (int x = 0; x < 128; ++x) v[static_cast<std::size_t>(y) * 128 + x] = tiles.at(x, y);
    LO_CHECK_GOLDEN(compareGolden(single(128, 96, v), readNpyF32(goldenPath("mask/feather_2.5_f32.npy")), "mask_render_feather"));
}

LO_TEST(half_floats_round_trip_and_round_to_nearest_even) {
    for (int h = 0; h < 65536; ++h) {
        const auto bits = static_cast<std::uint16_t>(h);
        if (((bits >> 10) & 0x1f) == 0x1f && (bits & 0x3ff) != 0) continue;  // NaN
        LO_CHECK(lo::masks::toHalf(lo::masks::fromHalf(bits)) == bits);
    }
    LO_CHECK(lo::masks::fromHalf(lo::masks::toHalf(1.f)) == 1.f);
    LO_CHECK(lo::masks::fromHalf(lo::masks::toHalf(0.5f)) == 0.5f);
    // 1 + 2^-11 is halfway between 1 and the next half (1 + 2^-10): ties go to even (1).
    LO_CHECK(lo::masks::fromHalf(lo::masks::toHalf(1.f + std::ldexp(1.f, -11))) == 1.f);
    LO_CHECK(std::fabs(lo::masks::fromHalf(lo::masks::toHalf(0.3f)) - 0.3f) < 2.5e-4f);
}

LO_TEST(regions_meet_without_seams) {
    // A heavily feathered mask rendered as four tiles equals the whole, crop by crop.
    lo::LayerMask mask = testMask();
    mask.feather = 6.0;
    mask.inverted = true;
    mask.density = 70.0;
    lo::masks::MaskRegion whole;
    whole.width = 128;
    whole.height = 96;
    lo::masks::MaskTiles all;
    lo::masks::render(mask, whole, &all);
    const int xs[] = {0, 50, 128}, ys[] = {0, 37, 96};
    double worst = 0.0;
    for (int ty = 0; ty < 2; ++ty)
        for (int tx = 0; tx < 2; ++tx) {
            lo::masks::MaskRegion r;
            r.originX = xs[tx];
            r.originY = ys[ty];
            r.width = xs[tx + 1] - xs[tx];
            r.height = ys[ty + 1] - ys[ty];
            r.docWidth = 128;
            r.docHeight = 96;
            lo::masks::MaskTiles part;
            lo::masks::render(mask, r, &part);
            for (int y = 0; y < r.height; ++y)
                for (int x = 0; x < r.width; ++x)
                    worst = std::max(worst, static_cast<double>(std::fabs(part.at(x, y) - all.at(x + r.originX, y + r.originY))));
        }
    LO_CHECK(worst == 0.0);
    if (worst != 0.0) std::cerr << "  seam: " << worst << "\n";
}

LO_TEST(the_compositor_scene_with_masks_and_clipping_matches_the_reference) {
    registerSource();
    const ImageF32 scene = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    const lo::Graph graph = graphFrom("mask/scene_graph.json");
    const ImageF32 full = composite(graph, scene, 1.0);
    LO_CHECK_GOLDEN(compareGolden(full, readNpyF32(goldenPath("mask/scene_f32.npy")), "mask_scene"));
    LO_CHECK_GOLDEN(compareGolden(composite(graphFrom("mask/scene_linear_graph.json"), scene, 1.0),
                                  readNpyF32(goldenPath("mask/scene_linear_f32.npy")), "mask_scene_linear"));
    ImageF32 half;
    half.width = 64;
    half.height = 48;
    half.channels = 4;
    for (int y = 0; y < 96; y += 2)
        for (int x = 0; x < 128; x += 2)
            for (int c = 0; c < 4; ++c) half.data.push_back(scene.data[(static_cast<std::size_t>(y) * 128 + x) * 4 + c]);
    LO_CHECK_GOLDEN(compareGolden(composite(graph, half, 0.5), readNpyF32(goldenPath("mask/scene_half_f32.npy")), "mask_scene_half"));
    // Export tiles: each equals that part of the whole (feathered masks read their neighbours).
    const int xs[] = {0, 47, 128}, ys[] = {0, 61, 96};
    double worst = 0.0;
    for (int ty = 0; ty < 2; ++ty)
        for (int tx = 0; tx < 2; ++tx) {
            const int w = xs[tx + 1] - xs[tx], h = ys[ty + 1] - ys[ty];
            const ImageF32 part = composite(graph, crop(scene, xs[tx], ys[ty], w, h), 1.0, xs[tx], ys[ty], 128, 96);
            worst = std::max(worst, maxDiff(part, crop(full, xs[tx], ys[ty], w, h)));
        }
    LO_CHECK(worst < 1e-6);
    if (worst >= 1e-6) std::cerr << "  tile seam: " << worst << "\n";
}

LO_TEST(mask_views_show_the_mask) {
    registerSource();
    const lo::Graph graph = graphFrom("mask/scene_graph.json");
    lo::layers::Region region;
    region.width = 128;
    region.height = 96;
    // Layer 4 (the exposure adjustment) has an inverted, feathered gradient mask.
    const std::string id = graph.layers[4].id;
    LO_CHECK(graph.layers[4].hasMask);
    std::vector<float> rgba(128 * 96 * 4, 0.25f);
    lo::layers::showMask(graph, id, lo::layers::MaskView::MaskOnly, rgba.data(), region);
    // Grey everywhere; dark where the mask hides (top left: inverted gradient starts at 0).
    LO_CHECK(rgba[0] == rgba[1] && rgba[1] == rgba[2] && rgba[3] == 1.f);
    const std::size_t far = (static_cast<std::size_t>(95) * 128 + 127) * 4;
    LO_CHECK(rgba[0] < 0.05f && rgba[far] > 0.9f);
    std::vector<float> over(128 * 96 * 4, 0.25f);
    lo::layers::showMask(graph, id, lo::layers::MaskView::Overlay, over.data(), region);
    LO_CHECK(over[0] > over[1] && over[far] == over[far + 1]);  // red where hidden, unchanged where shown
    std::vector<float> none(128 * 96 * 4, 0.25f);
    lo::layers::showMask(graph, "no-such-layer", lo::layers::MaskView::Overlay, none.data(), region);
    LO_CHECK(none[0] == 0.25f);
}

LO_TEST(mask_json_round_trips) {
    const std::string fixture = readText(std::string(LO_TESTDATA_DIR) + "/graph/edit_graph_masks.json");
    const std::string text = fixture.substr(0, fixture.find_last_not_of("\n") + 1);
    lo::Graph g;
    std::string error;
    LO_CHECK(lo::parseGraph(text, &g, &error));
    LO_CHECK(g.layers.size() == 3 && g.layers[1].hasMask && g.layers[2].clip && !g.layers[2].mask.enabled);
    LO_CHECK(g.layers[1].mask.paint.size() == 2 && g.layers[1].mask.paint[1].points.size() == 4);
    const std::string again = lo::serializeGraph(g);
    LO_CHECK(again == text);
    if (again != text) std::cerr << "  " << again << "\n  " << text << "\n";
    // Layers without masks write nothing new.
    LO_CHECK(lo::parseGraph(R"({"schemaVersion":1,"layers":[{"id":"background","ops":[]}]})", &g, &error));
    LO_CHECK(lo::serializeGraph(g) == R"({"schemaVersion":1,"layers":[{"id":"background","ops":[]}]})");
}

int main() { return runAllTests(); }
