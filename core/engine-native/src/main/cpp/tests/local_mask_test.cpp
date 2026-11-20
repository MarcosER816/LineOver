// Local masks (Stage 17) against tools/golden/local_mask_reference.py: every mask generator
// (linear, radial, inverted radial, brush, brush with auto-mask, colour range, luminance range,
// depth range, model raster) and a group combining them (full and half scale), then the mask
// math: the combine identities, duplicate and invert, a masked op is the lerp of the op by the
// mask and the amount, tiles meet without seams, and the component JSON round trip.
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/color.h"
#include "lineover/detail.h"
#include "lineover/light.h"
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

const lo::Graph& goldenGraph() {
    static const lo::Graph graph = [] {
        lo::Graph g;
        std::string error;
        const bool ok = lo::parseGraph(readText(goldenPath("localmask/graph.json")), &g, &error);
        LO_CHECK(ok);
        if (!ok) std::cerr << "  " << error << "\n";
        return g;
    }();
    return graph;
}

const lo::LayerMask& maskOf(const std::string& id) {
    for (const lo::Layer& l : goldenGraph().layers)
        if (l.id == id) return l.mask;
    static const lo::LayerMask none;
    LO_CHECK(false);
    return none;
}

void registerRasters() {
    for (const char* name : {"depth", "subject"}) {
        const ImageF32 r = readNpyF32(goldenPath(std::string("localmask/") + name + "_raster_f32.npy"));
        lo::masks::registerRaster(name, std::make_shared<const std::vector<float>>(r.data), r.width, r.height);
    }
}

const ImageF32& scene() {
    static const ImageF32 s = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    return s;
}

ImageF32 halfScene() {
    const ImageF32& s = scene();
    ImageF32 out;
    out.width = s.width / 2;
    out.height = s.height / 2;
    out.channels = 4;
    for (int y = 0; y < s.height; y += 2)
        for (int x = 0; x < s.width; x += 2)
            for (int c = 0; c < 4; ++c) out.data.push_back(s.data[(static_cast<std::size_t>(y) * s.width + x) * 4 + c]);
    return out;
}

ImageF32 single(int w, int h, const std::vector<float>& v) {
    ImageF32 out;
    out.width = w;
    out.height = h;
    out.channels = 1;
    out.data = v;
    return out;
}

std::vector<float> raw(const lo::LayerMask& mask, const ImageF32& image, double scale) {
    std::vector<float> out(static_cast<std::size_t>(image.width) * image.height);
    lo::masks::rasterize(mask, 0, 0, image.width, image.height, scale, out.data(), image.data.data());
    return out;
}

std::vector<float> rendered(const lo::LayerMask& mask, const lo::masks::MaskRegion& region, const float* image) {
    lo::masks::MaskTiles tiles;
    lo::masks::render(mask, region, &tiles, image);
    std::vector<float> v(static_cast<std::size_t>(region.width) * region.height);
    for (int y = 0; y < region.height; ++y)
        for (int x = 0; x < region.width; ++x) v[static_cast<std::size_t>(y) * region.width + x] = tiles.at(x, y);
    return v;
}

lo::MaskComponent component(const std::string& id) { return maskOf(id).components.at(0); }

}  // namespace

LO_TEST(every_mask_generator_matches_the_reference) {
    registerRasters();
    for (const char* name : {"linear", "radial", "radial_inverted", "brush", "brush_auto", "color_range", "luminance_range",
                             "depth_range", "raster"}) {
        const lo::LayerMask& mask = maskOf(name);
        LO_CHECK(mask.components.size() == 1);
        LO_CHECK_GOLDEN(compareGolden(single(128, 96, raw(mask, scene(), 1.0)),
                                      readNpyF32(goldenPath(std::string("localmask/") + name + "_f32.npy")),
                                      std::string("localmask_") + name));
    }
}

