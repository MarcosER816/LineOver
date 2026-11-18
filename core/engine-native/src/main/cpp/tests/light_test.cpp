// The Light ops (Stage 5) against the Python reference in testdata/golden/light, at three
// settings each, plus the guided-filter tiling, the graph mapping and Auto.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/color.h"
#include "lineover/graph.h"
#include "lineover/light.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

namespace {

ImageF32 scene() { return readNpyF32(goldenPath("input/scene_rec2020_f32.npy")); }

void checkOp(const char* name, int index, const lo::Op& op, bool region) {
    const ImageF32 input = scene();
    ImageF32 actual = input;
    if (region) {
        const lo::GuidedCoefficients c = lo::guidedCoefficients(input.data.data(), input.width, input.height);
        lo::OpRegion r;
        r.width = input.width;
        r.height = input.height;
        r.coefficients = &c;
        op.cpuRefRegion(input.data.data(), actual.data.data(), r);
    } else {
        op.cpuRef(input.data.data(), actual.data.data(), input.data.size() / 4);
    }
    const std::string file = std::string("light/") + name + "_" + std::to_string(index) + "_f32.npy";
    LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath(file)), std::string(name) + "_" + std::to_string(index),
                                  kToleranceFloat));
}

}  // namespace

LO_TEST(exposure_matches_python_at_three_settings) {
    const float ev[3] = {-2.5f, 1.0f, 3.7f};
    for (int i = 0; i < 3; ++i) checkOp("exposure", i + 1, *lo::makeExposure(ev[i]), false);
}

LO_TEST(contrast_matches_python_at_three_settings) {
    const float v[3] = {-60.f, 40.f, 100.f};
    for (int i = 0; i < 3; ++i) checkOp("contrast", i + 1, *lo::makeContrast(v[i]), false);
}

LO_TEST(brightness_matches_python_at_three_settings) {
    const float v[3] = {-70.f, 30.f, 100.f};
    for (int i = 0; i < 3; ++i) checkOp("brightness", i + 1, *lo::makeBrightness(v[i]), false);
}

LO_TEST(whites_matches_python_at_three_settings) {
    const float v[3] = {-100.f, 50.f, 80.f};
    for (int i = 0; i < 3; ++i) checkOp("whites", i + 1, *lo::makeWhites(v[i]), false);
}

LO_TEST(blacks_matches_python_at_three_settings) {
    const float v[3] = {-100.f, -30.f, 70.f};
    for (int i = 0; i < 3; ++i) checkOp("blacks", i + 1, *lo::makeBlacks(v[i]), false);
}

LO_TEST(highlights_shadows_matches_python_at_three_settings) {
    const float h[3] = {-100.f, 0.f, -60.f};
    const float s[3] = {0.f, 100.f, 60.f};
    for (int i = 0; i < 3; ++i) checkOp("highlights_shadows", i + 1, *lo::makeHighlightsShadows(h[i], s[i]), true);
}

// Tiles sample the preview's coefficients at their own uv: four quadrant tiles at twice
// the resolution must agree with the coefficients' own resolution where they coincide,
// and a tile run must equal the whole-image run for the same pixels.
LO_TEST(highlights_shadows_tiles_agree_with_the_whole_image) {
    const ImageF32 input = scene();
    const int w = input.width, h = input.height;
    const lo::GuidedCoefficients c = lo::guidedCoefficients(input.data.data(), w, h);
    const auto op = lo::makeHighlightsShadows(-80.f, 70.f);
    std::vector<float> whole(input.data.size());
    lo::OpRegion all;
    all.width = w;
    all.height = h;
    all.coefficients = &c;
    op->cpuRefRegion(input.data.data(), whole.data(), all);
    double worst = 0.0;
    for (int ty = 0; ty < 2; ++ty) {
        for (int tx = 0; tx < 2; ++tx) {
            const int tw = w / 2, th = h / 2;
            std::vector<float> tile(static_cast<std::size_t>(tw * th * 4)), out(tile.size());
            for (int y = 0; y < th; ++y)
                for (int x = 0; x < tw; ++x)
                    for (int k = 0; k < 4; ++k)
                        tile[(y * tw + x) * 4 + k] = input.data[((ty * th + y) * w + tx * tw + x) * 4 + k];
            lo::OpRegion r;
            r.width = tw;
            r.height = th;
            r.rect[0] = tx * 0.5f;
            r.rect[1] = ty * 0.5f;
            r.rect[2] = 0.5f;
            r.rect[3] = 0.5f;
            r.coefficients = &c;
            op->cpuRefRegion(tile.data(), out.data(), r);
            for (int y = 0; y < th; ++y)
                for (int x = 0; x < tw; ++x)
                    for (int k = 0; k < 4; ++k)
                        worst = std::max(worst, std::fabs(double(out[(y * tw + x) * 4 + k]) -
                                                          whole[((ty * th + y) * w + tx * tw + x) * 4 + k]));
        }
    }
    LO_CHECK(worst < 1e-4);
}

