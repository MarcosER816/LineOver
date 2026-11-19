// Export pixel steps and ICC profiles (Stage 12); see output.h and export_reference.py.
#include "lineover/output.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include "lineover/color.h"

namespace lo::output {
namespace {

using Vec3 = std::array<double, 3>;
using M3 = std::array<double, 9>;  // row-major

constexpr double kPi = 3.14159265358979323846;
constexpr int kLanczos = 3;

// Luminance weights of the working space (reference.LUMA).
constexpr float kLuma[3] = {0.2627f, 0.6780f, 0.0593f};

M3 mul(const M3& a, const M3& b) {
    M3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            for (int k = 0; k < 3; ++k) r[3 * i + j] += a[3 * i + k] * b[3 * k + j];
    return r;
}

Vec3 mul(const M3& a, const Vec3& v) {
    Vec3 r{};
    for (int i = 0; i < 3; ++i)
        for (int k = 0; k < 3; ++k) r[i] += a[3 * i + k] * v[k];
    return r;
}

M3 inverse(const M3& m) {
    const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    return {A / det,
            -(b * i - c * h) / det,
            (b * f - c * e) / det,
            B / det,
            (a * i - c * g) / det,
            -(a * f - c * d) / det,
            C / det,
            -(a * h - b * g) / det,
            (a * e - b * d) / det};
}

Vec3 xyzOf(double x, double y) { return {x / y, 1.0, (1.0 - x - y) / y}; }

constexpr double kD65[2] = {0.3127, 0.3290};
constexpr double kD50Pcs[3] = {0.9642, 1.0, 0.8249};

// Normalised primary matrix (export_reference.rgb_to_xyz_d65).
M3 rgbToXyz(Space space) {
    static const double kPrimaries[3][6] = {
        {0.640, 0.330, 0.300, 0.600, 0.150, 0.060},
        {0.680, 0.320, 0.265, 0.690, 0.150, 0.060},
        {0.708, 0.292, 0.170, 0.797, 0.131, 0.046},
    };
    const double* p = kPrimaries[static_cast<int>(space)];
    const Vec3 r = xyzOf(p[0], p[1]), g = xyzOf(p[2], p[3]), b = xyzOf(p[4], p[5]);
    const M3 P = {r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]};
    const Vec3 s = mul(inverse(P), xyzOf(kD65[0], kD65[1]));
    M3 out = P;
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) out[3 * i + j] *= s[j];
    return out;
}

M3 chadD65ToD50() {
    const M3 bradford = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
    const Vec3 src = mul(bradford, xyzOf(kD65[0], kD65[1]));
    const Vec3 dst = mul(bradford, Vec3{kD50Pcs[0], kD50Pcs[1], kD50Pcs[2]});
    const M3 scale = {dst[0] / src[0], 0, 0, 0, dst[1] / src[1], 0, 0, 0, dst[2] / src[2]};
    return mul(inverse(bradford), mul(scale, bradford));
}

// Parametric curves (ICC type 3): g, a, b, c, d.
constexpr double kSrgbCurve[5] = {2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045};
constexpr double kBt2020Curve[5] = {1 / 0.45, 1 / 1.0993, 0.0993 / 1.0993, 1 / 4.5, 0.08145};

float bt2020Oetf(float x) {
    constexpr double beta = kBt2020Curve[4] * kBt2020Curve[3];
    if (x >= beta) return static_cast<float>((std::pow(static_cast<double>(x), 1.0 / kBt2020Curve[0]) - kBt2020Curve[2]) / kBt2020Curve[1]);
    return static_cast<float>(x / kBt2020Curve[3]);
}

float srgbEncode(float x) {
    if (x <= 0.0031308f) return 12.92f * x;
    return static_cast<float>(1.055 * std::pow(static_cast<double>(x), 1.0 / 2.4) - 0.055);
}

double lanczos(double t) {
    t = std::fabs(t);
    if (t == 0.0) return 1.0;
    if (t >= kLanczos) return 0.0;
    const double pt = kPi * t;
    return kLanczos * std::sin(pt) * std::sin(pt / kLanczos) / (pt * pt);
}