LO_TEST(the_mask_group_matches_the_reference_at_full_and_half_scale) {
    registerRasters();
    const lo::LayerMask& group = maskOf("group");
    LO_CHECK(group.components.size() == 4);
    LO_CHECK_GOLDEN(compareGolden(single(128, 96, raw(group, scene(), 1.0)), readNpyF32(goldenPath("localmask/group_f32.npy")),
                                  "localmask_group"));
    const ImageF32 half = halfScene();
    LO_CHECK_GOLDEN(compareGolden(single(64, 48, raw(group, half, 0.5)), readNpyF32(goldenPath("localmask/group_half_f32.npy")),
                                  "localmask_group_half"));
}

LO_TEST(combine_follows_the_selection_rules) {
    const float values[] = {0.f, 0.2f, 0.5f, 0.75f, 1.f};
    for (float m : values) {
        for (float a : values) {
            LO_CHECK(lo::masks::combine(m, a, "add") == std::max(m, a));
            LO_CHECK(lo::masks::combine(m, a, "subtract") == std::min(m, 1.f - a));
            LO_CHECK(lo::masks::combine(m, a, "intersect") == std::min(m, a));
            // Add and intersect commute; adding nothing or intersecting with everything keeps M.
            LO_CHECK(lo::masks::combine(m, a, "add") == lo::masks::combine(a, m, "add"));
            LO_CHECK(lo::masks::combine(m, a, "intersect") == lo::masks::combine(a, m, "intersect"));
        }
        LO_CHECK(lo::masks::combine(m, 0.f, "add") == m);
        LO_CHECK(lo::masks::combine(m, 1.f, "intersect") == m);
        LO_CHECK(lo::masks::combine(m, 0.f, "subtract") == m);
        LO_CHECK(lo::masks::combine(m, 1.f, "subtract") == 0.f);
    }
    // A group of one added component is the component; inverting twice is the identity.
    registerRasters();
    lo::LayerMask one;
    one.components.push_back(component("radial"));
    lo::LayerMask twice = one;
    twice.components[0].invert = true;
    std::vector<float> a(128 * 96), b(128 * 96);
    lo::masks::evaluateComponent(one.components[0], 0, 0, 128, 96, 1.0, a.data(), nullptr);
    lo::masks::evaluateComponent(twice.components[0], 0, 0, 128, 96, 1.0, b.data(), nullptr);
    const std::vector<float> g = raw(one, scene(), 1.0);
    for (std::size_t q = 0; q < a.size(); ++q) {
        LO_CHECK(g[q] == a[q]);
        LO_CHECK(std::fabs(a[q] + b[q] - 1.f) < 1e-6f);
    }
    // Intersecting a linear gradient with the sky (the brief's phone check) is never more than either.
    lo::LayerMask sky;
    sky.components = {component("linear"), component("raster")};
    sky.components[1].mode = "intersect";
    const std::vector<float> both = raw(sky, scene(), 1.0), lin = raw(maskOf("linear"), scene(), 1.0),
                             ras = raw(maskOf("raster"), scene(), 1.0);
    for (std::size_t q = 0; q < both.size(); ++q) LO_CHECK(both[q] <= lin[q] && both[q] <= ras[q] && both[q] == std::min(lin[q], ras[q]));
}

LO_TEST(duplicate_and_invert_covers_the_image_exactly_once) {
    registerRasters();
    lo::masks::MaskRegion region;
    region.width = 128;
    region.height = 96;
    for (const char* name : {"group", "color_range", "brush_auto"}) {
        lo::LayerMask mask = maskOf(name);
        mask.feather = 3.0;
        lo::LayerMask inverted = mask;
        inverted.inverted = true;
        const std::vector<float> m = rendered(mask, region, scene().data.data());
        const std::vector<float> n = rendered(inverted, region, scene().data.data());
        double worst = 0.0;
        for (std::size_t q = 0; q < m.size(); ++q) worst = std::max(worst, std::fabs(static_cast<double>(m[q]) + n[q] - 1.0));
        LO_CHECK(worst < 1e-3);  // two R16F roundings
        if (worst >= 1e-3) std::cerr << "  " << name << ": " << worst << "\n";
    }
}

