// Smart selections (Stage 16) against tools/golden/smart_reference.py and OpenCV: the live-wire
// path equals the reference pixel for pixel (and the generator checked the reference against
// cv2's IntelligentScissorsMB), joint bilateral upsampling and the guided-filter matte within
// 1e-3, the max flow equals Edmonds-Karp on random graphs, GrabCut agrees with cv2.grabCut by
// IoU, quick selection finds the object, and a recipe with a model mask, brushes, a magnetic
// lasso and refine edge matches at full and half scale, also when resumed from the cache.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <memory>
#include <queue>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/graph.h"
#include "lineover/masks.h"
#include "lineover/selection.h"
#include "lineover/smart_select.h"
#include "lo_test.h"
#include "npy_io.h"
#include "png_io.h"

using namespace lo::testing;

namespace {

std::string readText(const std::string& path) {
    std::ifstream file(path);
    std::stringstream s;
    s << file.rdbuf();
    return s.str();
}

ImageF32 single(int w, int h, const std::vector<float>& v) {
    ImageF32 out;
    out.width = w;
    out.height = h;
    out.channels = 1;
    out.data = v;
    return out;
}

// The integers of the JSON array after "key" at or after [from].
std::vector<int> intsAfter(const std::string& text, const std::string& key, std::size_t* from) {
    std::vector<int> out;
    std::size_t at = text.find("\"" + key + "\"", *from);
    if (at == std::string::npos) return out;
    const std::size_t open = text.find('[', at), close = text.find(']', open);
    std::stringstream s(text.substr(open + 1, close - open - 1));
    std::string item;
    while (std::getline(s, item, ',')) out.push_back(std::stoi(item));
    *from = close;
    return out;
}

double iou(const std::vector<float>& a, const std::vector<float>& b) {
    std::size_t both = 0, either = 0;
    for (std::size_t p = 0; p < a.size(); ++p) {
        const bool x = a[p] >= 0.5f, y = b[p] >= 0.5f;
        both += x && y ? 1 : 0;
        either += x || y ? 1 : 0;
    }
    return either == 0 ? 1.0 : static_cast<double>(both) / either;
}

std::vector<lo::SelectionOp> opsFrom(const std::string& goldenFile) {
    std::vector<lo::SelectionOp> ops;
    std::string error;
    const bool ok = lo::parseSelection(readText(goldenPath(goldenFile)), &ops, &error);
    LO_CHECK(ok);
    if (!ok) std::cerr << "  " << error << "\n";
    return ops;
}

// Edmonds-Karp on an adjacency matrix: the reference max flow.
double edmondsKarp(std::vector<std::vector<double>> cap, int s, int t) {
    const int n = static_cast<int>(cap.size());
    double flow = 0.0;
    for (;;) {
        std::vector<int> parent(static_cast<std::size_t>(n), -1);
        parent[static_cast<std::size_t>(s)] = s;
        std::queue<int> q;
        q.push(s);
        while (!q.empty() && parent[static_cast<std::size_t>(t)] < 0) {
            const int u = q.front();
            q.pop();
            for (int v = 0; v < n; ++v)
                if (parent[static_cast<std::size_t>(v)] < 0 && cap[static_cast<std::size_t>(u)][static_cast<std::size_t>(v)] > 1e-12) {
                    parent[static_cast<std::size_t>(v)] = u;
                    q.push(v);
                }
        }
        if (parent[static_cast<std::size_t>(t)] < 0) return flow;
        double push = 1e300;
        for (int v = t; v != s; v = parent[static_cast<std::size_t>(v)])
            push = std::min(push, cap[static_cast<std::size_t>(parent[static_cast<std::size_t>(v)])][static_cast<std::size_t>(v)]);
        for (int v = t; v != s; v = parent[static_cast<std::size_t>(v)]) {
            const int u = parent[static_cast<std::size_t>(v)];
            cap[static_cast<std::size_t>(u)][static_cast<std::size_t>(v)] -= push;
            cap[static_cast<std::size_t>(v)][static_cast<std::size_t>(u)] += push;
        }
        flow += push;
    }
}

}  // namespace