// The taps of one output sample along an axis of `n` input samples scaled to `size`.
struct Taps {
    std::vector<int> first;     // per output sample
    std::vector<int> count;
    std::vector<int> index;     // clamped input indices, concatenated
    std::vector<float> weight;  // normalised, concatenated
    std::vector<std::size_t> start;
};

Taps tapsFor(int n, int size) {
    Taps t;
    const double scale = static_cast<double>(n) / size;
    const double f = std::max(scale, 1.0);
    const double support = kLanczos * f;
    for (int o = 0; o < size; ++o) {
        const double centre = (o + 0.5) * scale - 0.5;
        const int first = static_cast<int>(std::floor(centre - support)) + 1;
        const int last = static_cast<int>(std::floor(centre + support));
        std::vector<double> w;
        double sum = 0.0;
        for (int i = first; i <= last; ++i) {
            w.push_back(lanczos((i - centre) / f));
            sum += w.back();
        }
        t.start.push_back(t.index.size());
        t.count.push_back(last - first + 1);
        for (int i = first; i <= last; ++i) {
            t.index.push_back(std::clamp(i, 0, n - 1));
            t.weight.push_back(static_cast<float>(w[static_cast<std::size_t>(i - first)] / sum));
        }
    }
    return t;
}


}  // namespace

void resize(const float* in, int width, int height, float* tmp, float* out, int outWidth, int outHeight) {
    const Taps hx = tapsFor(width, outWidth);
    for (int y = 0; y < height; ++y) {
        const float* src = in + static_cast<std::size_t>(y) * width * 4;
        float* dst = tmp + static_cast<std::size_t>(y) * outWidth * 4;
        for (int x = 0; x < outWidth; ++x) {
            double acc[4] = {0, 0, 0, 0};
            const std::size_t s = hx.start[static_cast<std::size_t>(x)];
            for (int k = 0; k < hx.count[static_cast<std::size_t>(x)]; ++k) {
                const float* p = src + static_cast<std::size_t>(hx.index[s + k]) * 4;
                const double w = hx.weight[s + k];
                for (int c = 0; c < 4; ++c) acc[c] += w * p[c];
            }
            for (int c = 0; c < 4; ++c) dst[4 * x + c] = static_cast<float>(acc[c]);
        }
    }
    const Taps vy = tapsFor(height, outHeight);
    const std::size_t rowFloats = static_cast<std::size_t>(outWidth) * 4;
    std::vector<double> acc(rowFloats);
    for (int y = 0; y < outHeight; ++y) {
        std::fill(acc.begin(), acc.end(), 0.0);
        const std::size_t s = vy.start[static_cast<std::size_t>(y)];
        for (int k = 0; k < vy.count[static_cast<std::size_t>(y)]; ++k) {
            const float* src = tmp + static_cast<std::size_t>(vy.index[s + k]) * rowFloats;
            const double w = vy.weight[s + k];
            for (std::size_t i = 0; i < rowFloats; ++i) acc[i] += w * src[i];
        }
        float* dst = out + static_cast<std::size_t>(y) * rowFloats;
        for (std::size_t i = 0; i < rowFloats; ++i) dst[i] = static_cast<float>(acc[i]);
    }
}