LO_TEST(a_masked_adjustment_is_the_lerp_of_the_op_by_mask_and_amount) {
    registerRasters();
    // Exposure +1 through the group at 60 % amount (opacity), in the gamma blending space.
    lo::Graph graph;
    std::string error;
    const std::string json =
        R"({"schemaVersion":1,"layers":[{"id":"bg","ops":[]},{"id":"m","ops":[{"op":"exposure_test","stops":1.0}],)"
        R"("type":"adjustment","opacity":60.0,"mask":)" +
        lo::serializeMaskJson(maskOf("group")) + "}]}";
    LO_CHECK(lo::parseGraph(json, &graph, &error));
    ImageF32 out = scene();
    lo::layers::Region region;
    region.width = out.width;
    region.height = out.height;
    std::string skipped;
    lo::layers::compositeLayers(graph, out.data.data(), region, false, &skipped);
    LO_CHECK(skipped.empty());
    lo::masks::MaskRegion mr;
    mr.width = 128;
    mr.height = 96;
    const std::vector<float> m = rendered(maskOf("group"), mr, scene().data.data());
    double worst = 0.0;
    for (std::size_t p = 0; p < m.size(); ++p) {
        const float k = 0.6f * m[p];
        for (int c = 0; c < 3; ++c) {
            const float in = scene().data[4 * p + c];
            const float lit = in * 2.f;
            const float eb = lo::color::srgbOetf(in);
            const float want = lo::color::srgbEotf(eb + k * (lo::color::srgbOetf(lit) - eb));
            worst = std::max(worst, std::fabs(static_cast<double>(want) - out.data[4 * p + c]));
        }
    }
    LO_CHECK(worst < 1e-4);
    if (worst >= 1e-4) std::cerr << "  lerp: " << worst << "\n";
}

LO_TEST(mask_tiles_meet_without_seams) {
    registerRasters();
    // Components that do not read the image are exact in any region, feathered or not.
    lo::LayerMask mask = maskOf("group");
    mask.feather = 4.0;
    lo::masks::MaskRegion whole;
    whole.width = 128;
    whole.height = 96;
    const std::vector<float> all = rendered(mask, whole, nullptr);
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
            const std::vector<float> part = rendered(mask, r, nullptr);
            for (int y = 0; y < r.height; ++y)
                for (int x = 0; x < r.width; ++x)
                    worst = std::max(worst, static_cast<double>(std::fabs(part[static_cast<std::size_t>(y) * r.width + x] -
                                                                          all[static_cast<std::size_t>(y + r.originY) * 128 + x + r.originX])));
        }
    LO_CHECK(worst == 0.0);
    if (worst != 0.0) std::cerr << "  seam: " << worst << "\n";
    // Colour range reads only its own pixel: exact in tiles too.
    const lo::LayerMask& range = maskOf("color_range");
    const std::vector<float> full = rendered(range, whole, scene().data.data());
    lo::masks::MaskRegion r;
    r.originX = 40;
    r.originY = 30;
    r.width = 50;
    r.height = 40;
    r.docWidth = 128;
    r.docHeight = 96;
    std::vector<float> tile(static_cast<std::size_t>(r.width) * r.height * 4);
    for (int y = 0; y < r.height; ++y)
        for (int x = 0; x < r.width * 4; ++x)
            tile[static_cast<std::size_t>(y) * r.width * 4 + x] = scene().data[(static_cast<std::size_t>(y + r.originY) * 128 + r.originX) * 4 + x];
    const std::vector<float> part = rendered(range, r, tile.data());
    worst = 0.0;
    for (int y = 0; y < r.height; ++y)
        for (int x = 0; x < r.width; ++x)
            worst = std::max(worst, static_cast<double>(std::fabs(part[static_cast<std::size_t>(y) * r.width + x] -
                                                                  full[static_cast<std::size_t>(y + r.originY) * 128 + x + r.originX])));
    LO_CHECK(worst == 0.0);
}

