// The Color basics (Stage 6) against the Python reference (colour-science Bradford CAT,
// OKLab) in testdata/golden/colorops, plus band weights, the eyedropper solve and the
// graph arrays.
#include <cmath>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/color_ops.h"
#include "lineover/graph.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

namespace {

void check(const char* name, int index, const lo::Op& op, const char* input) {
    const ImageF32 in = readNpyF32(goldenPath(input));
    ImageF32 actual = in;
    op.cpuRef(in.data.data(), actual.data.data(), in.data.size() / 4);
    const std::string file = std::string("colorops/") + name + "_" + std::to_string(index) + "_f32.npy";
    LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath(file)), std::string(name) + "_" + std::to_string(index),
                                  kToleranceFloat));
}

constexpr const char* kScene = "input/scene_rec2020_f32.npy";
constexpr const char* kChart = "input/colors_rec2020_f32.npy";

lo::HslBands bands(std::initializer_list<float> v) {
    lo::HslBands b{};
    std::size_t i = 0;
    for (float x : v) b[i++] = x;
    return b;
}

}  // namespace

LO_TEST(white_balance_matches_colour_science_bradford) {
    check("white_balance", 1, *lo::makeWhiteBalance(3000.f, 0.f), kScene);
    check("white_balance", 2, *lo::makeWhiteBalance(9000.f, 40.f), kScene);
    check("white_balance", 3, *lo::makeWhiteBalance(5000.f, -60.f), kScene);
}

LO_TEST(vibrance_matches_python) {
    const float v[3] = {-50.f, 40.f, 100.f};
    for (int i = 0; i < 3; ++i) check("vibrance", i + 1, *lo::makeVibrance(v[i]), kChart);
}

LO_TEST(saturation_matches_python) {
    const float v[3] = {-100.f, -30.f, 60.f};
    for (int i = 0; i < 3; ++i) check("saturation", i + 1, *lo::makeSaturation(v[i]), kChart);
}

LO_TEST(hsl_mixer_matches_python) {
    check("hsl_mixer", 1,
          *lo::makeHslMixer(bands({0, 40, 0, 0, 0, -30, 0, 0}), bands({0, 60, 0, 0, 0, 0, 0, 0}), bands({0, 0, 0, 0, 0, 0, 0, 0})),
          kChart);
    check("hsl_mixer", 2,
          *lo::makeHslMixer(bands({0, 0, 0, 0, 0, 0, 0, 0}), bands({-100, -100, -100, -100, -100, 100, 0, 0}),
                            bands({0, 0, 0, 0, 50, -60, 0, 0})),
          kChart);
    check("hsl_mixer", 3,
          *lo::makeHslMixer(bands({20, -20, 30, -40, 50, -60, 70, -80}), bands({10, 20, 30, 40, -50, -60, -70, -80}),
                            bands({-10, 20, -30, 40, -50, 60, -70, 80})),
          kChart);
}

LO_TEST(invert_posterize_threshold_match_python) {
    check("invert", 1, *lo::makeInvert(), kScene);
    const float levels[3] = {2.f, 5.f, 16.f};
    for (int i = 0; i < 3; ++i) check("posterize", i + 1, *lo::makePosterize(levels[i]), kScene);
    const float t[3] = {0.25f, 0.5f, 0.75f};
    for (int i = 0; i < 3; ++i) check("threshold", i + 1, *lo::makeThreshold(t[i]), kScene);
}

// The HSL band weights are a partition of unity at every hue and peak at their centre.
LO_TEST(hsl_band_weights_sum_to_one) {
    float w[lo::kHslBands];
    double worst = 0.0;
    for (int i = 0; i <= 36000; ++i) {
        lo::colorops::hslWeights(i * 0.01f - 0.005f, w);
        double sum = 0.0;
        for (float x : w) {
            LO_CHECK(x >= 0.f && x <= 1.f);
            sum += x;
        }
        worst = std::max(worst, std::fabs(sum - 1.0));
    }
    LO_CHECK(worst < 1e-6);
    for (int b = 0; b < lo::kHslBands; ++b) {
        lo::colorops::hslWeights(lo::colorops::kHslCentres[b], w);
        LO_CHECK(std::fabs(w[b] - 1.f) < 1e-6f);
    }
    // At most two neighbouring bands are active at any hue.
    lo::colorops::hslWeights(80.f, w);  // between orange (52.8) and yellow (109.8)
    LO_CHECK(w[1] > 0.f && w[2] > 0.f && w[0] == 0.f && w[3] == 0.f);
}