void sharpen(float* rgba, int width, int height, float amount) {
    constexpr int r = 3;
    constexpr double sigma = 0.7;
    constexpr int kBand = 128;
    constexpr float kMaxRatio = 4.f, kEps = 1e-6f;
    float w[2 * r + 1];
    {
        double sum = 0.0, tmp[2 * r + 1];
        for (int k = -r; k <= r; ++k) sum += tmp[k + r] = std::exp(-(k * k) / (2 * sigma * sigma));
        for (int k = 0; k <= 2 * r; ++k) w[k] = static_cast<float>(tmp[k] / sum);
    }
    auto lightnessOf = [&](int y, float* out) {
        const float* p = rgba + static_cast<std::size_t>(y) * width * 4;
        for (int x = 0; x < width; ++x, p += 4) out[x] = std::cbrt(std::max(kLuma[0] * p[0] + kLuma[1] * p[1] + kLuma[2] * p[2], 0.f));
    };
    const std::size_t W = static_cast<std::size_t>(width);
    std::vector<float> carry;  // original lightness of the r rows above the band (already sharpened)
    int carryFrom = 0;
    std::vector<float> L, H, row(W);
    for (int y0 = 0; y0 < height; y0 += kBand) {
        const int y1 = std::min(height, y0 + kBand);
        const int a = std::max(0, y0 - r), b = std::min(height, y1 + r);
        const int rows = b - a;
        L.assign(static_cast<std::size_t>(rows) * W, 0.f);
        for (int y = a; y < b; ++y) {
            float* dst = L.data() + static_cast<std::size_t>(y - a) * W;
            if (y < y0)
                std::memcpy(dst, carry.data() + static_cast<std::size_t>(y - carryFrom) * W, W * sizeof(float));
            else
                lightnessOf(y, dst);
        }
        H.assign(L.size(), 0.f);
        for (int y = 0; y < rows; ++y) {
            const float* src = L.data() + static_cast<std::size_t>(y) * W;
            float* dst = H.data() + static_cast<std::size_t>(y) * W;
            for (int x = 0; x < width; ++x) {
                float acc = 0.f;
                for (int k = -r; k <= r; ++k) acc += w[k + r] * src[std::clamp(x + k, 0, width - 1)];
                dst[x] = acc;
            }
        }
        // Keep the original lightness of this band's last r rows for the next band.
        carryFrom = std::max(y0, y1 - r);
        carry.assign(L.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(carryFrom - a) * W),
                     L.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(y1 - a) * W));
        for (int y = y0; y < y1; ++y) {
            for (int x = 0; x < width; ++x) {
                float acc = 0.f;
                for (int k = -r; k <= r; ++k) acc += w[k + r] * H[static_cast<std::size_t>(std::clamp(y + k, 0, height - 1) - a) * W + x];
                row[static_cast<std::size_t>(x)] = acc;
            }
            const float* l = L.data() + static_cast<std::size_t>(y - a) * W;
            float* p = rgba + static_cast<std::size_t>(y) * W * 4;
            for (int x = 0; x < width; ++x, p += 4) {
                const float lightness = l[x];
                if (lightness <= kEps) continue;
                const float sharp = std::max(lightness + amount * (lightness - row[static_cast<std::size_t>(x)]), 0.f);
                const float q = sharp / lightness;
                const float ratio = std::min(q * q * q, kMaxRatio);
                for (int c = 0; c < 3; ++c) p[c] *= ratio;
            }
        }
    }
}

void watermark(float* rgba, int width, int height, const std::uint8_t* mark, int markWidth, int markHeight, int x, int y,
               float opacity) {
    const color::Mat3& m = color::kSrgbToRec2020;
    for (int my = std::max(0, -y); my < markHeight && y + my < height; ++my) {
        for (int mx = std::max(0, -x); mx < markWidth && x + mx < width; ++mx) {
            const std::uint8_t* s = mark + (static_cast<std::size_t>(my) * markWidth + mx) * 4;
            if (s[3] == 0) continue;
            const float a = s[3] / 255.f;
            float lin[3];
            for (int c = 0; c < 3; ++c) {
                const float e = std::min(s[c] / 255.f / a, 1.f);
                lin[c] = e <= 0.04045f ? e / 12.92f : std::pow((e + 0.055f) / 1.055f, 2.4f);
            }
            float* p = rgba + (static_cast<std::size_t>(y + my) * width + (x + mx)) * 4;
            const float k = a * opacity;
            for (int c = 0; c < 3; ++c) {
                const float v = m.m[3 * c] * lin[0] + m.m[3 * c + 1] * lin[1] + m.m[3 * c + 2] * lin[2];
                p[c] = p[c] * (1.f - k) + v * k;
            }
        }
    }
}

void baseLinearRow(const float* rgba, int pixels, Space space, float* out) {
    if (space == Space::Rec2020) {
        for (int i = 0; i < pixels * 4; ++i) out[i] = (i % 4 == 3) ? rgba[i] : std::clamp(rgba[i], 0.f, 1.f);
        return;
    }
    color::gamutMap(rgba, out, static_cast<std::size_t>(pixels), space == Space::Srgb ? color::Display::Srgb : color::Display::DisplayP3);
}

namespace {
float encode(Space space, float linear) { return space == Space::Rec2020 ? bt2020Oetf(linear) : srgbEncode(linear); }
}  // namespace