LO_TEST(components_round_trip_through_json) {
    for (const lo::Layer& layer : goldenGraph().layers) {
        if (!layer.hasMask) continue;
        const std::string once = lo::serializeMaskJson(layer.mask);
        lo::Graph again;
        std::string error;
        LO_CHECK(lo::parseGraph(R"({"schemaVersion":1,"layers":[{"id":"bg","ops":[]},{"id":"m","ops":[],"type":"adjustment","mask":)" +
                                    once + "}]}",
                                &again, &error));
        LO_CHECK(again.layers.size() == 2 && lo::serializeMaskJson(again.layers[1].mask) == once);
        LO_CHECK(again.layers[1].mask.components.size() == layer.mask.components.size());
    }
    // The shared fixture (Kotlin's LocalMasksTest encodes the same graph to the same text).
    const std::string fixture = readText(std::string(LO_TESTDATA_DIR) + "/graph/edit_graph_local_masks.json");
    const std::string text = fixture.substr(0, fixture.find_last_not_of("\n") + 1);
    lo::Graph g;
    std::string error;
    LO_CHECK(lo::parseGraph(text, &g, &error));
    LO_CHECK(g.layers.size() == 3 && g.layers[2].mask.components.size() == 5 && g.layers[2].mask.components[1].strokes.size() == 2);
    LO_CHECK(g.layers[2].mask.components[1].strokes[0].color.size() == 3 && g.layers[1].mask.components[1].tool == "sky");
    LO_CHECK(lo::serializeGraph(g) == text);
    // Defaults are left out (Kotlin's EncodeDefault NEVER); a raster names its tool.
    lo::LayerMask m;
    lo::MaskComponent c;
    c.kind = "raster";
    c.source = "sky1";
    c.tool = "sky";
    c.width = 32;
    c.height = 24;
    c.docWidth = 128;
    c.docHeight = 96;
    m.components.push_back(c);
    c = lo::MaskComponent();
    c.kind = "linear";
    c.x1 = 10;
    c.mode = "intersect";
    c.invert = true;
    m.components.push_back(c);
    LO_CHECK(lo::serializeMaskJson(m) ==
             R"({"components":[{"kind":"raster","source":"sky1","width":32.0,"height":24.0,"docWidth":128.0,"docHeight":96.0,"tool":"sky"},)"
             R"({"kind":"linear","x0":0.0,"y0":0.0,"x1":10.0,"y1":0.0,"mode":"intersect","invert":true}]})");
}

namespace {

const char* kDetailOps =
    R"([{"op":"highlights_shadows","highlights":-40.0,"shadows":35.0},{"op":"clarity","amount":45.0},)"
    R"({"op":"texture","amount":-30.0},{"op":"dehaze","amount":25.0},{"op":"sharpen","amount":80.0,"radius":1.5},)"
    R"({"op":"noise_reduction","luminance":30.0}])";

lo::Graph localGraph(const std::string& mask) {
    lo::Graph g;
    std::string error;
    const std::string json = R"({"schemaVersion":1,"layers":[{"id":"bg","ops":[]},{"id":"m","ops":)" + std::string(kDetailOps) +
                             R"(,"type":"adjustment")" + (mask.empty() ? "" : R"(,"mask":)" + mask) + "}]}";
    LO_CHECK(lo::parseGraph(json, &g, &error));
    return g;
}

}  // namespace