LO_TEST(livewire_paths_equal_the_reference) {
    const ImageF32 grey = readNpyF32(goldenPath("smart/livewire_image_f32.npy"));
    const std::string json = readText(goldenPath("smart/livewire_paths.json"));
    lo::smart::LiveWire wire;
    wire.setImage(grey.data.data(), grey.width, grey.height);
    std::size_t at = 0;
    int cases = 0;
    for (;;) {
        const std::vector<int> anchor = intsAfter(json, "anchor", &at);
        if (anchor.size() != 2) break;
        const std::vector<int> target = intsAfter(json, "target", &at);
        const std::vector<int> expected = intsAfter(json, "path", &at);
        wire.setAnchor(anchor[0], anchor[1], 0);
        const std::vector<int> path = wire.pathTo(target[0], target[1]);
        LO_CHECK(path == expected);
        if (path != expected) std::cerr << "  live-wire case " << cases << ": " << path.size() / 2 << " vs " << expected.size() / 2 << " px\n";
        // A window around the anchor large enough for the path gives the same path.
        wire.setAnchor(anchor[0], anchor[1], 64);
        LO_CHECK(wire.pathTo(target[0], target[1]) == expected);
        ++cases;
    }
    LO_CHECK(cases == 4);
}

LO_TEST(joint_bilateral_upsampling_matches_the_reference) {
    const ImageF32 image = readNpyF32(goldenPath("smart/jbu_image_f32.npy"));
    const ImageF32 low = readNpyF32(goldenPath("smart/jbu_low_f32.npy"));
    const std::vector<double> g = lo::smart::guide(image.data.data(), image.width, image.height);
    std::vector<float> up(static_cast<std::size_t>(image.width) * image.height);
    lo::smart::jointBilateralUpsample(low.data.data(), low.width, low.height,
                                      lo::smart::lowGuide(g, image.width, image.height, low.width, low.height), g, image.width,
                                      image.height, up.data());
    LO_CHECK_GOLDEN(compareGolden(single(image.width, image.height, up), readNpyF32(goldenPath("smart/jbu_f32.npy")), "jbu"));
}

LO_TEST(guided_matte_matches_the_reference) {
    const ImageF32 image = readNpyF32(goldenPath("smart/matte_image_f32.npy"));
    const ImageF32 hard = readNpyF32(goldenPath("smart/matte_hard_f32.npy"));
    for (const auto& [r, c] : {std::pair<int, int>{4, 0}, std::pair<int, int>{2, 40}}) {
        std::vector<float> s = hard.data;
        lo::smart::refine(&s, image.data.data(), image.width, image.height, r, c);
        const std::string name = "smart/matte_r" + std::to_string(r) + "_c" + std::to_string(c) + "_f32.npy";
        LO_CHECK_GOLDEN(compareGolden(single(image.width, image.height, s), readNpyF32(goldenPath(name)), name));
    }
}

LO_TEST(max_flow_equals_edmonds_karp) {
    std::uint64_t state = 7;
    auto next = [&]() {
        state = state * 6364136223846793005ull + 1442695040888963407ull;
        return static_cast<double>(state >> 11) / 9007199254740992.0;
    };
    for (int trial = 0; trial < 20; ++trial) {
        const int n = 6 + trial % 9;
        // Matrix with source n and sink n + 1.
        std::vector<std::vector<double>> cap(static_cast<std::size_t>(n + 2), std::vector<double>(static_cast<std::size_t>(n + 2), 0.0));
        lo::smart::GraphCut graph(n);
        for (int v = 0; v < n; ++v) {
            const double s = next() < 0.5 ? std::floor(next() * 10) : 0.0, t = next() < 0.5 ? std::floor(next() * 10) : 0.0;
            graph.addTerminal(v, s, t);
            cap[static_cast<std::size_t>(n)][static_cast<std::size_t>(v)] += s;
            cap[static_cast<std::size_t>(v)][static_cast<std::size_t>(n + 1)] += t;
        }
        for (int a = 0; a < n; ++a)
            for (int b = a + 1; b < n; ++b)
                if (next() < 0.4) {
                    const double w = std::floor(next() * 8), r = std::floor(next() * 8);
                    graph.addEdge(a, b, w, r);
                    cap[static_cast<std::size_t>(a)][static_cast<std::size_t>(b)] += w;
                    cap[static_cast<std::size_t>(b)][static_cast<std::size_t>(a)] += r;
                }
        const double expected = edmondsKarp(cap, n, n + 1);
        const double flow = graph.maxFlow();
        LO_CHECK(std::abs(flow - expected) < 1e-9);
        if (std::abs(flow - expected) >= 1e-9) std::cerr << "  trial " << trial << ": " << flow << " vs " << expected << "\n";
    }
}