LO_TEST(guided_radius_is_one_and_a_half_percent_of_the_long_edge) {
    LO_CHECK(lo::guidedRadius(128, 96) == 2);
    LO_CHECK(lo::guidedRadius(2048, 1536) == 31);
    LO_CHECK(lo::guidedRadius(10, 10) == 1);
}

// A flat image has zero variance: a = 0, so the base is the mean (= L) everywhere.
LO_TEST(guided_base_of_a_flat_image_is_its_log_luminance) {
    std::vector<float> flat(16 * 8 * 4, 0.25f);
    const lo::GuidedCoefficients c = lo::guidedCoefficients(flat.data(), 16, 8);
    const float l = std::log2(0.25f);
    for (std::size_t i = 0; i < c.ab.size(); i += 2) LO_CHECK(std::fabs(c.ab[i]) < 1e-6f && std::fabs(c.ab[i + 1] - l) < 1e-5f);
}

LO_TEST(zero_amounts_are_identity) {
    LO_CHECK(lo::makeExposure(0.f)->isIdentity());
    LO_CHECK(lo::makeContrast(0.f)->isIdentity());
    LO_CHECK(lo::makeBrightness(0.f)->isIdentity());
    LO_CHECK(lo::makeWhites(0.f)->isIdentity());
    LO_CHECK(lo::makeBlacks(0.f)->isIdentity());
    LO_CHECK(lo::makeHighlightsShadows(0.f, 0.f)->isIdentity());
    LO_CHECK(!lo::makeHighlightsShadows(0.f, 1.f)->isIdentity());
}

LO_TEST(graph_builds_every_light_op_in_order) {
    // Shared with Kotlin's LightAdjustmentsTest (byte for byte).
    std::ifstream file(std::string(LO_TESTDATA_DIR) + "/graph/light_v1.json");
    std::stringstream buffer;
    buffer << file.rdbuf();
    std::string json = buffer.str();
    while (!json.empty() && (json.back() == '\n' || json.back() == '\r')) json.pop_back();
    lo::Graph graph;
    std::string error;
    LO_CHECK(lo::parseGraph(json, &graph, &error));
    std::string skipped;
    const auto ops = lo::buildOps(graph, &skipped);
    LO_CHECK(skipped.empty());
    LO_CHECK(lo::serializeGraph(graph) == json);
    const lo::OpType expected[6] = {lo::OpType::HighlightsShadows, lo::OpType::Exposure, lo::OpType::Whites,
                                    lo::OpType::Blacks, lo::OpType::Contrast, lo::OpType::Brightness};
    LO_CHECK(ops->ops().size() == 6);
    for (int i = 0; i < 6; ++i) LO_CHECK(ops->ops()[i]->type() == expected[i]);
}