LO_TEST(local_adjustments_reuse_the_detail_and_light_ops) {
    // Unmasked, a local adjustment of Highlights / Shadows, Clarity, Texture, Dehaze, Sharpening
    // and Noise is exactly the background's ops on the same pixels with the same fields.
    const lo::Graph graph = localGraph("");
    ImageF32 out = scene();
    lo::layers::Region region;
    region.width = out.width;
    region.height = out.height;
    std::string skipped;
    lo::layers::compositeLayers(graph, out.data.data(), region, false, &skipped);
    LO_CHECK(skipped.empty());
    if (!skipped.empty()) std::cerr << "  skipped: " << skipped << "\n";
    std::vector<lo::OpParams> ops = graph.layers[1].ops;
    const auto list = lo::buildLayerOps(ops, nullptr);
    LO_CHECK(list->ops().size() == 6 && !list->isIdentity());
    const lo::GuidedCoefficients coefficients = lo::guidedCoefficients(scene().data.data(), 128, 96);
    const lo::DetailFields fields = lo::computeDetailFields(scene().data.data(), 128, 96);
    lo::OpRegion r;
    r.width = 128;
    r.height = 96;
    r.coefficients = &coefficients;
    r.fields = &fields;
    std::vector<float> want = scene().data;
    list->cpuRefRegion(want.data(), want.data(), r);
    double worst = 0.0, moved = 0.0;
    for (std::size_t q = 0; q < want.size(); q += 4)
        for (int c = 0; c < 3; ++c) {
            // Normal blending at alpha 1 goes through the sRGB curve and back.
            worst = std::max(worst, std::fabs(static_cast<double>(want[q + c]) - out.data[q + c]));
            moved = std::max(moved, std::fabs(static_cast<double>(scene().data[q + c]) - out.data[q + c]));
        }
    LO_CHECK(worst < 1e-5);
    LO_CHECK(moved > 0.05);  // the ops did something
    if (worst >= 1e-5) std::cerr << "  ops: " << worst << "\n";
    // Geometry and the finishing never run in a layer.
    std::vector<lo::OpParams> finishing(1);
    finishing[0].op = "grain";
    std::string named;
    LO_CHECK(lo::buildLayerOps(finishing, &named)->ops().empty() && named == "grain");
}

LO_TEST(export_tiles_with_the_layer_apron_match_the_whole) {
    registerRasters();
    // A feathered auto-mask brush and colour range intersected, with the detail ops: tiles
    // rendered with layerApron() of reach and cropped equal the whole document.
    lo::LayerMask mask;
    mask.components = {component("brush_auto"), component("color_range")};
    mask.components[1].mode = "add";
    mask.feather = 2.0;
    const lo::Graph graph = localGraph(lo::serializeMaskJson(mask));
    const int apron = lo::layers::layerApron(graph);
    LO_CHECK(apron > 0);
    ImageF32 whole = scene();
    lo::layers::Region all;
    all.width = 128;
    all.height = 96;
    lo::layers::compositeLayers(graph, whole.data.data(), all, true, nullptr);  // also keeps the fields
    double worst = 0.0;
    for (int ty = 0; ty < 96; ty += 40) {
        for (int tx = 0; tx < 128; tx += 48) {
            const int w = std::min(48, 128 - tx), h = std::min(40, 96 - ty);
            const int px = std::max(0, tx - apron), py = std::max(0, ty - apron);
            const int pw = std::min(128, tx + w + apron) - px, ph = std::min(96, ty + h + apron) - py;
            std::vector<float> tile(static_cast<std::size_t>(pw) * ph * 4);
            for (int y = 0; y < ph; ++y)
                for (int x = 0; x < pw * 4; ++x)
                    tile[static_cast<std::size_t>(y) * pw * 4 + x] = scene().data[(static_cast<std::size_t>(y + py) * 128 + px) * 4 + x];
            lo::layers::Region r;
            r.width = pw;
            r.height = ph;
            r.originX = px;
            r.originY = py;
            r.docWidth = 128;
            r.docHeight = 96;
            std::string skipped;
            lo::layers::compositeLayers(graph, tile.data(), r, true, &skipped);
            LO_CHECK(skipped.empty());
            for (int y = ty; y < ty + h; ++y)
                for (int x = tx; x < tx + w; ++x)
                    for (int c = 0; c < 3; ++c)
                        worst = std::max(worst, std::fabs(static_cast<double>(tile[(static_cast<std::size_t>(y - py) * pw + (x - px)) * 4 + c]) -
                                                          whole.data[(static_cast<std::size_t>(y) * 128 + x) * 4 + c]));
        }
    }
    LO_CHECK(worst < 2e-3);  // R16F masks
    if (worst >= 2e-3) std::cerr << "  tiles: " << worst << "\n";
}

int main() { return runAllTests(); }
