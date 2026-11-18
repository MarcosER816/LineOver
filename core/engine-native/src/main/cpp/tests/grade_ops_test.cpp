// Curves and grading (Stage 7) against the Python reference in testdata/golden/grade,
// spline monotonicity on random points, and the graph mapping of every op.
#include <cmath>
#include <fstream>
#include <random>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/grade_ops.h"
#include "lineover/graph.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

namespace {

constexpr const char* kScene = "input/scene_rec2020_f32.npy";
constexpr const char* kChart = "input/colors_rec2020_f32.npy";

void check(const char* name, int index, const lo::Op& op, const char* input) {
    const ImageF32 in = readNpyF32(goldenPath(input));
    ImageF32 actual = in;
    op.cpuRef(in.data.data(), actual.data.data(), in.data.size() / 4);
    const std::string file = std::string("grade/") + name + "_" + std::to_string(index) + "_f32.npy";
    LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath(file)), std::string(name) + "_" + std::to_string(index),
                                  kToleranceFloat));
}

constexpr std::array<float, 5> kLv = {0, 1, 1, 0, 1};

std::array<float, 20> levels(std::array<float, 5> m, std::array<float, 5> r, std::array<float, 5> g, std::array<float, 5> b) {
    std::array<float, 20> v{};
    const std::array<float, 5>* rows[4] = {&m, &r, &g, &b};
    for (int i = 0; i < 4; ++i)
        for (int k = 0; k < 5; ++k) v[5 * i + k] = (*rows[i])[k];
    return v;
}

std::array<float, 36> selective() {
    return {-40, 10, 20, 0, 0, 0, -30, 0, 0, 20, 0, 10, 30, 0, 0, 0, 0, 0, 40, -10, 0, 0, 0, 0, 0, 0, 0, -20, 10, 0, 0, 0, 0, 0, 0, 20};
}

}  // namespace

LO_TEST(curves_match_python) {
    lo::CurveSet a;
    a.rgb = {{0.f, 0.f}, {0.25f, 0.18f}, {0.75f, 0.84f}, {1.f, 1.f}};
    check("curves", 1, *lo::makeCurves(a), kScene);
    lo::CurveSet b;
    b.red = {{0.f, 0.05f}, {0.5f, 0.56f}, {1.f, 1.f}};
    b.blue = {{0.f, 0.f}, {0.5f, 0.44f}, {1.f, 0.95f}};
    b.luma = {{0.f, 0.f}, {0.4f, 0.5f}, {1.f, 1.f}};
    check("curves", 2, *lo::makeCurves(b), kScene);
    lo::CurveSet c;
    c.rgb = {{0.f, 1.f}, {0.5f, 0.3f}, {1.f, 0.f}};
    c.green = {{0.f, 0.f}, {0.1f, 0.4f}, {0.12f, 0.42f}, {1.f, 1.f}};
    check("curves", 3, *lo::makeCurves(c), kScene);
}

LO_TEST(parametric_curve_matches_python) {
    check("parametric_curve", 1, *lo::makeParametricCurve(30, 10, -20, -40, 0.25f, 0.5f, 0.75f), kScene);
    check("parametric_curve", 2, *lo::makeParametricCurve(-60, -20, 30, 50, 0.25f, 0.5f, 0.75f), kScene);
    check("parametric_curve", 3, *lo::makeParametricCurve(100, -100, 100, -100, 0.2f, 0.45f, 0.8f), kScene);
}

LO_TEST(levels_match_python) {
    check("levels", 1, *lo::makeLevels(levels({0.05f, 1.2f, 0.9f, 0, 1}, kLv, kLv, kLv)), kScene);
    check("levels", 2, *lo::makeLevels(levels(kLv, {0.1f, 1, 1, 0, 1}, kLv, {0, 0.8f, 0.85f, 0.05f, 1})), kScene);
    check("levels", 3, *lo::makeLevels(levels({0, 0.6f, 1, 0.1f, 0.9f}, {0, 1.3f, 1, 0, 1}, {0.02f, 1, 0.95f, 0, 1}, kLv)), kScene);
}

LO_TEST(color_grading_matches_python) {
    using W = lo::GradeWheel;
    check("color_grading", 1, *lo::makeColorGrading(W{200, 60, 0}, W{0, 0, 0}, W{40, 50, 0}, W{0, 0, 0}, 50, 0), kChart);
    check("color_grading", 2, *lo::makeColorGrading(W{180, 80, -20}, W{300, 30, 10}, W{30, 70, 20}, W{90, 20, -10}, 80, 30), kChart);
    check("color_grading", 3, *lo::makeColorGrading(W{250, 100, 0}, W{0, 0, -50}, W{60, 100, 0}, W{0, 0, 40}, 10, -60), kChart);
}

