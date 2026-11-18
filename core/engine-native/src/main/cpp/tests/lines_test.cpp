// Stage 9 analysis on synthetic images: line segments (Upright), the horizon (auto
// straighten) and chromatic aberration (auto).
#include <algorithm>
#include <cmath>
#include <vector>

#include "lineover/geometry.h"
#include "lineover/lines.h"
#include "lo_test.h"

namespace {

constexpr double kPi = 3.14159265358979323846;

std::vector<float> blank(int w, int h, float v) {
    std::vector<float> img(static_cast<std::size_t>(w) * h * 4, v);
    for (std::size_t i = 3; i < img.size(); i += 4) img[i] = 1.f;
    return img;
}

// A bright straight bar (3 px wide, anti-aliased like a photographed edge) from (x1, y1) to (x2, y2).
void bar(std::vector<float>& img, int w, int h, double x1, double y1, double x2, double y2) {
    const double len = std::hypot(x2 - x1, y2 - y1);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double px = x + 0.5 - x1, py = y + 0.5 - y1;
            const double t = (px * (x2 - x1) + py * (y2 - y1)) / (len * len);
            if (t < 0 || t > 1) continue;
            const double d = std::fabs(px * (y2 - y1) - py * (x2 - x1)) / len;
            const double cover = std::clamp(2.0 - d, 0.0, 1.0);  // 1 inside 1.5 px, 0 beyond 2 px
            if (cover > 0.0)
                for (int k = 0; k < 3; ++k) {
                    float& p = img[4 * (static_cast<std::size_t>(y) * w + x) + k];
                    p = std::max(p, static_cast<float>(0.05 + 0.85 * cover));
                }
        }
}

double segmentAngle(const lo::LineSegment& s, int w, int h) {
    double a = std::atan2((s.y2 - s.y1) * h, (s.x2 - s.x1) * w) * 180.0 / kPi;
    while (a < 0) a += 180.0;
    return a;
}

}  // namespace

LO_TEST(segments_find_drawn_lines) {
    const int w = 400, h = 300;
    auto img = blank(w, h, 0.05f);
    bar(img, w, h, 100, 30, 112, 270);   // a near-vertical edge (87.1 deg)
    bar(img, w, h, 160, 200, 380, 140);  // a slanted one (-15.3 deg = 164.7)
    const auto segments = lo::detectSegments(img.data(), w, h);
    bool vertical = false, slanted = false;
    for (const auto& s : segments) {
        const double a = segmentAngle(s, w, h);
        const double length = std::hypot((s.x2 - s.x1) * w, (s.y2 - s.y1) * h);
        if (length > 180 && std::fabs(a - 87.14) < 1.0) vertical = true;
        if (length > 180 && std::fabs(a - 164.74) < 1.0) slanted = true;
    }
    LO_CHECK(vertical);
    LO_CHECK(slanted);
    // A flat image has no lines.
    const auto none = blank(w, h, 0.3f);
    LO_CHECK(lo::detectSegments(none.data(), w, h).empty());
}

LO_TEST(horizon_finds_a_tilted_horizon) {
    for (double tilt : {7.0, -12.0, 0.0}) {
        const int w = 480, h = 320;
        auto img = blank(w, h, 0.02f);
        const double slope = std::tan(tilt * kPi / 180.0);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const double horizon = h / 2.0 + (x + 0.5 - w / 2.0) * slope;
                if (y + 0.5 < horizon)
                    for (int k = 0; k < 3; ++k) img[4 * (static_cast<std::size_t>(y) * w + x) + k] = 0.7f;
            }
        float degrees = 99.f;
        LO_CHECK(lo::horizonAngle(img.data(), w, h, &degrees));
        LO_CHECK(std::fabs(degrees - tilt) <= 0.5);
    }
    const auto flat = blank(200, 150, 0.4f);
    float degrees = 0.f;
    LO_CHECK(!lo::horizonAngle(flat.data(), 200, 150, &degrees));
}

LO_TEST(chromatic_aberration_is_recovered) {
    // Concentric rings (edges everywhere away from the centre), grey: R = G = B.
    const int w = 320, h = 240;
    auto clean = blank(w, h, 0.f);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double r = std::hypot(x + 0.5 - w / 2.0, y + 0.5 - h / 2.0);
            const float v = std::fmod(r, 24.0) < 12.0 ? 0.8f : 0.1f;
            for (int k = 0; k < 3; ++k) clean[4 * (static_cast<std::size_t>(y) * w + x) + k] = v;
        }
    // Add lateral CA with the geometry warp itself (red sampled further out, blue further in).
    lo::Geometry g;
    g.matrix[0] = w;
    g.matrix[4] = h;
    g.outputWidth = w;
    g.outputHeight = h;
    g.caRed = 0.004;
    g.caBlue = -0.003;
    std::vector<float> fringed(clean.size());
    lo::WarpSource s;
    s.rgba = clean.data();
    s.width = w;
    s.height = h;
    lo::warp(g, w, h, s, 1.0, 0, 0, w, h, fringed.data());
    float red = 0.f, blue = 0.f;
    LO_CHECK(lo::estimateChromaticAberration(fringed.data(), w, h, &red, &blue));
    // The correction samples red at 1 / 1.004 of the radius, blue at 1 / 0.997.
    LO_CHECK(std::fabs(red - (1.0 / 1.004 - 1.0)) < 0.0005);
    LO_CHECK(std::fabs(blue - (1.0 / 0.997 - 1.0)) < 0.0005);
    // A clean image needs no correction.
    LO_CHECK(lo::estimateChromaticAberration(clean.data(), w, h, &red, &blue));
    LO_CHECK(std::fabs(red) < 0.0002 && std::fabs(blue) < 0.0002);
}

int main() { return lo::testing::runAllTests(); }