void convertRow8(const float* rgba, int pixels, Space space, int channels, std::uint8_t* out) {
    std::vector<float> lin(static_cast<std::size_t>(pixels) * 4);
    baseLinearRow(rgba, pixels, space, lin.data());
    for (int i = 0; i < pixels; ++i) {
        for (int c = 0; c < 3; ++c) {
            const float e = std::clamp(encode(space, lin[4 * static_cast<std::size_t>(i) + c]), 0.f, 1.f);
            out[channels * i + c] = static_cast<std::uint8_t>(std::floor(e * 255.f + 0.5f));
        }
        if (channels == 4) out[4 * i + 3] = 255;
    }
}

void convertRow16(const float* rgba, int pixels, Space space, std::uint16_t* out) {
    std::vector<float> lin(static_cast<std::size_t>(pixels) * 4);
    baseLinearRow(rgba, pixels, space, lin.data());
    for (int i = 0; i < pixels; ++i) {
        for (int c = 0; c < 3; ++c) {
            const double e = std::clamp(static_cast<double>(encode(space, lin[4 * static_cast<std::size_t>(i) + c])), 0.0, 1.0);
            out[3 * i + c] = static_cast<std::uint16_t>(std::floor(e * 65535.0 + 0.5));
        }
    }
}

float rebuildGainmap(const float* rgba, int width, int height, Space space, int factor, std::vector<std::uint8_t>* out,
                     int* outWidth, int* outHeight) {
    const M3 hdrXyz = rgbToXyz(Space::Rec2020), sdrXyz = rgbToXyz(space);
    const int gw = (width + factor - 1) / factor, gh = (height + factor - 1) / factor;
    std::vector<double> sums(static_cast<std::size_t>(gw) * gh, 0.0);
    std::vector<int> counts(sums.size(), 0);
    std::vector<float> base(static_cast<std::size_t>(width) * 4);
    for (int y = 0; y < height; ++y) {
        const float* p = rgba + static_cast<std::size_t>(y) * width * 4;
        baseLinearRow(p, width, space, base.data());
        for (int x = 0; x < width; ++x) {
            const float* h = p + 4 * x;
            const float* s = base.data() + 4 * x;
            const double yh = std::max(hdrXyz[3] * h[0] + hdrXyz[4] * h[1] + hdrXyz[5] * h[2], 0.0);
            const double ys = sdrXyz[3] * s[0] + sdrXyz[4] * s[1] + sdrXyz[5] * s[2];
            const double g = std::log2(std::max((yh + kGainmapEpsilon) / (ys + kGainmapEpsilon), 1.0));
            const std::size_t b = static_cast<std::size_t>(y / factor) * gw + static_cast<std::size_t>(x / factor);
            sums[b] += g;
            ++counts[b];
        }
    }
    double top = 0.0;
    for (std::size_t i = 0; i < sums.size(); ++i) {
        sums[i] /= counts[i];
        top = std::max(top, sums[i]);
    }
    *outWidth = gw;
    *outHeight = gh;
    out->assign(sums.size(), 0);
    if (top < 1e-4) return 0.f;
    for (std::size_t i = 0; i < sums.size(); ++i) (*out)[i] = static_cast<std::uint8_t>(std::floor(std::clamp(sums[i] / top, 0.0, 1.0) * 255.0 + 0.5));
    return static_cast<float>(std::exp2(top));
}

// --- ICC -----------------------------------------------------------------------------------