LO_TEST(grabcut_agrees_with_opencv) {
    const ImageU8 image = readPng(goldenPath("smart/grabcut_image_rgba8.png"));
    const ImageF32 trimap = readNpyF32(goldenPath("smart/grabcut_trimap_f32.npy"));
    const ImageF32 truth = readNpyF32(goldenPath("smart/grabcut_truth_f32.npy"));
    const ImageF32 cv = readNpyF32(goldenPath("smart/grabcut_cv2_f32.npy"));
    const std::size_t n = static_cast<std::size_t>(image.width) * image.height;
    std::vector<double> rgb(n * 3);
    for (std::size_t p = 0; p < n; ++p)
        for (int c = 0; c < 3; ++c) rgb[3 * p + c] = image.data[4 * p + c];
    std::vector<std::uint8_t> mask(n);
    for (std::size_t p = 0; p < n; ++p) mask[p] = static_cast<std::uint8_t>(std::lround(trimap.data[p]));
    lo::smart::grabCut(rgb, image.width, image.height, &mask, 4);
    std::vector<float> cut(n);
    for (std::size_t p = 0; p < n; ++p) cut[p] = mask[p] == 1 || mask[p] == 3 ? 1.f : 0.f;
    const double vsCv = iou(cut, cv.data), vsTruth = iou(cut, truth.data);
    std::cerr << "  grabcut IoU vs cv2.grabCut " << vsCv << ", vs truth " << vsTruth << "\n";
    LO_CHECK(vsCv >= 0.97);
    LO_CHECK(vsTruth >= 0.97);
}

LO_TEST(quick_selection_finds_the_object) {
    const ImageU8 image = readPng(goldenPath("smart/grabcut_image_rgba8.png"));
    const ImageF32 truth = readNpyF32(goldenPath("smart/grabcut_truth_f32.npy"));
    // The image as working-space pixels: the guide is sqrt(rgb), so square the 8-bit values.
    std::vector<float> rgba(image.data.size());
    for (std::size_t i = 0; i < rgba.size(); ++i) {
        const double v = image.data[i] / 255.0;
        rgba[i] = static_cast<float>(i % 4 == 3 ? 1.0 : v * v);
    }
    lo::selection::Grid grid;
    grid.width = image.width;
    grid.height = image.height;
    grid.rgba = rgba.data();
    lo::SelectionOp op;
    op.kind = "quick";
    op.radius = 3.0;
    op.points = {28.5, 37.5, 61.5, 37.5};  // across the object, as the trimap's stroke
    const std::vector<float> s = lo::selection::quickSelect(op, grid);
    const double score = iou(s, truth.data);
    std::cerr << "  quick selection IoU vs truth " << score << "\n";
    LO_CHECK(score >= 0.95);
}