LO_TEST(gpu_bodies_declare_their_uniforms) {
    struct Recorder final : lo::GpuOpContext {
        std::string declarations, body;
        std::vector<std::string> names;
        void setShader(const char* d, const char* b) override {
            declarations = d;
            body = b;
        }
        void setUniform1f(const char* name, float) override { names.emplace_back(name); }
    };
    const std::unique_ptr<lo::Op> ops[] = {lo::makeExposure(1.f),  lo::makeContrast(10.f), lo::makeBrightness(10.f),
                                           lo::makeWhites(10.f),   lo::makeBlacks(10.f),   lo::makeHighlightsShadows(10.f, 10.f)};
    for (const auto& op : ops) {
        Recorder r;
        op->gpu(r);
        LO_CHECK(!r.names.empty());
        for (const std::string& name : r.names) LO_CHECK(r.declarations.find("uniform float " + name + ";") != std::string::npos);
        LO_CHECK(r.body.find("return") != std::string::npos);
    }
}

LO_TEST(percentiles_and_auto_stretch) {
    // Luminance i / 999 for i = 0..999 (grey pixels: Y equals the channel value).
    std::vector<float> ramp(1000 * 4);
    for (int i = 0; i < 1000; ++i) {
        const float v = static_cast<float>(i) / 999.f;
        ramp[4 * i] = ramp[4 * i + 1] = ramp[4 * i + 2] = v;
        ramp[4 * i + 3] = 1.f;
    }
    float low, high;
    lo::luminancePercentiles(ramp.data(), 1000, 0.005f, 0.995f, &low, &high);
    LO_CHECK(std::fabs(low - 5.f / 999.f) < 1e-5f);
    LO_CHECK(std::fabs(high - 994.f / 999.f) < 1e-5f);
    // A dim image (high 0.25): +2 stops; the low end then lands on the black point.
    const lo::AutoLight a = lo::autoLight(0.01f, 0.25f);
    LO_CHECK(std::fabs(a.exposure - 2.f) < 1e-5f);
    std::vector<float> px = {0.01f, 0.01f, 0.01f, 1.f};
    lo::makeExposure(a.exposure)->cpuRef(px.data(), px.data(), 1);
    lo::makeBlacks(a.blacks)->cpuRef(px.data(), px.data(), 1);
    LO_CHECK(a.blacks >= -100.f && a.blacks <= 100.f);
    // 0.04 after exposure; the blacks curve only reaches -100 (1 stop at the black point)
    // so it moves as far as the clamp allows, darker than before.
    LO_CHECK(px[0] < 0.04f);
    // An image already spanning the range needs nothing.
    const lo::AutoLight none = lo::autoLight(0.002f, 1.0f);
    LO_CHECK(std::fabs(none.exposure) < 1e-6f && std::fabs(none.blacks) < 1e-3f);
    // Exposure is clamped to +-5.
    LO_CHECK(lo::autoLight(0.f, 1e-5f).exposure == 5.f);
}

// The CPU histogram (reference of the compute shader, and its fallback) against numpy, on
// the scene rounded to half floats as the preview texture holds it.
LO_TEST(histogram_matches_numpy_within_one_count_per_bin) {
    ImageF32 input = scene();
    for (float& v : input.data) v = lo::color::halfToFloat(lo::color::floatToHalf(v));
    std::vector<std::uint32_t> bins(1024);
    lo::histogramSrgb(input.data.data(), input.data.size() / 4, bins.data());
    const ImageF32 expected = readNpyF32(goldenPath("histogram/scene_srgb_hist_f32.npy"));  // (1, 256, 4)
    LO_CHECK(expected.width == 256 && expected.channels == 4);
    long worst = 0;
    for (int c = 0; c < 4; ++c) {
        long total = 0;
        for (int i = 0; i < 256; ++i) {
            const long want = std::lround(expected.data[static_cast<std::size_t>(i) * 4 + c]);
            worst = std::max(worst, std::labs(static_cast<long>(bins[c * 256 + i]) - want));
            total += bins[c * 256 + i];
        }
        LO_CHECK(total == static_cast<long>(input.width) * input.height);
    }
    std::printf("histogram vs numpy: max |diff| %ld counts\n", worst);
    LO_CHECK(worst <= 1);
}

int main() { return runAllTests(); }
