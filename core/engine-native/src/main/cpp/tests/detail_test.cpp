// Detail and Effects (Stage 10) against tools/golden/detail_reference.py (Dehaze 100 is the
// He et al. 2009 script itself, dehaze_reference.py), and the preview-vs-export consistency
// test: a 1024 x 768 image exported in 512 px tiles with aprons, downsampled 4x, against
// the 4x smaller preview rendered at scale 0.25 (PSNR > 35 dB).
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/detail.h"
#include "lineover/graph.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

namespace {

ImageF32 load(const char* name) { return readNpyF32(goldenPath(std::string("input/") + name)); }

void check(const ImageF32& actual, const std::string& file) {
    LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath(file)), file, kToleranceFloat));
}

ImageF32 run(const lo::Op& op, const ImageF32& input, float scale, const lo::DetailFields* fields = nullptr) {
    ImageF32 out = input;
    lo::OpRegion r;
    r.width = input.width;
    r.height = input.height;
    r.scale = scale;
    r.fields = fields;
    op.cpuRefRegion(input.data.data(), out.data.data(), r);
    return out;
}

}  // namespace

LO_TEST(sharpen_matches_python) {
    const ImageF32 scene = load("scene_rec2020_f32.npy");
    struct S { float amount, radius, detail, masking, scale; bool mask; };
    const S settings[4] = {{80, 1.0f, 25, 0, 1.0f, false}, {150, 2.5f, 80, 40, 1.0f, false}, {60, 1.5f, 25, 60, 1.0f, true},
                           {120, 2.0f, 50, 30, 0.5f, false}};
    for (int i = 0; i < 4; ++i) {
        const S& s = settings[i];
        check(run(*lo::makeSharpen(s.amount, s.radius, s.detail, s.masking, s.mask), scene, s.scale),
              "detail/sharpen_" + std::to_string(i + 1) + "_f32.npy");
    }
}

LO_TEST(noise_reduction_matches_python) {
    const ImageF32 scene = load("scene_rec2020_f32.npy");
    const float settings[3][5] = {{60, 50, 0, 25, 1.0f}, {100, 0, 60, 100, 1.0f}, {80, 30, 30, 60, 0.5f}};
    for (int i = 0; i < 3; ++i) {
        const float* s = settings[i];
        check(run(*lo::makeNoiseReduction(s[0], s[1], s[2], s[3]), scene, s[4]),
              "detail/noise_reduction_" + std::to_string(i + 1) + "_f32.npy");
    }
}

LO_TEST(clarity_and_texture_match_python) {
    const ImageF32 scene = load("scene_rec2020_f32.npy");
    const lo::DetailFields fields = lo::computeDetailFields(scene.data.data(), scene.width, scene.height);
    const float clarity[2] = {60, -50}, texture[2] = {70, -60};
    for (int i = 0; i < 2; ++i) {
        check(run(*lo::makeClarity(clarity[i]), scene, 1.f, &fields), "effects/clarity_" + std::to_string(i + 1) + "_f32.npy");
        check(run(*lo::makeTexture(texture[i]), scene, 1.f, &fields), "effects/texture_" + std::to_string(i + 1) + "_f32.npy");
    }
}

LO_TEST(dehaze_matches_python_and_he_2009) {
    const ImageF32 hazy = load("hazy_rec2020_f32.npy");
    const lo::DetailFields fields = lo::computeDetailFields(hazy.data.data(), hazy.width, hazy.height);
    const float amounts[3] = {100, 40, -50};
    for (int i = 0; i < 3; ++i) {
        check(run(*lo::makeDehaze(amounts[i]), hazy, 1.f, &fields), "effects/dehaze_" + std::to_string(i + 1) + "_f32.npy");
    }
    // He's A is the brightest pixel of the densest haze: at or above the synthetic airlight
    // (0.86, 0.88, 0.92), and as blue.
    LO_CHECK(fields.airlight[0] >= 0.86f && fields.airlight[1] >= 0.88f && fields.airlight[2] >= 0.92f);
    LO_CHECK(fields.airlight[2] > fields.airlight[0] && fields.airlight[2] <= 1.f);
}

