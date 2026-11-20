// Layer masks (Stage 14); see masks.h and tools/golden/blend_reference.py.
#include "lineover/masks.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

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

}  // namespace

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

void rasterize(const LayerMask& mask, int x0, int y0, int width, int height, double scale, float* out) {
    std::fill(out, out + static_cast<std::size_t>(width) * height, static_cast<float>(mask.base));
    for (const MaskPaint& p : mask.paint) {
        if (p.kind == "gradient") {
            paintGradient(p, x0, y0, width, height, scale, out);
        } else if (p.kind == "stroke") {
            paintStroke(p, x0, y0, width, height, scale, out);
        }
    }
}

void render(const LayerMask& mask, const MaskRegion& region, MaskTiles* out) {
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
    std::vector<float> raw(static_cast<std::size_t>(xhi - xlo) * (yhi - ylo));
    rasterize(mask, xlo, ylo, xhi - xlo, yhi - ylo, region.scale, raw.data());
    const int pw = w + 2 * r, ph = h + 2 * r;
    std::vector<float> padded(static_cast<std::size_t>(pw) * ph);
    for (int j = 0; j < ph; ++j) {
        const int sy = reflect101(region.originY - r + j, docH) - ylo;
        for (int i = 0; i < pw; ++i) {
            const int sx = reflect101(region.originX - r + i, docW) - xlo;
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
