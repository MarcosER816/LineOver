// 3D LUTs, profiles and Auto (Stage 11) against tools/golden/lut_reference.py: tetrahedral
// interpolation within 1/255 of the Python reference (the brief) and 1e-3 (float ops).
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/auto_adjust.h"
#include "lineover/color_ops.h"
#include "lineover/graph.h"
#include "lineover/light.h"
#include "lineover/lut.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

namespace {

constexpr double kOneStep = 1.0 / 255.0;

// A table image (row b * N + g, column r) as a Lut3D.
std::shared_ptr<lo::Lut3D> lutFrom(const std::string& file, float domainMax = 1.f) {
    const ImageF32 t = readNpyF32(goldenPath(file));
    auto lut = std::make_shared<lo::Lut3D>();
    lut->size = t.width;
    for (int k = 0; k < 3; ++k) lut->domainMax[k] = domainMax;
    lut->rgb.reserve(static_cast<std::size_t>(t.width) * t.height * 3);
    for (std::size_t i = 0; i < t.data.size(); i += 4)
        for (int k = 0; k < 3; ++k) lut->rgb.push_back(t.data[i + k]);
    LO_CHECK(lut->finish());
    return lut;
}

ImageF32 run(const lo::Op& op, const ImageF32& in) {
    ImageF32 out = in;
    op.cpuRef(in.data.data(), out.data.data(), in.data.size() / 4);
    return out;
}

double maxDiff(const ImageF32& a, const ImageF32& b) {
    double worst = 0;
    for (std::size_t i = 0; i < a.data.size(); ++i) worst = std::max(worst, double(std::fabs(a.data[i] - b.data[i])));
    return worst;
}

void check(const ImageF32& actual, const std::string& file) {
    const ImageF32 expected = readNpyF32(goldenPath(file));
    const double worst = maxDiff(actual, expected);
    std::printf("%s: max |diff| %.3g (1/255 = %.3g)\n", file.c_str(), worst, kOneStep);
    LO_CHECK(worst <= kOneStep);
    LO_CHECK_GOLDEN(compareGolden(actual, expected, file, kToleranceFloat));
}

ImageF32 chart() { return readNpyF32(goldenPath("input/colors_rec2020_f32.npy")); }
ImageF32 scene() { return readNpyF32(goldenPath("input/scene_rec2020_f32.npy")); }

}  // namespace

LO_TEST(imported_luts_match_python) {
    check(run(*lo::makeLut(lutFrom("luts/warm_fade_17_table_f32.npy"), 1.f), chart()), "luts/warm_fade_17_100_f32.npy");
    // Domain 0..1.25 and the HDR scene (values beyond the domain continue in luma).
    check(run(*lo::makeLut(lutFrom("luts/teal_amber_33_table_f32.npy", 1.25f), 0.6f), scene()), "luts/teal_amber_33_60_f32.npy");
}

LO_TEST(profile_tables_match_python) {
    for (const char* name : {"vivid", "mono", "flat"}) {
        const auto made = lo::profileLut(name);
        LO_CHECK(made != nullptr);
        if (!made) continue;
        const auto ref = lutFrom(std::string("profiles/") + name + "_table_f32.npy");
        double worst = 0;
        for (std::size_t i = 0; i < made->rgb.size(); ++i) worst = std::max(worst, double(std::fabs(made->rgb[i] - ref->rgb[i])));
        std::printf("profile %s table: max |diff| %.3g\n", name, worst);
        LO_CHECK(worst <= 1e-3);
    }
    LO_CHECK(lo::profileLut("neutral") == nullptr);
    LO_CHECK(lo::profileLut("sepia") == nullptr);
}

LO_TEST(profiles_match_python) {
    for (const char* name : {"vivid", "mono", "flat"}) {
        check(run(*lo::makeLut(lo::profileLut(name), 1.f, lo::OpType::Profile), chart()),
              std::string("profiles/") + name + "_100_f32.npy");
    }
    check(run(*lo::makeLut(lo::profileLut("flat"), 0.5f, lo::OpType::Profile), chart()), "profiles/flat_50_f32.npy");
    // Neutral adds no op in the app; its identity table through the LUT op (out-of-gamut
    // chroma mapped like any LUT) matches the reference too.
    check(run(*lo::makeLut(lutFrom("profiles/neutral_table_f32.npy"), 1.f), chart()), "profiles/neutral_100_f32.npy");
}

LO_TEST(tetrahedral_hits_the_grid_and_the_identity) {
    const auto identity = lutFrom("profiles/neutral_table_f32.npy");
    double worst = 0;
    for (int i = 0; i <= 50; ++i) {
        const double c[3] = {i / 50.0, std::fmod(i * 0.37, 1.0), std::fmod(i * 0.71, 1.0)};
        double o[3];
        lo::lutTetrahedral(*identity, c, o);
        for (int k = 0; k < 3; ++k) worst = std::max(worst, std::fabs(o[k] - c[k]));
    }
    std::printf("identity LUT: max |diff| %.3g\n", worst);
    LO_CHECK(worst < 1e-6);
}