LO_TEST(vignette_and_grain_match_python) {
    const ImageF32 scene = load("scene_rec2020_f32.npy");
    const double w = scene.width, h = scene.height;
    const float vig[3][5] = {{-60, 50, 0, 50, 0}, {-100, 20, -80, 10, 60}, {50, 70, 100, 80, 0}};
    for (int i = 0; i < 3; ++i) {
        lo::Finishing f;
        f.vignette = vig[i][0];
        f.midpoint = vig[i][1];
        f.roundness = vig[i][2];
        f.feather = vig[i][3];
        f.highlights = vig[i][4];
        ImageF32 out = scene;
        lo::applyFinishing(f, out.data.data(), out.width, out.height, w, h, 0, 0, 1);
        check(out, "effects/vignette_" + std::to_string(i + 1) + "_f32.npy");
    }
    const float grain[3][4] = {{60, 30, 40, 1.0f}, {100, 100, 100, 1.0f}, {60, 30, 40, 0.5f}};
    for (int i = 0; i < 3; ++i) {
        lo::Finishing f;
        f.grain = grain[i][0];
        f.grainSize = grain[i][1];
        f.roughness = grain[i][2];
        ImageF32 out = scene;
        lo::applyFinishing(f, out.data.data(), out.width, out.height, w / grain[i][3], h / grain[i][3], 0, 0, grain[i][3]);
        check(out, "effects/grain_" + std::to_string(i + 1) + "_f32.npy");
    }
}

LO_TEST(graph_maps_the_stage_10_ops) {
    lo::Graph g;
    lo::Layer layer;
    layer.ops.push_back({"sharpen", {{"amount", 50}, {"radius", 1}}, {}, {}});
    layer.ops.push_back({"noise_reduction", {{"luminance", 40}}, {}, {}});
    layer.ops.push_back({"clarity", {{"amount", 20}}, {}, {}});
    layer.ops.push_back({"texture", {{"amount", 20}}, {}, {}});
    layer.ops.push_back({"dehaze", {{"amount", 20}}, {}, {}});
    layer.ops.push_back({"post_crop_vignette", {{"amount", -30}}, {}, {}});
    layer.ops.push_back({"grain", {{"amount", 30}}, {}, {}});
    g.layers.push_back(layer);
    std::string skipped;
    const auto ops = lo::buildOps(g, &skipped);
    LO_CHECK(skipped.empty());
    LO_CHECK(ops->ops().size() == 5);
    LO_CHECK(ops->apron() > 0);
    lo::Finishing f;
    LO_CHECK(lo::finishingOf(g, &f));
    LO_CHECK(f.vignette == -30.f && f.grain == 30.f);
}

// The graph Kotlin writes (testdata/graph/detail_v1.json, DetailAdjustmentsTest): read,
// mapped to the five source ops and the finishing, and written back byte for byte.
LO_TEST(kotlin_fixture_maps_and_round_trips) {
    std::ifstream file(std::string(LO_TESTDATA_DIR) + "/graph/detail_v1.json");
    std::stringstream text;
    text << file.rdbuf();
    std::string json = text.str();
    while (!json.empty() && (json.back() == '\n' || json.back() == '\r')) json.pop_back();
    lo::Graph g;
    std::string error;
    LO_CHECK(lo::parseGraph(json, &g, &error));
    std::string skipped;
    const auto ops = lo::buildOps(g, &skipped);
    LO_CHECK(skipped.empty());
    LO_CHECK(ops->ops().size() == 5);
    LO_CHECK(ops->ops()[0]->type() == lo::OpType::NoiseReduction && ops->ops()[4]->type() == lo::OpType::Sharpen);
    lo::Finishing f;
    LO_CHECK(lo::finishingOf(g, &f));
    LO_CHECK(f.vignette == -30.f && f.roundness == 20.f && f.feather == 60.f && f.highlights == 10.f && f.grain == 20.f);
    LO_CHECK(lo::serializeGraph(g) == json);
}

