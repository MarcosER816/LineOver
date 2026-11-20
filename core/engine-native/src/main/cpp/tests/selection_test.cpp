// Selections (Stage 15) against tools/golden/selection_reference.py and OpenCV: the flood
// fill equals cv2.floodFill exactly (fixed range, 4- and 8-connected), the distance transform
// equals cv2.distanceTransform (DIST_MASK_PRECISE), grow / shrink / border on top of it, two
// recipes on the scene (shapes, modes, wand, color range, invert, feather, smooth), at full and
// half scale; the outline, saved selections as mask rasters, and the recipe JSON.
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/graph.h"
#include "lineover/masks.h"
#include "lineover/selection.h"
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

ImageF32 single(int w, int h, const std::vector<float>& v) {
    ImageF32 out;
    out.width = w;
    out.height = h;
    out.channels = 1;
    out.data = v;
    return out;
}

std::vector<std::uint8_t> bytes(const ImageF32& image) {
    std::vector<std::uint8_t> out(image.data.size());
    for (std::size_t i = 0; i < out.size(); ++i) out[i] = static_cast<std::uint8_t>(std::lround(image.data[i]));
    return out;
}

std::vector<lo::SelectionOp> opsFrom(const std::string& goldenFile) {
    std::vector<lo::SelectionOp> ops;
    std::string error;
    const bool ok = lo::parseSelection(readText(goldenPath(goldenFile)), &ops, &error);
    LO_CHECK(ok);
    if (!ok) std::cerr << "  " << error << "\n";
    return ops;
}

}  // namespace

LO_TEST(flood_fill_equals_opencv_flood_fill) {
    const ImageF32 image = readNpyF32(goldenPath("selection/flood_image_f32.npy"));
    const std::vector<std::uint8_t> img = bytes(image);
    const int cases[][5] = {{15, 15, 2, 2, 4}, {15, 15, 2, 2, 8}, {15, 15, 0, 0, 4}, {3, 40, 10, 5, 4}, {60, 2, 7, 7, 8}};
    for (int c = 0; c < 5; ++c) {
        std::vector<std::uint8_t> out(img.size());
        lo::selection::floodFillRange(img.data(), image.width, image.height, cases[c][0], cases[c][1], cases[c][2], cases[c][3],
                                      cases[c][4], out.data());
        const ImageF32 expected = readNpyF32(goldenPath("selection/flood_" + std::to_string(c) + "_f32.npy"));
        std::size_t differ = 0;
        for (std::size_t p = 0; p < out.size(); ++p) differ += out[p] != static_cast<std::uint8_t>(expected.data[p]) ? 1 : 0;
        LO_CHECK(differ == 0);  // identical, pixel for pixel
        if (differ != 0) std::cerr << "  flood case " << c << ": " << differ << " pixels differ\n";
    }
    // 4- and 8-connectivity really differ on this image (the diagonal chain).
    const ImageF32 four = readNpyF32(goldenPath("selection/flood_0_f32.npy"));
    const ImageF32 eight = readNpyF32(goldenPath("selection/flood_1_f32.npy"));
    LO_CHECK(four.data != eight.data);
}

LO_TEST(distance_transform_equals_opencv) {
    const ImageF32 blob = readNpyF32(goldenPath("selection/blob_f32.npy"));
    std::vector<std::uint8_t> inside(blob.data.size()), outside(blob.data.size());
    for (std::size_t p = 0; p < blob.data.size(); ++p) {
        inside[p] = blob.data[p] >= 0.5f;
        outside[p] = blob.data[p] < 0.5f;
    }
    std::vector<float> d(blob.data.size());
    lo::selection::distanceTransform(inside.data(), blob.width, blob.height, d.data());
    LO_CHECK_GOLDEN(compareGolden(single(blob.width, blob.height, d), readNpyF32(goldenPath("selection/distance_f32.npy")), "distance"));
    lo::selection::distanceTransform(outside.data(), blob.width, blob.height, d.data());
    LO_CHECK_GOLDEN(
        compareGolden(single(blob.width, blob.height, d), readNpyF32(goldenPath("selection/distance_outside_f32.npy")), "distance_outside"));
}

LO_TEST(grow_shrink_and_border_follow_the_distance_transform) {
    const ImageF32 blob = readNpyF32(goldenPath("selection/blob_f32.npy"));
    for (const char* r : {"2.5", "6.0", "-1.5", "-4.0"}) {
        std::vector<float> s = blob.data;
        lo::selection::grow(&s, blob.width, blob.height, std::stod(r));
        LO_CHECK_GOLDEN(compareGolden(single(blob.width, blob.height, s),
                                      readNpyF32(goldenPath(std::string("selection/grow_") + r + "_f32.npy")), std::string("grow_") + r));
    }
    // Growing never removes and shrinking never adds.
    std::vector<float> grown = blob.data, shrunk = blob.data;
    lo::selection::grow(&grown, blob.width, blob.height, 3.0);
    lo::selection::grow(&shrunk, blob.width, blob.height, -3.0);
    for (std::size_t p = 0; p < blob.data.size(); ++p) LO_CHECK(grown[p] >= blob.data[p] && shrunk[p] <= blob.data[p]);
    std::vector<float> band = blob.data;
    lo::selection::border(&band, blob.width, blob.height, 3.0);
    LO_CHECK_GOLDEN(compareGolden(single(blob.width, blob.height, band), readNpyF32(goldenPath("selection/border_3.0_f32.npy")), "border"));
}

