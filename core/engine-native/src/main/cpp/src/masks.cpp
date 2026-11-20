// Layer masks (Stage 14); see masks.h and tools/golden/blend_reference.py.
#include "lineover/masks.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <unordered_map>

#include "lineover/color_ops.h"
#include "lineover/smart_select.h"

namespace lo::masks {

std::uint16_t toHalf(float value) {
    std::uint32_t f;
    std::memcpy(&f, &value, sizeof f);
    const std::uint32_t sign = (f >> 16) & 0x8000u;
    const std::uint32_t abs = f & 0x7fffffffu;
    if (abs >= 0x7f800000u) return static_cast<std::uint16_t>(sign | 0x7c00u | (abs > 0x7f800000u ? 0x200u : 0u));
    if (abs >= 0x477ff000u) return static_cast<std::uint16_t>(sign | 0x7c00u);  // overflow -> inf
    if (abs < 0x38800000u) {
        // Subnormal half (or zero): value / 2^-24, rounded to nearest even.
        if (abs < 0x33000000u) return static_cast<std::uint16_t>(sign);
        const std::uint32_t mant = (abs & 0x7fffffu) | 0x800000u;
        const int shift = 126 - static_cast<int>(abs >> 23);  // 14 .. 24
        const std::uint32_t half = mant >> shift;
        const std::uint32_t rest = mant & ((1u << shift) - 1u);
        const std::uint32_t mid = 1u << (shift - 1);
        const std::uint32_t rounded = half + ((rest > mid || (rest == mid && (half & 1u))) ? 1u : 0u);
        return static_cast<std::uint16_t>(sign | rounded);
    }
    const std::uint32_t exp = (abs >> 23) - 112u;  // rebias 127 -> 15
    std::uint32_t half = (exp << 10) | ((abs >> 13) & 0x3ffu);
    const std::uint32_t rest = abs & 0x1fffu;
    if (rest > 0x1000u || (rest == 0x1000u && (half & 1u))) ++half;  // may carry into the exponent: correct
    return static_cast<std::uint16_t>(sign | half);
}

float fromHalf(std::uint16_t h) {
    const std::uint32_t sign = static_cast<std::uint32_t>(h & 0x8000u) << 16;
    const std::uint32_t exp = (h >> 10) & 0x1fu;
    const std::uint32_t mant = h & 0x3ffu;
    std::uint32_t f;
    if (exp == 0) {
        if (mant == 0) {
            f = sign;
        } else {
            float v = std::ldexp(static_cast<float>(mant), -24);
            return sign ? -v : v;
        }
    } else if (exp == 31) {
        f = sign | 0x7f800000u | (mant << 13);
    } else {
        f = sign | ((exp + 112u) << 23) | (mant << 13);
    }
    float out;
    std::memcpy(&out, &f, sizeof out);
    return out;
}

void MaskTiles::reset(int width, int height) {
    width_ = std::max(0, width);
    height_ = std::max(0, height);
    columns_ = (width_ + kTile - 1) / kTile;
    const int rows = (height_ + kTile - 1) / kTile;
    tiles_.assign(static_cast<std::size_t>(columns_) * rows, std::vector<std::uint16_t>(kTile * kTile, 0));
}

void MaskTiles::set(int x, int y, float value) {
    tiles_[static_cast<std::size_t>(y / kTile) * columns_ + x / kTile][(y % kTile) * kTile + x % kTile] = toHalf(value);
}

float MaskTiles::at(int x, int y) const {
    return fromHalf(tiles_[static_cast<std::size_t>(y / kTile) * columns_ + x / kTile][(y % kTile) * kTile + x % kTile]);
}

std::vector<double> gaussianKernel(double sigma) {
    if (sigma <= 0.0) return {};
    // cv::createGaussianKernels for float images: ksize = cvRound(sigma * 4 * 2 + 1) | 1.
    const int ksize = static_cast<int>(std::lrint(sigma * 8.0 + 1.0)) | 1;
    std::vector<double> k(static_cast<std::size_t>(ksize));
    const double scale = -0.5 / (sigma * sigma);
    double sum = 0.0;
    for (int i = 0; i < ksize; ++i) {
        const double x = i - (ksize - 1) * 0.5;
        k[static_cast<std::size_t>(i)] = std::exp(scale * x * x);
        sum += k[static_cast<std::size_t>(i)];
    }
    for (double& v : k) v /= sum;
    return k;
}

namespace {

int reflect101(int i, int n) {
    if (n <= 1) return 0;
    while (i < 0 || i >= n) {
        if (i < 0) i = -i;
        if (i >= n) i = 2 * n - 2 - i;
    }
    return i;
}

// One pass of the separable blur: out(i) = sum k(t) in(i + t - r), [in] already padded by r.
void convolveRows(const std::vector<float>& in, int inWidth, int height, const std::vector<double>& k, int outWidth,
                  std::vector<float>* out) {
    out->assign(static_cast<std::size_t>(outWidth) * height, 0.f);
    for (int y = 0; y < height; ++y) {
        const float* row = in.data() + static_cast<std::size_t>(y) * inWidth;
        float* o = out->data() + static_cast<std::size_t>(y) * outWidth;
        for (int x = 0; x < outWidth; ++x) {
            double acc = 0.0;
            for (std::size_t t = 0; t < k.size(); ++t) acc += k[t] * row[x + static_cast<int>(t)];
            o[x] = static_cast<float>(acc);
        }
    }
}

std::vector<float> transpose(const std::vector<float>& in, int width, int height) {
    std::vector<float> out(in.size());
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) out[static_cast<std::size_t>(x) * height + y] = in[static_cast<std::size_t>(y) * width + x];
    return out;
}

// Blurs [in] (width + 2r) x (height + 2r), padded by the kernel's reach r, to width x height.
std::vector<float> blurPadded(const std::vector<float>& in, int width, int height, const std::vector<double>& k) {
    const int r = static_cast<int>(k.size() / 2);
    std::vector<float> rows;
    convolveRows(in, width + 2 * r, height + 2 * r, k, width, &rows);  // (height + 2r) x width
    const std::vector<float> t = transpose(rows, width, height + 2 * r);  // width x (height + 2r)
    std::vector<float> cols;
    convolveRows(t, height + 2 * r, width, k, height, &cols);  // width x height (transposed)
    return transpose(cols, height, width);
}

double brushInner(double radius, double hardness, double scale) {
    const double r = radius * scale;
    const double soft = r > 1.0 ? 1.0 / r : 1.0;
    return std::max(0.0, std::min(hardness, 1.0 - soft));
}

double smoothstep(double t) {
    t = std::clamp(t, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

void paintStroke(const MaskPaint& p, int x0, int y0, int width, int height, double scale, float* out) {
    const std::size_t n = p.points.size() / 2;
    if (n == 0 || p.radius <= 0.0) return;
    std::vector<double> best(static_cast<std::size_t>(width) * height, std::numeric_limits<double>::infinity());
    const double r = p.radius;
    const std::size_t segments = n > 1 ? n - 1 : 1;
    for (std::size_t s = 0; s < segments; ++s) {
        const double ax = p.points[2 * s], ay = p.points[2 * s + 1];
        const double bx = n > 1 ? p.points[2 * s + 2] : ax, by = n > 1 ? p.points[2 * s + 3] : ay;
        const double dx = bx - ax, dy = by - ay, ll = dx * dx + dy * dy;
        // Buffer pixels whose centre is within r of the segment's box.
        const int i0 = std::max(0, static_cast<int>(std::floor((std::min(ax, bx) - r) * scale - 0.5)) - x0);
        const int i1 = std::min(width, static_cast<int>(std::ceil((std::max(ax, bx) + r) * scale - 0.5)) - x0 + 1);
        const int j0 = std::max(0, static_cast<int>(std::floor((std::min(ay, by) - r) * scale - 0.5)) - y0);
        const int j1 = std::min(height, static_cast<int>(std::ceil((std::max(ay, by) + r) * scale - 0.5)) - y0 + 1);
        for (int j = j0; j < j1; ++j) {
            const double y = (y0 + j + 0.5) / scale;
            for (int i = i0; i < i1; ++i) {
                const double x = (x0 + i + 0.5) / scale;
                double t = 0.0;
                if (ll > 0.0) t = std::clamp(((x - ax) * dx + (y - ay) * dy) / ll, 0.0, 1.0);
                const double d = std::hypot(x - (ax + t * dx), y - (ay + t * dy));
                double& b = best[static_cast<std::size_t>(j) * width + i];
                if (d < b) b = d;
            }
        }
    }
    const double inner = brushInner(r, p.hardness, scale);
    const double amount = p.opacity / 100.0;
    for (std::size_t q = 0; q < best.size(); ++q) {
        if (!std::isfinite(best[q])) continue;
        const double d = best[q] / r;
        const double cov = d <= inner ? 1.0 : 1.0 - smoothstep((d - inner) / std::max(1.0 - inner, 1e-12));
        if (cov <= 0.0) continue;
        out[q] = static_cast<float>(out[q] + (p.value - out[q]) * cov * amount);
    }
}

struct Raster {
    std::shared_ptr<const std::vector<float>> values;
    int width = 0, height = 0;
};
std::mutex gRasterMutex;
std::unordered_map<std::string, Raster> gRasters;

// Replaces the mask with a saved raster covering the document, bilinear between its pixel
// centres (clamped at its edges).
void paintRaster(const MaskPaint& p, int x0, int y0, int width, int height, double scale, float* out) {
    Raster r;
    {
        std::lock_guard<std::mutex> lock(gRasterMutex);
        auto it = gRasters.find(p.source);
        if (it == gRasters.end()) return;  // not registered: the mask keeps what it had
        r = it->second;
    }
    if (r.width <= 0 || r.height <= 0 || p.docWidth <= 0.0 || p.docHeight <= 0.0) return;
    const std::vector<float>& v = *r.values;
    const double sx = r.width / p.docWidth, sy = r.height / p.docHeight;
    for (int j = 0; j < height; ++j) {
        const double fy = std::clamp((y0 + j + 0.5) / scale * sy - 0.5, 0.0, r.height - 1.0);
        const int ya = static_cast<int>(fy), yb = std::min(ya + 1, r.height - 1);
        const double ty = fy - ya;
        for (int i = 0; i < width; ++i) {
            const double fx = std::clamp((x0 + i + 0.5) / scale * sx - 0.5, 0.0, r.width - 1.0);
            const int xa = static_cast<int>(fx), xb = std::min(xa + 1, r.width - 1);
            const double tx = fx - xa;
            auto at = [&](int x, int y) { return static_cast<double>(v[static_cast<std::size_t>(y) * r.width + x]); };
            const double top = at(xa, ya) + (at(xb, ya) - at(xa, ya)) * tx;
            const double bottom = at(xa, yb) + (at(xb, yb) - at(xa, yb)) * tx;
            out[static_cast<std::size_t>(j) * width + i] = static_cast<float>(top + (bottom - top) * ty);
        }
    }
}

// A guided raster (Stage 16): joint bilateral upsampling (smart_select.h's weights: sigma_s 1
// raster px, sigma_r 0.1, reach 2) against sqrt(rgb) of [guideRgba], with the raster's guide
// planes as the low-resolution colours. Bilinear when the guide is missing.
bool paintGuidedRaster(const MaskPaint& p, int x0, int y0, int width, int height, double scale, float* out,
                       const float* guideRgba) {
    Raster r, g;
    {
        std::lock_guard<std::mutex> lock(gRasterMutex);
        auto it = gRasters.find(p.source);
        auto gt = gRasters.find(p.guide);
        if (it == gRasters.end() || gt == gRasters.end()) return false;
        r = it->second;
        g = gt->second;
    }
    if (r.width <= 0 || r.height <= 0 || g.width != r.width || g.height != 3 * r.height || p.docWidth <= 0.0 || p.docHeight <= 0.0)
        return false;
    const std::vector<float>& v = *r.values;
    const float* planes = g.values->data();
    const std::size_t plane = static_cast<std::size_t>(r.width) * r.height;
    const double sx = r.width / p.docWidth, sy = r.height / p.docHeight;
    constexpr double kSigmaS = 1.0, kSigmaR = 0.1;
    constexpr int kReach = 2;
    for (int j = 0; j < height; ++j) {
        const double vv = (y0 + j + 0.5) / scale * sy - 0.5;
        const int fv = static_cast<int>(std::floor(vv));
        for (int i = 0; i < width; ++i) {
            const double uu = (x0 + i + 0.5) / scale * sx - 0.5;
            const int fu = static_cast<int>(std::floor(uu));
            const float* px = guideRgba + 4 * (static_cast<std::size_t>(j) * width + i);
            double gp[3];
            for (int c = 0; c < 3; ++c) gp[c] = std::sqrt(std::clamp(static_cast<double>(px[c]), 0.0, 1.0));
            double num = 0.0, den = 0.0;
            for (int ky = -kReach; ky <= kReach; ++ky) {
                const int qy = fv + ky;
                const double dy = qy - vv;
                if (qy < 0 || qy >= r.height || std::abs(dy) > kReach) continue;
                for (int kx = -kReach; kx <= kReach; ++kx) {
                    const int qx = fu + kx;
                    const double dx = qx - uu;
                    if (qx < 0 || qx >= r.width || std::abs(dx) > kReach) continue;
                    const std::size_t q = static_cast<std::size_t>(qy) * r.width + qx;
                    const double d0 = gp[0] - planes[q], d1 = gp[1] - planes[plane + q], d2 = gp[2] - planes[2 * plane + q];
                    const double w = std::exp(-(dx * dx + dy * dy) / (2 * kSigmaS * kSigmaS)) *
                                     std::exp(-(d0 * d0 + d1 * d1 + d2 * d2) / (2 * kSigmaR * kSigmaR));
                    num += w * v[q];
                    den += w;
                }
            }
            if (den > 0.0) out[static_cast<std::size_t>(j) * width + i] = static_cast<float>(num / den);
        }
    }
    return true;
}

void paintGradient(const MaskPaint& p, int x0, int y0, int width, int height, double scale, float* out) {
    const double dx = p.x1 - p.x0, dy = p.y1 - p.y0, ll = dx * dx + dy * dy;
    for (int j = 0; j < height; ++j) {
        const double y = (y0 + j + 0.5) / scale;
        for (int i = 0; i < width; ++i) {
            const double x = (x0 + i + 0.5) / scale;
            const double t = ll > 0.0 ? std::clamp(((x - p.x0) * dx + (y - p.y0) * dy) / ll, 0.0, 1.0) : 1.0;
            out[static_cast<std::size_t>(j) * width + i] = static_cast<float>(p.from + (p.to - p.from) * t);
        }
    }
}

// --- Local mask components (Stage 17; tools/golden/local_mask_reference.py) -------------------

constexpr double kAutoMaskDeltaE = 20.0;  // the auto-mask's colour similarity reaches 0 here
constexpr double kAutoMaskEps = 1e-3;

double ramp(double v, double lo, double hi, double s) {
    if (s <= 0.0) return v >= lo && v <= hi ? 1.0 : 0.0;
    return smoothstep((v - lo + s) / s) * (1.0 - smoothstep((v - hi) / s));
}

// OKLab x 100 of every pixel of [rgba].
std::vector<float> oklab100(const float* rgba, std::size_t pixels) {
    std::vector<float> lab(pixels * 3);
    for (std::size_t p = 0; p < pixels; ++p) {
        colorops::toOklab(rgba + 4 * p, lab.data() + 3 * p);
        for (int c = 0; c < 3; ++c) lab[3 * p + c] *= 100.f;
    }
    return lab;
}

double deltaE(const float* lab, const double* to) {
    const double a = lab[0] - to[0], b = lab[1] - to[1], c = lab[2] - to[2];
    return std::sqrt(a * a + b * b + c * c);
}

void linearComponent(const MaskComponent& c, int x0, int y0, int width, int height, double scale, float* out) {
    MaskPaint g;
    g.x0 = c.x0;
    g.y0 = c.y0;
    g.x1 = c.x1;
    g.y1 = c.y1;
    g.from = 1.0;
    g.to = 0.0;
    paintGradient(g, x0, y0, width, height, scale, out);
}

void radialComponent(const MaskComponent& c, int x0, int y0, int width, int height, double scale, float* out) {
    const double a = c.angle * 3.14159265358979323846 / 180.0, ca = std::cos(a), sa = std::sin(a);
    const double rx = std::max(c.rx, 1e-9), ry = std::max(c.ry, 1e-9);
    const double f = std::clamp(c.feather / 100.0, 0.0, 1.0);
    for (int j = 0; j < height; ++j) {
        const double py = (y0 + j + 0.5) / scale - c.cy;
        for (int i = 0; i < width; ++i) {
            const double px = (x0 + i + 0.5) / scale - c.cx;
            const double u = (ca * px + sa * py) / rx, v = (-sa * px + ca * py) / ry;
            const double d = std::sqrt(u * u + v * v);
            const double m = f <= 0.0 ? (d <= 1.0 ? 1.0 : 0.0) : 1.0 - smoothstep((d - (1.0 - f)) / f);
            out[static_cast<std::size_t>(j) * width + i] = static_cast<float>(m);
        }
    }
}

// The strokes over 0; with auto-mask times the guided-filtered colour similarity to the
// painting strokes' colours (needs [image], the region's pixels).
void brushComponent(const MaskComponent& c, int x0, int y0, int width, int height, double scale, float* out,
                    const float* image) {
    const std::size_t n = static_cast<std::size_t>(width) * height;
    std::fill(out, out + n, 0.f);
    for (const MaskPaint& p : c.strokes) paintStroke(p, x0, y0, width, height, scale, out);
    if (!c.autoMask || image == nullptr) return;
    const std::vector<float> lab = oklab100(image, n);
    std::vector<float> sim(n, 0.f);
    double radius = 0.0;
    for (const MaskPaint& p : c.strokes) {
        radius = std::max(radius, p.radius);
        if (p.value <= 0.0 || p.color.size() < 3) continue;
        for (std::size_t q = 0; q < n; ++q) {
            const double v = std::clamp(1.0 - deltaE(lab.data() + 3 * q, p.color.data()) / kAutoMaskDeltaE, 0.0, 1.0);
            sim[q] = std::max(sim[q], static_cast<float>(v));
        }
    }
    // Python's round() (half to even), as the reference.
    const int r = std::max(1, static_cast<int>(std::nearbyint(radius * scale / 4.0)));
    std::vector<float> matte(n);
    smart::guidedMatte(sim.data(), smart::guide(image, width, height), width, height, r, kAutoMaskEps, matte.data());
    for (std::size_t q = 0; q < n; ++q) out[q] *= std::clamp(matte[q], 0.f, 1.f);
}

void colorRangeComponent(const MaskComponent& c, std::size_t n, float* out, const float* image) {
    const std::vector<float> lab = oklab100(image, n);
    const double fuzz = std::max(c.fuzziness, 1e-6);
    for (std::size_t q = 0; q < n; ++q) {
        double best = std::numeric_limits<double>::infinity();
        for (std::size_t k = 0; k + 2 < c.colors.size(); k += 3) best = std::min(best, deltaE(lab.data() + 3 * q, c.colors.data() + k));
        out[q] = static_cast<float>(std::clamp(1.0 - best / fuzz, 0.0, 1.0));
    }
}

void rasterComponent(const MaskComponent& c, int x0, int y0, int width, int height, double scale, float* out,
                     const float* image) {
    std::fill(out, out + static_cast<std::size_t>(width) * height, 0.f);
    MaskPaint p;
    p.kind = "raster";
    p.source = c.source;
    p.guide = c.guide;
    p.docWidth = c.docWidth;
    p.docHeight = c.docHeight;
    const bool guided = image != nullptr && !p.guide.empty() && paintGuidedRaster(p, x0, y0, width, height, scale, out, image);
    if (!guided) paintRaster(p, x0, y0, width, height, scale, out);
}

}  // namespace

bool componentReadsImage(const MaskComponent& c) {
    return c.kind == "color_range" || c.kind == "luminance_range" || (c.kind == "brush" && c.autoMask) ||
           ((c.kind == "raster" || c.kind == "depth_range") && !c.guide.empty());
}

bool readsImage(const LayerMask& mask) { return std::any_of(mask.components.begin(), mask.components.end(), componentReadsImage); }

void evaluateComponent(const MaskComponent& c, int x0, int y0, int width, int height, double scale, float* out,
                       const float* image) {
    const std::size_t n = static_cast<std::size_t>(width) * height;
    if (c.kind == "linear") {
        linearComponent(c, x0, y0, width, height, scale, out);
    } else if (c.kind == "radial") {
        radialComponent(c, x0, y0, width, height, scale, out);
    } else if (c.kind == "brush") {
        brushComponent(c, x0, y0, width, height, scale, out, image);
    } else if (c.kind == "color_range" && image != nullptr) {
        colorRangeComponent(c, n, out, image);
    } else if (c.kind == "luminance_range" && image != nullptr) {
        for (std::size_t q = 0; q < n; ++q) {
            float lab[3];
            colorops::toOklab(image + 4 * q, lab);
            out[q] = static_cast<float>(ramp(lab[0], c.lo, c.hi, c.smooth));
        }
    } else if (c.kind == "depth_range") {
        rasterComponent(c, x0, y0, width, height, scale, out, image);
        for (std::size_t q = 0; q < n; ++q) out[q] = static_cast<float>(ramp(out[q], c.lo, c.hi, c.smooth));
    } else if (c.kind == "raster") {
        rasterComponent(c, x0, y0, width, height, scale, out, image);
    } else {
        std::fill(out, out + n, 0.f);  // unknown, or it needs the image and has none: selects nothing
    }
    if (c.invert)
        for (std::size_t q = 0; q < n; ++q) out[q] = 1.f - out[q];
}

float combine(float m, float a, const std::string& mode) {
    if (mode == "subtract") return std::min(m, 1.f - a);
    if (mode == "intersect") return std::min(m, a);
    return std::max(m, a);
}

void rasterizeComponents(const LayerMask& mask, int x0, int y0, int width, int height, double scale, float* out,
                         const float* image) {
    const std::size_t n = static_cast<std::size_t>(width) * height;
    std::fill(out, out + n, 0.f);
    std::vector<float> a(n);
    for (const MaskComponent& c : mask.components) {
        evaluateComponent(c, x0, y0, width, height, scale, a.data(), image);
        for (std::size_t q = 0; q < n; ++q) out[q] = combine(out[q], a[q], c.mode);
    }
}

void registerRaster(const std::string& name, std::shared_ptr<const std::vector<float>> values, int width, int height) {
    std::lock_guard<std::mutex> lock(gRasterMutex);
    gRasters[name] = {std::move(values), width, height};
}

void unregisterRaster(const std::string& name) {
    std::lock_guard<std::mutex> lock(gRasterMutex);
    gRasters.erase(name);
}

bool findRaster(const std::string& name, std::shared_ptr<const std::vector<float>>* values, int* width, int* height) {
    std::lock_guard<std::mutex> lock(gRasterMutex);
    auto it = gRasters.find(name);
    if (it == gRasters.end()) return false;
    *values = it->second.values;
    *width = it->second.width;
    *height = it->second.height;
    return true;
}

bool hasRaster(const std::string& name) {
    std::lock_guard<std::mutex> lock(gRasterMutex);
    return gRasters.count(name) != 0;
}

void gaussianBlur(const float* in, int width, int height, double sigma, float* out) {
    const std::vector<double> k = gaussianKernel(sigma);
    if (k.size() <= 1) {
        std::copy(in, in + static_cast<std::size_t>(width) * height, out);
        return;
    }
    const int r = static_cast<int>(k.size() / 2);
    std::vector<float> padded(static_cast<std::size_t>(width + 2 * r) * (height + 2 * r));
    for (int j = 0; j < height + 2 * r; ++j)
        for (int i = 0; i < width + 2 * r; ++i)
            padded[static_cast<std::size_t>(j) * (width + 2 * r) + i] =
                in[static_cast<std::size_t>(reflect101(j - r, height)) * width + reflect101(i - r, width)];
    const std::vector<float> blurred = blurPadded(padded, width, height, k);
    std::copy(blurred.begin(), blurred.end(), out);
}

void rasterize(const LayerMask& mask, int x0, int y0, int width, int height, double scale, float* out,
               const float* guideRgba) {
    if (!mask.components.empty()) {
        rasterizeComponents(mask, x0, y0, width, height, scale, out, guideRgba);
        return;
    }
    std::fill(out, out + static_cast<std::size_t>(width) * height, static_cast<float>(mask.base));
    for (const MaskPaint& p : mask.paint) {
        if (p.kind == "gradient") {
            paintGradient(p, x0, y0, width, height, scale, out);
        } else if (p.kind == "raster") {
            const bool guided = guideRgba != nullptr && !p.guide.empty() &&
                                paintGuidedRaster(p, x0, y0, width, height, scale, out, guideRgba);
            if (!guided) paintRaster(p, x0, y0, width, height, scale, out);
        } else if (p.kind == "stroke") {
            paintStroke(p, x0, y0, width, height, scale, out);
        }
    }
}

void render(const LayerMask& mask, const MaskRegion& region, MaskTiles* out, const float* guideRgba) {
    const int w = region.width, h = region.height;
    out->reset(w, h);
    if (w <= 0 || h <= 0) return;
    const int docW = std::max(region.docWidth, region.originX + w);
    const int docH = std::max(region.docHeight, region.originY + h);
    const std::vector<double> k = gaussianKernel(mask.feather * region.scale);
    const int r = k.size() > 1 ? static_cast<int>(k.size() / 2) : 0;
    // The region plus the blur's reach, reflected (101) into the document.
    auto range = [](int from, int to, int n, int* lo, int* hi) {
        *lo = n;
        *hi = 0;
        for (int i = from; i < to; ++i) {
            const int q = reflect101(i, n);
            *lo = std::min(*lo, q);
            *hi = std::max(*hi, q + 1);
        }
    };
    int xlo, xhi, ylo, yhi;
    range(region.originX - r, region.originX + w + r, docW, &xlo, &xhi);
    range(region.originY - r, region.originY + h + r, docH, &ylo, &yhi);
    // The guide covers exactly the region, so it is used only without a feather apron.
    const bool exact = r == 0 && xlo == region.originX && ylo == region.originY && xhi - xlo == w && yhi - ylo == h;
    if (!exact && guideRgba != nullptr && readsImage(mask)) {
        // Stage 17: components that read the image are evaluated on the region only (its pixels
        // are all there is); the feather's reach outside it repeats the region's edge. Exports
        // render layers with an apron (layerApron) so the difference is cropped away.
        xlo = region.originX;
        ylo = region.originY;
        xhi = xlo + w;
        yhi = ylo + h;
    }
    const bool clampToRegion = xlo == region.originX && ylo == region.originY && xhi - xlo == w && yhi - ylo == h && r > 0;
    std::vector<float> raw(static_cast<std::size_t>(xhi - xlo) * (yhi - ylo));
    const bool withGuide = exact || (clampToRegion && readsImage(mask));
    rasterize(mask, xlo, ylo, xhi - xlo, yhi - ylo, region.scale, raw.data(), withGuide ? guideRgba : nullptr);
    const int pw = w + 2 * r, ph = h + 2 * r;
    std::vector<float> padded(static_cast<std::size_t>(pw) * ph);
    for (int j = 0; j < ph; ++j) {
        const int sy = std::clamp(reflect101(region.originY - r + j, docH) - ylo, 0, yhi - ylo - 1);
        for (int i = 0; i < pw; ++i) {
            const int sx = std::clamp(reflect101(region.originX - r + i, docW) - xlo, 0, xhi - xlo - 1);
            const float v = raw[static_cast<std::size_t>(sy) * (xhi - xlo) + sx];
            padded[static_cast<std::size_t>(j) * pw + i] = mask.inverted ? 1.f - v : v;
        }
    }
    const std::vector<float> m = r > 0 ? blurPadded(padded, w, h, k) : padded;
    const float density = static_cast<float>(mask.density / 100.0);
    for (int j = 0; j < h; ++j)
        for (int i = 0; i < w; ++i) out->set(i, j, 1.f - density * (1.f - m[static_cast<std::size_t>(j) * w + i]));
}

}  // namespace lo::masks