namespace {

// A procedural "photo": smooth gradients, edges, fine texture and noise (linear Rec.2020).
std::vector<float> photo(int w, int h) {
    std::vector<float> out(std::size_t(w) * h * 4);
    std::uint32_t state = 12345u;
    auto rnd = [&]() {
        state = state * 1664525u + 1013904223u;
        return (state >> 8) / 16777216.0;
    };
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double u = (x + 0.5) / w, v = (y + 0.5) / h;
            double base = 0.05 + 0.5 * u * (1 - 0.6 * v);
            if (std::hypot(u - 0.6, v - 0.45) < 0.18) base = 0.7;
            if (x % 97 < 3) base *= 0.3;
            const double tex = 0.04 * std::sin(x * 0.9) * std::sin(y * 0.7);
            const double noise = 0.03 * (rnd() - 0.5);
            float* p = &out[4 * (std::size_t(y) * w + x)];
            const double l = std::max(0.002, base + tex + noise);
            p[0] = static_cast<float>(l * (0.9 + 0.3 * v));
            p[1] = static_cast<float>(l);
            p[2] = static_cast<float>(l * (1.1 - 0.3 * u) + 0.01 * (rnd() - 0.5));
            p[3] = 1.f;
        }
    return out;
}

// Box downsample by an integer factor, averaged in linear light.
std::vector<float> downsample(const std::vector<float>& in, int w, int h, int f) {
    const int ow = w / f, oh = h / f;
    std::vector<float> out(std::size_t(ow) * oh * 4, 0.f);
    for (int y = 0; y < oh; ++y)
        for (int x = 0; x < ow; ++x)
            for (int c = 0; c < 4; ++c) {
                double s = 0;
                for (int j = 0; j < f; ++j)
                    for (int i = 0; i < f; ++i) s += in[4 * (std::size_t(y * f + j) * w + x * f + i) + c];
                out[4 * (std::size_t(y) * ow + x) + c] = static_cast<float>(s / (f * f));
            }
    return out;
}

double encode(double x) {
    x = std::clamp(x, 0.0, 1.0);
    return x <= 0.0031308 ? x * 12.92 : 1.055 * std::pow(x, 1.0 / 2.4) - 0.055;
}

// PSNR of the encoded RGB (display values 0..1).
double psnr(const std::vector<float>& a, const std::vector<float>& b) {
    double se = 0;
    std::size_t n = 0;
    for (std::size_t i = 0; i < a.size(); ++i) {
        if (i % 4 == 3) continue;
        const double d = encode(a[i]) - encode(b[i]);
        se += d * d;
        ++n;
    }
    const double mse = se / n;
    return mse <= 0 ? 99.0 : 10.0 * std::log10(1.0 / mse);
}

lo::Graph allOps() {
    lo::Graph g;
    lo::Layer layer;
    layer.ops.push_back({"noise_reduction", {{"luminance", 50}, {"detail", 50}, {"contrast", 30}, {"color", 50}}, {}, {}});
    layer.ops.push_back({"clarity", {{"amount", 50}}, {}, {}});
    layer.ops.push_back({"texture", {{"amount", 40}}, {}, {}});
    layer.ops.push_back({"dehaze", {{"amount", 30}}, {}, {}});
    layer.ops.push_back({"sharpen", {{"amount", 60}, {"radius", 1.0}, {"detail", 25}, {"masking", 20}}, {}, {}});
    layer.ops.push_back({"post_crop_vignette", {{"amount", -40}, {"midpoint", 50}, {"feather", 50}}, {}, {}});
    layer.ops.push_back({"grain", {{"amount", 30}, {"size", 25}, {"roughness", 50}}, {}, {}});
    g.layers.push_back(layer);
    return g;
}

}  // namespace