LO_TEST(graph_maps_luts_by_id_and_profiles_by_name) {
    lo::registerLut("abc123", lutFrom("luts/warm_fade_17_table_f32.npy"));
    const std::string json =
        R"({"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"profile","name":"vivid","amount":80.0},)"
        R"({"op":"lut","id":"abc123","amount":60.0},{"op":"lut","id":"missing","amount":100.0}]}]})";
    lo::Graph g;
    std::string error;
    LO_CHECK(lo::parseGraph(json, &g, &error));
    LO_CHECK(lo::serializeGraph(g) == json);
    std::string skipped;
    const auto ops = lo::buildOps(g, &skipped);
    LO_CHECK(ops->ops().size() == 2);
    LO_CHECK(ops->ops()[0]->type() == lo::OpType::Profile && ops->ops()[1]->type() == lo::OpType::Lut);
    LO_CHECK(skipped == "lut:missing");
    lo::unregisterLut("abc123");
    LO_CHECK(lo::findLut("abc123") == nullptr);
}

LO_TEST(lut_data_is_validated) {
    lo::Lut3D bad;
    bad.size = 66;
    bad.rgb.assign(66 * 66 * 66 * 3, 0.f);
    LO_CHECK(!bad.finish());
    lo::Lut3D shortTable;
    shortTable.size = 2;
    shortTable.rgb.assign(5, 0.f);
    LO_CHECK(!shortTable.finish());
    lo::Lut3D inverted;
    inverted.size = 2;
    inverted.rgb.assign(24, 0.f);
    inverted.domainMax[1] = -1.f;
    LO_CHECK(!inverted.finish());
}

// ---------------------------------------------------------------------------- Auto

namespace {

// A smooth test photo: a luminance ramp with texture, tinted by `cast`, scaled by `gain`.
std::vector<float> photo(int w, int h, double gain, const double cast[3], double spread) {
    std::vector<float> out(std::size_t(w) * h * 4);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double u = (x + 0.5) / w, v = (y + 0.5) / h;
            const double stops = spread * (u - 0.5) + 0.8 * std::sin(9 * v) * 0.5;
            const double l = 0.18 * std::exp2(stops) * gain;
            float* p = &out[4 * (std::size_t(y) * w + x)];
            for (int k = 0; k < 3; ++k) p[k] = static_cast<float>(l * cast[k]);
            p[3] = 1.f;
        }
    return out;
}

}  // namespace

LO_TEST(auto_brightens_a_dark_photo_and_darkens_a_bright_one) {
    const double neutral[3] = {1, 1, 1};
    lo::AutoSettings dark, bright;
    auto d = photo(200, 150, 0.15, neutral, 6.0);
    auto b = photo(200, 150, 3.0, neutral, 6.0);
    LO_CHECK(lo::autoSettings(d.data(), 200, 150, &dark));
    LO_CHECK(lo::autoSettings(b.data(), 200, 150, &bright));
    std::printf("auto dark: ev %.2f, bright: ev %.2f\n", dark.exposure, bright.exposure);
    LO_CHECK(dark.exposure > 1.0f);
    LO_CHECK(bright.exposure < -0.5f);
}

LO_TEST(auto_neutralises_a_colour_cast) {
    const double cast[3] = {0.75, 0.95, 1.35};  // bluish
    auto img = photo(160, 120, 1.0, cast, 5.0);
    lo::AutoSettings s;
    LO_CHECK(lo::autoSettings(img.data(), 160, 120, &s));
    // The balanced mean is grey: R ~ G ~ B.
    const auto wb = lo::makeWhiteBalance(s.temperature, s.tint);
    double mean[3] = {0, 0, 0};
    for (std::size_t i = 0; i < img.size(); i += 4)
        for (int k = 0; k < 3; ++k) mean[k] += img[i + k];
    float px[4] = {float(mean[0]), float(mean[1]), float(mean[2]), 1.f};
    wb->cpuRef(px, px, 1);
    const double m = (px[0] + px[1] + px[2]) / 3.0;
    std::printf("auto cast: %.0f K tint %.0f -> rgb %.3f %.3f %.3f\n", s.temperature, s.tint, px[0] / m, px[1] / m, px[2] / m);
    LO_CHECK(s.temperature > 6504.f);  // a bluish cast is warmed: the light was bluer than D65
    for (int k = 0; k < 3; ++k) LO_CHECK(std::fabs(px[k] / m - 1.0) < 0.03);
}

LO_TEST(auto_sets_contrast_from_the_spread_and_vibrance_mildly) {
    const double neutral[3] = {1, 1, 1};
    auto flat = photo(160, 120, 1.0, neutral, 2.0);
    auto punchy = photo(160, 120, 1.0, neutral, 11.0);
    lo::AutoSettings a, b;
    LO_CHECK(lo::autoSettings(flat.data(), 160, 120, &a));
    LO_CHECK(lo::autoSettings(punchy.data(), 160, 120, &b));
    std::printf("auto contrast: flat %.0f, punchy %.0f; vibrance %.0f\n", a.contrast, b.contrast, a.vibrance);
    LO_CHECK(a.contrast > 20.f);
    LO_CHECK(b.contrast < 0.f);
    LO_CHECK(a.vibrance > 0.f && a.vibrance <= 25.f);
    const double vivid[3] = {1.6, 0.6, 0.3};  // a saturated photo gets little or no vibrance
    auto sat = photo(160, 120, 1.0, vivid, 5.0);
    lo::AutoSettings c;
    LO_CHECK(lo::autoSettings(sat.data(), 160, 120, &c));
    LO_CHECK(c.vibrance < a.vibrance);
}

LO_TEST(auto_refuses_an_empty_image) {
    lo::AutoSettings s;
    std::vector<float> black(64 * 4, 0.f);
    LO_CHECK(!lo::autoSettings(black.data(), 8, 8, &s));
    LO_CHECK(!lo::autoSettings(nullptr, 0, 0, &s));
}

int main() { return runAllTests(); }