LO_TEST(smart_recipe_matches_the_reference) {
    const ImageF32 scene = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    const ImageF32 raster = readNpyF32(goldenPath("smart/scene_raster_f32.npy"));
    lo::masks::registerRaster("subject", std::make_shared<std::vector<float>>(raster.data), raster.width, raster.height);
    const std::vector<lo::SelectionOp> ops = opsFrom("smart/scene_ops.json");
    LO_CHECK(ops.size() == 5 && ops[0].kind == "raster" && ops[0].tool == "subject" && ops[4].contrast == 30.0);
    lo::selection::Grid grid;
    grid.width = scene.width;
    grid.height = scene.height;
    grid.rgba = scene.data.data();
    grid.generation = 1;
    const ImageF32 expected = readNpyF32(goldenPath("smart/scene_f32.npy"));
    LO_CHECK_GOLDEN(compareGolden(single(scene.width, scene.height, lo::selection::evaluate(ops, grid)), expected, "smart_scene"));
    // With a cache: a first run fills it, a longer recipe resumes after the refine and an
    // edited brush resumes after the model mask; both equal a run from scratch.
    lo::selection::Cache cache;
    const std::vector<float> first = lo::selection::evaluate(ops, grid, &cache);
    LO_CHECK(cache.checkpoints.size() == 2);
    LO_CHECK(first == lo::selection::evaluate(ops, grid));
    std::vector<lo::SelectionOp> longer = ops;
    lo::SelectionOp grow;
    grow.kind = "grow";
    grow.px = 2.0;
    longer.push_back(grow);
    LO_CHECK(lo::selection::evaluate(longer, grid, &cache) == lo::selection::evaluate(longer, grid));
    std::vector<lo::SelectionOp> edited = ops;
    edited[1].radius = 9.0;
    LO_CHECK(lo::selection::evaluate(edited, grid, &cache) == lo::selection::evaluate(edited, grid));
    // A new image (generation) drops the checkpoints.
    grid.generation = 2;
    lo::selection::evaluate(ops, grid, &cache);
    LO_CHECK(cache.generation == 2 && cache.checkpoints.size() == 2);
    // The preview: half the grid.
    std::vector<float> half;
    for (int y = 0; y < scene.height; y += 2)
        for (int x = 0; x < scene.width; x += 2)
            for (int c = 0; c < 4; ++c) half.push_back(scene.data[(static_cast<std::size_t>(y) * scene.width + x) * 4 + c]);
    lo::selection::Grid small;
    small.width = scene.width / 2;
    small.height = scene.height / 2;
    small.scale = 0.5;
    small.rgba = half.data();
    LO_CHECK_GOLDEN(compareGolden(single(small.width, small.height, lo::selection::evaluate(ops, small)),
                                  readNpyF32(goldenPath("smart/scene_half_f32.npy")), "smart_scene_half"));
    lo::masks::unregisterRaster("subject");
    // Unregistered: the model mask selects nothing (no crash, nothing invented).
    std::vector<lo::SelectionOp> only(ops.begin(), ops.begin() + 1);
    const std::vector<float> none = lo::selection::evaluate(only, grid);
    LO_CHECK(std::all_of(none.begin(), none.end(), [](float v) { return v == 0.f; }));
}