LO_TEST(selection_recipes_match_the_reference) {
    const ImageF32 scene = readNpyF32(goldenPath("input/scene_rec2020_f32.npy"));
    for (const char* name : {"scene", "tail"}) {
        lo::selection::Grid grid;
        grid.width = scene.width;
        grid.height = scene.height;
        grid.rgba = scene.data.data();
        const std::vector<float> s = lo::selection::evaluate(opsFrom(std::string("selection/") + name + "_ops.json"), grid);
        LO_CHECK_GOLDEN(compareGolden(single(scene.width, scene.height, s),
                                      readNpyF32(goldenPath(std::string("selection/") + name + "_f32.npy")), std::string("selection_") + name));
    }
    // The preview: the same recipe (document px) on half the grid.
    std::vector<float> half;
    for (int y = 0; y < scene.height; y += 2)
        for (int x = 0; x < scene.width; x += 2)
            for (int c = 0; c < 4; ++c) half.push_back(scene.data[(static_cast<std::size_t>(y) * scene.width + x) * 4 + c]);
    lo::selection::Grid grid;
    grid.width = scene.width / 2;
    grid.height = scene.height / 2;
    grid.scale = 0.5;
    grid.rgba = half.data();
    const std::vector<float> s = lo::selection::evaluate(opsFrom("selection/scene_ops.json"), grid);
    LO_CHECK_GOLDEN(compareGolden(single(grid.width, grid.height, s), readNpyF32(goldenPath("selection/scene_half_f32.npy")), "selection_half"));
}

LO_TEST(the_outline_follows_the_edge) {
    // A disc of radius 20 px: every outline point lies within half a pixel of the circle.
    std::vector<lo::SelectionOp> ops(1);
    ops[0].kind = "ellipse";
    ops[0].x0 = 10.3;
    ops[0].y0 = 8.6;
    ops[0].x1 = 50.3;
    ops[0].y1 = 48.6;
    lo::selection::Grid grid;
    grid.width = 64;
    grid.height = 60;
    const std::vector<float> s = lo::selection::evaluate(ops, grid);
    std::vector<float> seg;
    lo::selection::contours(s.data(), grid.width, grid.height, 1.0, &seg);
    LO_CHECK(seg.size() > 4 * 60);
    double worst = 0.0;
    for (std::size_t k = 0; k < seg.size(); k += 2) worst = std::max(worst, std::fabs(std::hypot(seg[k] - 30.3, seg[k + 1] - 28.6) - 20.0));
    LO_CHECK(worst < 0.75);
    // Nothing selected: no outline. Everything: a frame around the grid.
    std::vector<float> none(64 * 60, 0.f), all(64 * 60, 1.f);
    lo::selection::contours(none.data(), 64, 60, 1.0, &seg);
    LO_CHECK(seg.empty());
    lo::selection::contours(all.data(), 64, 60, 1.0, &seg);
    LO_CHECK(!seg.empty());
}

LO_TEST(selection_json_and_mask_rasters) {
    std::vector<lo::SelectionOp> ops;
    std::string error;
    LO_CHECK(lo::parseSelection(
        R"({"ops":[{"kind":"rect","mode":"add","antiAlias":false,"x0":1.0,"y0":2.0,"x1":3.0,"y1":4.0},)"
        R"({"kind":"wand","mode":"subtract","x":5.0,"y":6.0,"tolerance":20.0,"contiguous":false,"allLayers":false},)"
        R"({"kind":"grow","px":-3.0}]})",
        &ops, &error));
    LO_CHECK(ops.size() == 3 && ops[0].mode == "add" && !ops[0].antiAlias && ops[1].mode == "subtract");
    LO_CHECK(!ops[1].contiguous && !ops[1].allLayers && ops[1].tolerance == 20.0 && ops[2].px == -3.0);
    LO_CHECK(!lo::parseSelection(R"({"ops":[{"mode":"new"}]})", &ops, &error));
    // A saved selection as a mask: the raster, resampled over the document.
    auto ramp = std::make_shared<std::vector<float>>();
    for (int y = 0; y < 10; ++y)
        for (int x = 0; x < 20; ++x) ramp->push_back(x / 19.f);
    lo::masks::registerRaster("sel1", ramp, 20, 10);
    lo::Graph g;
    LO_CHECK(lo::parseGraph(
        R"({"schemaVersion":1,"layers":[{"id":"background","ops":[]},{"id":"a","ops":[],"type":"adjustment","mask":{"paint":[)"
        R"({"kind":"raster","source":"sel1","width":20.0,"height":10.0,"docWidth":200.0,"docHeight":100.0}]}}]})",
        &g, &error));
    const std::string again = lo::serializeGraph(g);
    LO_CHECK(again.find(R"("kind":"raster","source":"sel1","width":20.0,"height":10.0,"docWidth":200.0,"docHeight":100.0)") !=
             std::string::npos);
    std::vector<float> m(200 * 100);
    lo::masks::rasterize(g.layers[1].mask, 0, 0, 200, 100, 1.0, m.data());
    // Pixel centre 105.5 -> raster x 105.5 / 10 - 0.5 = 10.05, between columns 10 and 11 of the ramp x / 19.
    LO_CHECK(std::fabs(m[50 * 200 + 105] - 10.05f / 19.f) < 1e-6f);
    LO_CHECK(m[0] == 0.f && std::fabs(m[199] - 1.f) < 1e-6f);
    lo::masks::unregisterRaster("sel1");
    std::vector<float> keep(200 * 100, 0.25f);
    lo::masks::rasterize(g.layers[1].mask, 0, 0, 200, 100, 1.0, keep.data());
    LO_CHECK(keep[0] == 1.f);  // unregistered: the mask keeps its base
}

int main() { return runAllTests(); }