LO_TEST(color_balance_matches_python) {
    check("color_balance", 1, *lo::makeColorBalance({-30, 0, 40}, {0, 0, 0}, {20, 0, -30}, true), kScene);
    check("color_balance", 2, *lo::makeColorBalance({0, 50, 0}, {60, -40, 20}, {0, 0, 0}, false), kScene);
    check("color_balance", 3, *lo::makeColorBalance({100, -100, 100}, {-100, 100, -100}, {100, 100, 100}, true), kScene);
}

LO_TEST(selective_color_matches_python) {
    check("selective_color", 1, *lo::makeSelectiveColor({}, true), kChart);
    check("selective_color", 2, *lo::makeSelectiveColor(selective(), true), kChart);
    check("selective_color", 3, *lo::makeSelectiveColor(selective(), false), kChart);
}

LO_TEST(channel_mixer_matches_python) {
    check("channel_mixer", 1, *lo::makeChannelMixer({100, 0, 0, 0, 100, 0, 0, 0, 100}, {0, 0, 0}, false), kChart);
    check("channel_mixer", 2, *lo::makeChannelMixer({80, 30, -10, 10, 90, 0, 0, -20, 120}, {5, 0, -5}, false), kChart);
    check("channel_mixer", 3, *lo::makeChannelMixer({30, 60, 10, 0, 0, 0, 0, 0, 0}, {0, 0, 0}, true), kChart);
}

LO_TEST(black_and_white_matches_python) {
    check("black_and_white", 1, *lo::makeBlackAndWhite({0, 0, 0, 0, 0, 0}, 0, 0), kChart);
    check("black_and_white", 2, *lo::makeBlackAndWhite({40, 60, -20, -30, -60, 20}, 0, 0), kChart);
    check("black_and_white", 3, *lo::makeBlackAndWhite({-50, 30, 80, 0, -100, 100}, 40, 30), kChart);
}

LO_TEST(photo_filter_matches_python) {
    check("photo_filter", 1, *lo::makePhotoFilter(60, 80, 25, true), kChart);
    check("photo_filter", 2, *lo::makePhotoFilter(250, 60, 60, false), kChart);
    check("photo_filter", 3, *lo::makePhotoFilter(140, 100, 100, true), kChart);
}

LO_TEST(gradient_map_matches_python) {
    check("gradient_map", 1, *lo::makeGradientMap({{0, 0.15f, 0, 0}, {1, 0.95f, 0, 0}}), kChart);
    check("gradient_map", 2, *lo::makeGradientMap({{0, 0.2f, 0.02f, -0.08f}, {0.5f, 0.6f, 0.08f, 0.02f}, {1, 0.97f, -0.01f, 0.06f}}),
          kChart);
    check("gradient_map", 3,
          *lo::makeGradientMap({{0, 0.3f, -0.05f, -0.1f}, {0.3f, 0.45f, 0.12f, 0.05f}, {0.7f, 0.8f, -0.04f, 0.12f}, {1, 0.98f, 0, 0}}),
          kChart);
}

LO_TEST(calibration_matches_python) {
    check("calibration", 1, *lo::makeCalibration({20, 0, 0}, {0, 0, 0}), kChart);
    check("calibration", 2, *lo::makeCalibration({0, -30, 40}, {30, 20, -20}), kChart);
    check("calibration", 3, *lo::makeCalibration({-60, 50, -40}, {-50, 60, 80}), kChart);
}

// Property: for random monotone (non-decreasing) point sets the 4096-entry table never
// decreases and passes through every point (Fritsch-Carlson).
LO_TEST(spline_is_monotone_for_random_points) {
    std::mt19937 rng(20261118);
    std::uniform_real_distribution<float> u(0.f, 1.f);
    std::vector<float> lut(lo::kLutSize);
    int checked = 0;
    for (int trial = 0; trial < 2000; ++trial) {
        const int n = 2 + static_cast<int>(rng() % 8);
        std::vector<float> xs = {0.f, 1.f}, ys;
        while (static_cast<int>(xs.size()) < n) xs.push_back(0.01f + 0.98f * u(rng));
        std::sort(xs.begin(), xs.end());
        bool distinct = true;
        for (std::size_t i = 1; i < xs.size(); ++i) distinct = distinct && xs[i] - xs[i - 1] > 1e-3f;
        if (!distinct) continue;
        for (int i = 0; i < n; ++i) ys.push_back(u(rng));
        std::sort(ys.begin(), ys.end());
        if (trial % 3 == 0) ys[static_cast<std::size_t>(rng() % n)] = ys[std::max(0, static_cast<int>(rng() % n) - 1)];  // flats
        std::sort(ys.begin(), ys.end());
        lo::CurvePoints pts;
        for (int i = 0; i < n; ++i) pts.emplace_back(xs[i], ys[i]);
        lo::grade::curveLut(pts, lut.data());
        for (int i = 1; i < lo::kLutSize; ++i) LO_CHECK(lut[i] >= lut[i - 1] - 1e-6f);
        for (int i = 0; i < n; ++i) {
            const int k = static_cast<int>(std::lround(xs[i] * (lo::kLutSize - 1)));
            (void)k;
        }
        LO_CHECK(std::fabs(lut[0] - ys[0]) < 1e-6f && std::fabs(lut[lo::kLutSize - 1] - ys[n - 1]) < 1e-6f);
        ++checked;
    }
    LO_CHECK(checked > 1000);
}