LO_TEST(guided_mask_rasters_upsample_like_the_reference_without_seams) {
    // A saved selection (24 x 18) with its guide planes, exported at 96 x 72: each export region
    // equals joint bilateral upsampling of the whole, whatever the tiling.
    const ImageF32 image = readNpyF32(goldenPath("smart/jbu_image_f32.npy"));
    const ImageF32 low = readNpyF32(goldenPath("smart/jbu_low_f32.npy"));
    const int w = image.width, h = image.height, lw = low.width, lh = low.height;
    const std::vector<double> g = lo::smart::guide(image.data.data(), w, h);
    const std::vector<double> lg = lo::smart::lowGuide(g, w, h, lw, lh);
    auto planes = std::make_shared<std::vector<float>>(static_cast<std::size_t>(lw) * lh * 3);
    for (std::size_t q = 0; q < static_cast<std::size_t>(lw) * lh; ++q)
        for (int c = 0; c < 3; ++c) (*planes)[c * static_cast<std::size_t>(lw) * lh + q] = static_cast<float>(lg[3 * q + c]);
    lo::masks::registerRaster("sel", std::make_shared<std::vector<float>>(low.data), lw, lh);
    lo::masks::registerRaster("sel-guide", planes, lw, 3 * lh);
    std::vector<float> expected(static_cast<std::size_t>(w) * h);
    lo::smart::jointBilateralUpsample(low.data.data(), lw, lh, lg, g, w, h, expected.data());
    lo::LayerMask mask;
    lo::MaskPaint p;
    p.kind = "raster";
    p.source = "sel";
    p.guide = "sel-guide";
    p.width = lw;
    p.height = lh;
    p.docWidth = w;
    p.docHeight = h;
    mask.paint.push_back(p);
    std::vector<float> tiled(expected.size());
    for (const auto& [x0, y0, rw, rh] : {std::array<int, 4>{0, 0, 40, 30}, std::array<int, 4>{40, 0, 56, 30},
                                          std::array<int, 4>{0, 30, 40, 42}, std::array<int, 4>{40, 30, 56, 42}}) {
        std::vector<float> region(static_cast<std::size_t>(rw) * rh * 4);
        for (int j = 0; j < rh; ++j)
            for (int i = 0; i < rw * 4; ++i)
                region[static_cast<std::size_t>(j) * rw * 4 + i] = image.data[(static_cast<std::size_t>(y0 + j) * w + x0) * 4 + i];
        lo::masks::MaskRegion mr;
        mr.originX = x0;
        mr.originY = y0;
        mr.width = rw;
        mr.height = rh;
        mr.docWidth = w;
        mr.docHeight = h;
        lo::masks::MaskTiles tiles;
        lo::masks::render(mask, mr, &tiles, region.data());
        for (int j = 0; j < rh; ++j)
            for (int i = 0; i < rw; ++i) tiled[static_cast<std::size_t>(y0 + j) * w + x0 + i] = tiles.at(i, j);
    }
    LO_CHECK_GOLDEN(compareGolden(single(w, h, tiled), single(w, h, expected), "guided_raster_tiles"));
    LO_CHECK_GOLDEN(compareGolden(single(w, h, tiled), readNpyF32(goldenPath("smart/jbu_f32.npy")), "guided_raster_vs_reference"));
    // Without the guide image (the canvas) the raster stays bilinear, and a feathered mask too.
    lo::masks::MaskRegion whole;
    whole.width = w;
    whole.height = h;
    whole.docWidth = w;
    whole.docHeight = h;
    lo::masks::MaskTiles plain, guided, feathered;
    lo::masks::render(mask, whole, &plain);
    lo::masks::render(mask, whole, &guided, image.data.data());
    mask.feather = 1.0;
    lo::masks::render(mask, whole, &feathered, image.data.data());
    double plainDiff = 0.0;
    for (int j = 0; j < h; ++j)
        for (int i = 0; i < w; ++i) plainDiff = std::max(plainDiff, static_cast<double>(std::abs(plain.at(i, j) - guided.at(i, j))));
    LO_CHECK(plainDiff > 0.1);  // the guide changes the edges
    lo::masks::MaskTiles plainFeathered;
    lo::masks::render(mask, whole, &plainFeathered);
    bool same = true;
    for (int j = 0; j < h; ++j)
        for (int i = 0; i < w; ++i) same = same && feathered.at(i, j) == plainFeathered.at(i, j);
    LO_CHECK(same);
    lo::masks::unregisterRaster("sel");
    lo::masks::unregisterRaster("sel-guide");
}

LO_TEST(lightness_is_oklab_l) {
    const float rgba[8] = {0.f, 0.f, 0.f, 1.f, 1.f, 1.f, 1.f, 1.f};
    const std::vector<float> l = lo::selection::lightness(rgba, 2, 1);
    LO_CHECK(std::abs(l[0]) < 1e-6 && std::abs(l[1] - 1.f) < 1e-3);
}

int main() { return runAllTests(); }