namespace {

void put32(std::vector<std::uint8_t>& b, std::uint32_t v) {
    for (int s = 24; s >= 0; s -= 8) b.push_back(static_cast<std::uint8_t>(v >> s));
}
void put16(std::vector<std::uint8_t>& b, std::uint16_t v) {
    b.push_back(static_cast<std::uint8_t>(v >> 8));
    b.push_back(static_cast<std::uint8_t>(v));
}
void putSig(std::vector<std::uint8_t>& b, const char* s) { b.insert(b.end(), s, s + 4); }
void putS15f16(std::vector<std::uint8_t>& b, double v) {
    put32(b, static_cast<std::uint32_t>(static_cast<std::int32_t>(std::floor(v * 65536.0 + 0.5))));
}

std::vector<std::uint8_t> mluc(const std::string& ascii) {
    std::vector<std::uint8_t> b;
    putSig(b, "mluc");
    put32(b, 0);
    put32(b, 1);
    put32(b, 12);
    putSig(b, "enUS");
    put32(b, static_cast<std::uint32_t>(ascii.size() * 2));
    put32(b, 28);
    for (char c : ascii) put16(b, static_cast<std::uint16_t>(static_cast<unsigned char>(c)));
    return b;
}

std::vector<std::uint8_t> xyzTag(double x, double y, double z) {
    std::vector<std::uint8_t> b;
    putSig(b, "XYZ ");
    put32(b, 0);
    putS15f16(b, x);
    putS15f16(b, y);
    putS15f16(b, z);
    return b;
}

}  // namespace

std::vector<std::uint8_t> iccProfile(Space space) {
    static const char* kNames[3] = {"LineOver sRGB", "LineOver Display P3", "LineOver Rec. 2020"};
    const M3 chad = chadD65ToD50();
    const M3 colorants = mul(chad, rgbToXyz(space));
    const double* curve = space == Space::Rec2020 ? kBt2020Curve : kSrgbCurve;
    std::vector<std::uint8_t> para;
    putSig(para, "para");
    put32(para, 0);
    put16(para, 3);
    put16(para, 0);
    for (int i = 0; i < 5; ++i) putS15f16(para, curve[i]);
    std::vector<std::uint8_t> chadTag;
    putSig(chadTag, "sf32");
    put32(chadTag, 0);
    for (double v : chad) putS15f16(chadTag, v);

    struct Tag {
        const char* sig;
        std::vector<std::uint8_t> body;
    };
    const std::vector<Tag> tags = {
        {"desc", mluc(kNames[static_cast<int>(space)])},
        {"cprt", mluc("No copyright, use freely")},
        {"wtpt", xyzTag(kD50Pcs[0], kD50Pcs[1], kD50Pcs[2])},
        {"chad", chadTag},
        {"rXYZ", xyzTag(colorants[0], colorants[3], colorants[6])},
        {"gXYZ", xyzTag(colorants[1], colorants[4], colorants[7])},
        {"bXYZ", xyzTag(colorants[2], colorants[5], colorants[8])},
        {"rTRC", para},
        {"gTRC", para},
        {"bTRC", para},
    };
    const std::uint32_t tableStart = 128;
    const std::uint32_t dataStart = tableStart + 4 + 12 * static_cast<std::uint32_t>(tags.size());
    std::vector<std::uint8_t> data, table;
    std::vector<std::pair<const std::vector<std::uint8_t>*, std::uint32_t>> placed;
    put32(table, static_cast<std::uint32_t>(tags.size()));
    for (const Tag& t : tags) {
        std::uint32_t at = 0;
        for (const auto& [body, offset] : placed)
            if (*body == t.body) at = offset;
        if (at == 0) {
            at = dataStart + static_cast<std::uint32_t>(data.size());
            placed.emplace_back(&t.body, at);
            data.insert(data.end(), t.body.begin(), t.body.end());
            while (data.size() % 4 != 0) data.push_back(0);
        }
        putSig(table, t.sig);
        put32(table, at);
        put32(table, static_cast<std::uint32_t>(t.body.size()));
    }
    std::vector<std::uint8_t> h;
    put32(h, dataStart + static_cast<std::uint32_t>(data.size()));
    put32(h, 0);
    put32(h, 0x04300000);
    putSig(h, "mntr");
    putSig(h, "RGB ");
    putSig(h, "XYZ ");
    for (std::uint16_t v : {2026, 1, 1, 0, 0, 0}) put16(h, v);
    putSig(h, "acsp");
    h.resize(h.size() + 28, 0);  // platform, flags, manufacturer, model, attributes, intent
    putS15f16(h, kD50Pcs[0]);
    putS15f16(h, kD50Pcs[1]);
    putS15f16(h, kD50Pcs[2]);
    h.resize(128, 0);  // creator, profile ID (zero), reserved
    h.insert(h.end(), table.begin(), table.end());
    h.insert(h.end(), data.begin(), data.end());
    return h;
}

}  // namespace lo::output