LO_TEST(white_balance_default_is_identity) {
    double m[9];
    lo::colorops::whiteBalanceMatrix(lo::kWbDefaultKelvin, 0.0, m);
    for (int i = 0; i < 9; ++i) LO_CHECK(std::fabs(m[i] - (i % 4 == 0 ? 1.0 : 0.0)) < 1e-9);
    LO_CHECK(lo::makeWhiteBalance(lo::kWbDefaultKelvin, 0.f)->isIdentity());
}

// The eyedropper: the solved (T, tint) makes the sampled colour neutral.
LO_TEST(eyedropper_solves_for_neutral) {
    const float samples[][3] = {{0.30f, 0.25f, 0.18f}, {0.12f, 0.15f, 0.22f}, {0.5f, 0.55f, 0.48f}, {0.2f, 0.18f, 0.21f}};
    for (const auto& rgb : samples) {
        float k, t;
        LO_CHECK(lo::colorops::solveNeutral(rgb, &k, &t));
        LO_CHECK(k >= lo::kWbMinKelvin && k <= lo::kWbMaxKelvin && std::fabs(t) <= lo::kWbTintRange);
        float px[4] = {rgb[0], rgb[1], rgb[2], 1.f};
        lo::makeWhiteBalance(k, t)->cpuRef(px, px, 1);
        const float mean = (px[0] + px[1] + px[2]) / 3.f;
        for (int c = 0; c < 3; ++c) LO_CHECK(std::fabs(px[c] / mean - 1.f) < 2e-3f);
    }
    const float black[3] = {0.f, 0.f, 0.f};
    float k, t;
    LO_CHECK(!lo::colorops::solveNeutral(black, &k, &t));
}

// Grey has no hue: the targeted adjustment and the mixer leave it alone.
LO_TEST(grey_is_untouched_by_hue_tools) {
    float w[lo::kHslBands];
    const float grey[3] = {0.4f, 0.4f, 0.4f};
    lo::colorops::targetedWeights(grey, w);
    for (float x : w) LO_CHECK(std::fabs(x) < 1e-3f);
    float px[4] = {0.4f, 0.4f, 0.4f, 1.f};
    lo::makeHslMixer(bands({100, 100, 100, 100, 100, 100, 100, 100}), bands({100, 100, 100, 100, 100, 100, 100, 100}),
                     bands({100, 100, 100, 100, 100, 100, 100, 100}))
        ->cpuRef(px, px, 1);
    for (int c = 0; c < 3; ++c) LO_CHECK(std::fabs(px[c] - 0.4f) < 2e-3f);
    // An orange pixel is mostly the orange band.
    const float orange[3] = {0.6f, 0.25f, 0.06f};
    lo::colorops::targetedWeights(orange, w);
    LO_CHECK(w[1] > 0.5f);
}

LO_TEST(oklab_round_trips_out_of_gamut_values) {
    const float rgb[3] = {-0.2f, 1.7f, 0.05f};
    float lab[3], back[3];
    lo::colorops::toOklab(rgb, lab);
    lo::colorops::fromOklab(lab, back);
    for (int c = 0; c < 3; ++c) LO_CHECK(std::fabs(back[c] - rgb[c]) < 1e-4f);
}

LO_TEST(graph_round_trips_color_ops_with_arrays) {
    std::ifstream file(std::string(LO_TESTDATA_DIR) + "/graph/color_v1.json");
    std::stringstream buffer;
    buffer << file.rdbuf();
    std::string json = buffer.str();
    while (!json.empty() && (json.back() == '\n' || json.back() == '\r')) json.pop_back();
    lo::Graph graph;
    std::string error;
    LO_CHECK(lo::parseGraph(json, &graph, &error));
    LO_CHECK(lo::serializeGraph(graph) == json);
    std::string skipped;
    const auto ops = lo::buildOps(graph, &skipped);
    LO_CHECK(skipped.empty());
    const lo::OpType expected[7] = {lo::OpType::WhiteBalance, lo::OpType::Vibrance, lo::OpType::Saturation, lo::OpType::HslMixer,
                                    lo::OpType::Invert,       lo::OpType::Posterize, lo::OpType::Threshold};
    LO_CHECK(ops->ops().size() == 7);
    for (int i = 0; i < 7 && i < static_cast<int>(ops->ops().size()); ++i) LO_CHECK(ops->ops()[i]->type() == expected[i]);
}

int main() { return runAllTests(); }