LO_TEST(preview_matches_the_downsampled_export) {
    const int w = 1024, h = 768, f = 4, tile = 512;
    const std::vector<float> full = photo(w, h);
    const lo::Graph graph = allOps();
    const auto ops = lo::buildOps(graph, nullptr);
    lo::Finishing fin;
    lo::finishingOf(graph, &fin);

    // Preview: 4x smaller, scale 0.25; fields from the preview itself.
    const int pw = w / f, ph = h / f;
    std::vector<float> preview = downsample(full, w, h, f);
    const lo::DetailFields fields = lo::computeDetailFields(preview.data(), pw, ph);
    {
        lo::OpRegion r;
        r.width = pw;
        r.height = ph;
        r.scale = 1.f / f;
        r.fields = &fields;
        ops->cpuRefRegion(preview.data(), preview.data(), r);
        lo::applyFinishing(fin, preview.data(), pw, ph, w, h, 0, 0, 1.0 / f);
    }

    // Export: full resolution in 512 px tiles, each padded by the op list's apron.
    const int apron = ops->apron();
    std::vector<float> exported(full.size());
    for (int ty = 0; ty < h; ty += tile)
        for (int tx = 0; tx < w; tx += tile) {
            const int x0 = std::max(0, tx - apron), y0 = std::max(0, ty - apron);
            const int x1 = std::min(w, tx + tile + apron), y1 = std::min(h, ty + tile + apron);
            const int bw = x1 - x0, bh = y1 - y0;
            std::vector<float> buf(std::size_t(bw) * bh * 4);
            for (int y = 0; y < bh; ++y)
                std::copy_n(&full[4 * (std::size_t(y0 + y) * w + x0)], 4 * bw, &buf[4 * std::size_t(y) * bw]);
            lo::OpRegion r;
            r.width = bw;
            r.height = bh;
            r.rect[0] = float(x0) / w;
            r.rect[1] = float(y0) / h;
            r.rect[2] = float(bw) / w;
            r.rect[3] = float(bh) / h;
            r.scale = 1.f;
            r.fields = &fields;
            ops->cpuRefRegion(buf.data(), buf.data(), r);
            lo::applyFinishing(fin, buf.data(), bw, bh, w, h, x0, y0, 1.0);
            const int iw = std::min(tile, w - tx), ih = std::min(tile, h - ty);
            for (int y = 0; y < ih; ++y)
                std::copy_n(&buf[4 * (std::size_t(ty - y0 + y) * bw + (tx - x0))], 4 * iw, &exported[4 * (std::size_t(ty + y) * w + tx)]);
        }

    // The tiles must agree with a whole-image export (the apron is wide enough). What remains
    // is float rounding of the tile uv where the fields are sampled (amplified by Dehaze's
    // 1 / t): within the float-op tolerance, and it does not shrink with a wider apron.
    std::vector<float> whole = full;
    {
        lo::OpRegion r;
        r.width = w;
        r.height = h;
        r.scale = 1.f;
        r.fields = &fields;
        ops->cpuRefRegion(whole.data(), whole.data(), r);
        lo::applyFinishing(fin, whole.data(), w, h, w, h, 0, 0, 1.0);
    }
    double worst = 0;
    for (std::size_t i = 0; i < whole.size(); ++i) worst = std::max(worst, double(std::fabs(whole[i] - exported[i])));
    std::printf("tiled vs whole export: max |diff| %.3g (apron %d px)\n", worst, apron);
    LO_CHECK(worst < kToleranceFloat.maxAbs);

    const double db = psnr(downsample(exported, w, h, f), preview);
    std::printf("preview vs downsampled export: PSNR %.2f dB\n", db);
    LO_CHECK(db > 35.0);
}

int main() { return runAllTests(); }
