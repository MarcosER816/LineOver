#include "lineover/auto_adjust.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "lineover/color_ops.h"
#include "lineover/light.h"
#include "op_util.h"

namespace lo {
namespace {

constexpr double kMidGrey = 0.18;
constexpr double kExposureWeight = 0.7;  // part of the way to mid grey: keeps the mood
constexpr double kExposureLimit = 2.0;
constexpr double kHighlightCeiling = 1.2;  // the 99th percentile may end at most here
constexpr double kContrastTarget = 6.5;    // stops between the 5th and 95th percentiles
constexpr double kContrastPerStop = 10.0;
constexpr double kWhiteTarget = 0.95;
constexpr double kBlackTarget = 0.006;
constexpr double kVibranceMax = 25.0;
constexpr double kVibranceChroma = 0.12;  // mean OKLCh chroma at which Auto adds none
constexpr std::size_t kMaxSamples = 250000;

double chroma2(const float rgb[3]) {
    const double v[3] = {rgb[0], rgb[1], rgb[2]};
    double lab[3];
    opsutil::toLab(v, lab);
    return lab[1] * lab[1] + lab[2] * lab[2];
}

// The white balance whose output of `mean` is closest to neutral (OKLab chroma): a grid in
// mireds x tint, then five refinements around the best point.
void grayWorld(const float mean[3], float* kelvin, float* tint) {
    auto cost = [&](double mired, double t) {
        const auto op = makeWhiteBalance(static_cast<float>(1e6 / mired), static_cast<float>(t));
        float px[4] = {mean[0], mean[1], mean[2], 1.f};
        op->cpuRef(px, px, 1);
        return chroma2(px);
    };
    const double mLo = 1e6 / kWbMaxKelvin, mHi = 1e6 / kWbMinKelvin;
    double bestM = 1e6 / kWbDefaultKelvin, bestT = 0.0, best = cost(bestM, bestT);
    double stepM = (mHi - mLo) / 48.0, stepT = 2.0 * kWbTintRange / 30.0;
    for (double m = mLo; m <= mHi + 1e-9; m += stepM)
        for (double t = -kWbTintRange; t <= kWbTintRange + 1e-9; t += stepT) {
            const double c = cost(m, t);
            if (c < best) {
                best = c;
                bestM = m;
                bestT = t;
            }
        }
    for (int round = 0; round < 5; ++round) {
        stepM *= 0.5;
        stepT *= 0.5;
        const double cm = bestM, ct = bestT;
        for (int i = -2; i <= 2; ++i)
            for (int j = -2; j <= 2; ++j) {
                const double m = std::clamp(cm + i * stepM, mLo, mHi);
                const double t = std::clamp(ct + j * stepT, -double(kWbTintRange), double(kWbTintRange));
                const double c = cost(m, t);
                if (c < best) {
                    best = c;
                    bestM = m;
                    bestT = t;
                }
            }
    }
    *kelvin = static_cast<float>(1e6 / bestM);
    *tint = static_cast<float>(bestT);
}

double percentile(std::vector<double>& v, double q) {
    const auto k = static_cast<std::size_t>(std::floor(std::clamp(q, 0.0, 1.0) * (v.size() - 1) + 0.5));
    std::nth_element(v.begin(), v.begin() + static_cast<std::ptrdiff_t>(k), v.end());
    return v[k];
}

}  // namespace

bool autoSettings(const float* rgba, int width, int height, AutoSettings* out) {
    *out = AutoSettings{};
    if (rgba == nullptr || width <= 0 || height <= 0) return false;
    const std::size_t n = static_cast<std::size_t>(width) * height;
    const std::size_t stride = std::max<std::size_t>(1, n / kMaxSamples);

    // Gray world over the midtones (no clipped or near-black pixels).
    double sum[3] = {0, 0, 0};
    std::size_t count = 0;
    for (std::size_t i = 0; i < n; i += stride) {
        const float* p = rgba + 4 * i;
        const double y = light::luminance(p);
        if (y < 0.03 || y > 0.85 || p[0] > 0.98f || p[1] > 0.98f || p[2] > 0.98f) continue;
        for (int k = 0; k < 3; ++k) sum[k] += p[k];
        ++count;
    }
    if (count > 0) {
        const float mean[3] = {float(sum[0] / count), float(sum[1] / count), float(sum[2] / count)};
        grayWorld(mean, &out->temperature, &out->tint);
    }

    // The balanced image's luminance and chroma, sampled.
    const auto wb = makeWhiteBalance(out->temperature, out->tint);
    std::vector<double> lum;
    lum.reserve(n / stride + 1);
    double chroma = 0.0;
    for (std::size_t i = 0; i < n; i += stride) {
        float px[4] = {rgba[4 * i], rgba[4 * i + 1], rgba[4 * i + 2], 1.f};
        wb->cpuRef(px, px, 1);
        lum.push_back(std::max(static_cast<double>(light::luminance(px)), 1e-6));
        chroma += std::sqrt(chroma2(px));
    }
    if (lum.empty()) return false;
    chroma /= static_cast<double>(lum.size());
    const double median = percentile(lum, 0.5);
    const double p01 = percentile(lum, 0.005), p05 = percentile(lum, 0.05);
    const double p95 = percentile(lum, 0.95), p99 = percentile(lum, 0.99), p995 = percentile(lum, 0.995);
    if (p995 <= 1e-5) return false;

    double ev = std::clamp(kExposureWeight * std::log2(kMidGrey / median), -kExposureLimit, kExposureLimit);
    ev = std::min(ev, std::log2(kHighlightCeiling / p99));  // never push the highlights far past white
    ev = std::clamp(ev, -kExposureLimit, kExposureLimit);
    const double gain = std::exp2(ev);
    const double hi = p995 * gain, lo = p01 * gain;

    // Whites: the 99.5th percentile towards 0.95 (an endpoint shift of 1 stop is 100).
    double whites = 0.0;
    if (hi < 0.9 || hi > 1.05) whites = std::clamp(100.0 * std::log2(kWhiteTarget / hi), -60.0, 60.0);
    // Blacks: a washed-out floor down, a crushed one gently up.
    double blacks = 0.0;
    if (lo > 2.0 * kBlackTarget) blacks = std::clamp(50.0 * std::log2(kBlackTarget / lo), -50.0, 0.0);
    else if (lo < light::kBlackPoint) blacks = std::clamp(50.0 * std::log2(light::kBlackPoint / lo), 0.0, 30.0);
    // Contrast: the 5..95 % spread towards 6.5 stops.
    const double spread = std::log2(p95 / std::max(p05, 1e-6));
    const double contrast = std::clamp((kContrastTarget - spread) * kContrastPerStop, -30.0, 40.0);

    out->exposure = static_cast<float>(std::round(ev * 100.0) / 100.0);
    out->whites = static_cast<float>(std::round(whites));
    out->blacks = static_cast<float>(std::round(blacks));
    out->contrast = static_cast<float>(std::round(contrast));
    out->vibrance = static_cast<float>(std::round(std::clamp(kVibranceMax * (1.0 - chroma / kVibranceChroma), 0.0, kVibranceMax)));
    out->temperature = std::round(out->temperature);
    out->tint = std::round(out->tint);
    return true;
}

}  // namespace lo