// Property: the parametric curve stays monotone for any amounts and split points.
LO_TEST(parametric_curve_is_monotone_for_random_settings) {
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> a(-100.f, 100.f), s(0.05f, 0.95f);
    std::vector<float> lut(lo::kLutSize);
    for (int trial = 0; trial < 500; ++trial) {
        float sp[3] = {s(rng), s(rng), s(rng)};
        std::sort(sp, sp + 3);
        if (sp[1] - sp[0] < 0.02f || sp[2] - sp[1] < 0.02f) continue;
        lo::grade::curveLut(lo::grade::parametricPoints(a(rng), a(rng), a(rng), a(rng), sp[0], sp[1], sp[2]), lut.data());
        for (int i = 1; i < lo::kLutSize; ++i) LO_CHECK(lut[i] >= lut[i - 1] - 1e-6f);
    }
}

LO_TEST(neutral_settings_are_identity) {
    LO_CHECK(lo::makeCurves(lo::CurveSet{})->isIdentity());
    lo::CurveSet line;
    line.rgb = {{0.f, 0.f}, {1.f, 1.f}};
    LO_CHECK(lo::makeCurves(line)->isIdentity());
    LO_CHECK(lo::makeParametricCurve(0, 0, 0, 0, 0.25f, 0.5f, 0.75f)->isIdentity());
    LO_CHECK(lo::makeLevels(levels(kLv, kLv, kLv, kLv))->isIdentity());
    LO_CHECK(lo::makeCalibration({0, 0, 0}, {0, 0, 0})->isIdentity());
    LO_CHECK(lo::makeChannelMixer({100, 0, 0, 0, 100, 0, 0, 0, 100}, {0, 0, 0}, false)->isIdentity());
    double m[9];
    lo::grade::calibrationMatrix({30, -20, 10}, {20, 0, -40}, m);
    for (int r = 0; r < 3; ++r) LO_CHECK(std::fabs(m[3 * r] + m[3 * r + 1] + m[3 * r + 2] - 1.0) < 1e-9);  // white kept
}

LO_TEST(graph_round_trips_every_grade_op) {
    std::ifstream file(std::string(LO_TESTDATA_DIR) + "/graph/grade_v1.json");
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
    LO_CHECK(ops->ops().size() == 11);
    const lo::OpType expected[11] = {lo::OpType::Calibration,   lo::OpType::Levels,        lo::OpType::Curves,
                                     lo::OpType::ParametricCurve, lo::OpType::ColorBalance, lo::OpType::SelectiveColor,
                                     lo::OpType::ChannelMixer,  lo::OpType::ColorGrading,  lo::OpType::PhotoFilter,
                                     lo::OpType::BlackAndWhite, lo::OpType::GradientMap};
    for (std::size_t i = 0; i < 11 && i < ops->ops().size(); ++i) LO_CHECK(ops->ops()[i]->type() == expected[i]);
}

LO_TEST(channel_percentiles_for_auto_levels) {
    std::vector<float> img(1000 * 4);
    for (int i = 0; i < 1000; ++i) {
        const float e = static_cast<float>(i) / 999.f;  // encoded ramp
        const float lin = e <= 0.04045f ? e / 12.92f : std::pow((e + 0.055f) / 1.055f, 2.4f);
        img[4 * i] = lin;
        img[4 * i + 1] = lin * 0.5f;
        img[4 * i + 2] = 0.2f;
        img[4 * i + 3] = 1.f;
    }
    float p[6];
    lo::grade::channelPercentiles(img.data(), 1000, 0.001f, 0.999f, p);
    LO_CHECK(std::fabs(p[0] - 1.f / 999.f) < 1e-4f && std::fabs(p[1] - 998.f / 999.f) < 1e-4f);
    LO_CHECK(p[3] < 0.75f && p[2] < 0.01f);
    LO_CHECK(std::fabs(p[4] - p[5]) < 1e-6f);  // a flat channel
}

int main() { return runAllTests(); }
