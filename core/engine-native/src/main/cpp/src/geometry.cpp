#include "lineover/geometry.h"

#include <algorithm>
#include <cmath>

#include "op_util.h"

namespace lo {
namespace {

double scalarOf(const OpParams& p, const char* key, double fallback) {
    for (const auto& [k, v] : p.params)
        if (k == key) return v;
    return fallback;
}

const std::vector<double>* arrayOf(const OpParams& p, const char* key) {
    for (const auto& [k, v] : p.arrays)
        if (k == key) return &v;
    return nullptr;
}

}  // namespace

bool geometryOf(const Graph& graph, Geometry* out) {
    if (graph.layers.empty()) return false;
    for (const OpParams& p : graph.layers.front().ops) {
        if (p.op != "geometry") continue;
        Geometry g;
        const std::vector<double>* m = arrayOf(p, "matrix");
        if (m == nullptr || m->size() != 9) return false;
        std::copy(m->begin(), m->end(), g.matrix);
        g.outputWidth = static_cast<int>(std::lround(scalarOf(p, "outputWidth", 0.0)));
        g.outputHeight = static_cast<int>(std::lround(scalarOf(p, "outputHeight", 0.0)));
        g.sourceWidth = static_cast<int>(std::lround(scalarOf(p, "sourceWidth", 0.0)));
        g.sourceHeight = static_cast<int>(std::lround(scalarOf(p, "sourceHeight", 0.0)));
        g.k1 = scalarOf(p, "k1", 0.0);
        g.k2 = scalarOf(p, "k2", 0.0);
        g.caRed = scalarOf(p, "caRed", 0.0);
        g.caBlue = scalarOf(p, "caBlue", 0.0);
        g.vignette = static_cast<float>(scalarOf(p, "vignette", 0.0));
        g.vignetteMid = static_cast<float>(scalarOf(p, "vignetteMid", 50.0));
        if (g.outputWidth <= 0 || g.outputHeight <= 0) return false;
        *out = g;
        return true;
    }
    return false;
}

void mapToSource(const Geometry& g, int sourceWidth, int sourceHeight, double u, double v, int channel, double* x, double* y) {
    const double* m = g.matrix;
    const double z = m[6] * u + m[7] * v + m[8];
    const double px = (m[0] * u + m[1] * v + m[2]) / z;
    const double py = (m[3] * u + m[4] * v + m[5]) / z;
    // Same arithmetic as reference.geometry_maps, lens or not (bit-identical positions).
    const double cx = sourceWidth / 2.0, cy = sourceHeight / 2.0;
    const double radius = 0.5 * std::hypot(static_cast<double>(sourceWidth), static_cast<double>(sourceHeight));
    const double qx = px - cx, qy = py - cy;
    const double r2 = (qx * qx + qy * qy) / (radius * radius);
    double s = 1.0 + g.k1 * r2 + g.k2 * r2 * r2;
    if (channel == 0) s *= 1.0 + g.caRed;
    if (channel == 2) s *= 1.0 + g.caBlue;
    *x = cx + qx * s;
    *y = cy + qy * s;
}

namespace {

bool invert3(const double m[9], double out[9]) {
    const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (std::fabs(det) < 1e-300) return false;
    out[0] = (m[4] * m[8] - m[5] * m[7]) / det;
    out[1] = (m[2] * m[7] - m[1] * m[8]) / det;
    out[2] = (m[1] * m[5] - m[2] * m[4]) / det;
    out[3] = (m[5] * m[6] - m[3] * m[8]) / det;
    out[4] = (m[0] * m[8] - m[2] * m[6]) / det;
    out[5] = (m[2] * m[3] - m[0] * m[5]) / det;
    out[6] = (m[3] * m[7] - m[4] * m[6]) / det;
    out[7] = (m[1] * m[6] - m[0] * m[7]) / det;
    out[8] = (m[0] * m[4] - m[1] * m[3]) / det;
    return true;
}

}  // namespace

bool sourceToOutput(const Geometry& g, int sourceWidth, int sourceHeight, double x, double y, double* ox, double* oy) {
    double inv[9];
    if (!invert3(g.matrix, inv)) return false;
    const double cx = sourceWidth / 2.0, cy = sourceHeight / 2.0;
    const double radius = 0.5 * std::hypot(static_cast<double>(sourceWidth), static_cast<double>(sourceHeight));
    double ux = x, uy = y;
    if (g.k1 != 0.0 || g.k2 != 0.0) {
        // r_d = r_u (1 + k1 r_u^2 + k2 r_u^4): Newton on r_u, then scale the vector.
        const double qx = (x - cx) / radius, qy = (y - cy) / radius;
        const double rd = std::hypot(qx, qy);
        if (rd > 0.0) {
            double ru = rd;
            for (int i = 0; i < 20; ++i) {
                const double r2 = ru * ru;
                const double f = ru * (1.0 + g.k1 * r2 + g.k2 * r2 * r2) - rd;
                const double df = 1.0 + 3.0 * g.k1 * r2 + 5.0 * g.k2 * r2 * r2;
                if (std::fabs(df) < 1e-9) break;
                ru -= f / df;
            }
            ux = cx + (x - cx) * ru / rd;
            uy = cy + (y - cy) * ru / rd;
        }
    }
    const double z = inv[6] * ux + inv[7] * uy + inv[8];
    if (z <= 0.0) return false;
    *ox = (inv[0] * ux + inv[1] * uy + inv[2]) / z * g.outputWidth;
    *oy = (inv[3] * ux + inv[4] * uy + inv[5]) / z * g.outputHeight;
    return true;
}

bool outputRectOf(const Geometry& g, int sourceWidth, int sourceHeight, const double sourceRect[4], double out[4]) {
    double minX = 1e30, minY = 1e30, maxX = -1e30, maxY = -1e30;
    constexpr int kSteps = 8;
    for (int side = 0; side < 4; ++side) {
        for (int k = 0; k <= kSteps; ++k) {
            const double t = static_cast<double>(k) / kSteps;
            const double fx = side == 0 ? t : side == 1 ? 1.0 : side == 2 ? 1.0 - t : 0.0;
            const double fy = side == 0 ? 0.0 : side == 1 ? t : side == 2 ? 1.0 : 1.0 - t;
            double ox, oy;
            if (!sourceToOutput(g, sourceWidth, sourceHeight, sourceRect[0] + fx * sourceRect[2], sourceRect[1] + fy * sourceRect[3], &ox,
                                &oy)) {
                // Part of the rect is behind the projection: draw it over the whole output.
                minX = minY = 0.0;
                maxX = g.outputWidth;
                maxY = g.outputHeight;
                side = 4;
                break;
            }
            minX = std::min(minX, ox);
            minY = std::min(minY, oy);
            maxX = std::max(maxX, ox);
            maxY = std::max(maxY, oy);
        }
    }
    // Chromatic aberration moves red / blue by a fraction of a percent: a wider margin.
    const double margin = 3.0 + (std::fabs(g.caRed) + std::fabs(g.caBlue)) * std::max(g.outputWidth, g.outputHeight);
    out[0] = std::max(0.0, minX - margin);
    out[1] = std::max(0.0, minY - margin);
    out[2] = std::min(static_cast<double>(g.outputWidth), maxX + margin) - out[0];
    out[3] = std::min(static_cast<double>(g.outputHeight), maxY + margin) - out[1];
    return out[2] > 0.0 && out[3] > 0.0;
}

double outputScaleAt(const Geometry& g, int sourceWidth, int sourceHeight, double u, double v) {
    const double du = 1.0 / g.outputWidth, dv = 1.0 / g.outputHeight;
    double x0, y0, x1, y1, x2, y2;
    mapToSource(g, sourceWidth, sourceHeight, u, v, 1, &x0, &y0);
    mapToSource(g, sourceWidth, sourceHeight, u + du, v, 1, &x1, &y1);
    mapToSource(g, sourceWidth, sourceHeight, u, v + dv, 1, &x2, &y2);
    // Source pixels per output pixel: the mean of the two axes; its inverse.
    const double s = 0.5 * (std::hypot(x1 - x0, y1 - y0) + std::hypot(x2 - x0, y2 - y0));
    return s > 0.0 ? 1.0 / s : 1.0;
}

void cubicWeights(float t, float w[4]) {
    constexpr float A = -0.75f;
    w[0] = ((A * (t + 1) - 5 * A) * (t + 1) + 8 * A) * (t + 1) - 4 * A;
    w[1] = ((A + 2) * t - (A + 3)) * t * t + 1;
    w[2] = ((A + 2) * (1 - t) - (A + 3)) * (1 - t) * (1 - t) + 1;
    w[3] = 1.f - w[0] - w[1] - w[2];
}

namespace {

// One channel at (sx, sy): pixel-centre coordinates of the whole (scaled) source, as
// OpenCV's float maps; the buffer starts at (s.x0, s.y0) of it. Border 0.
float sample(const WarpSource& s, float sx, float sy, int channel) {
    const float fx = std::floor(sx), fy = std::floor(sy);
    const long ix = static_cast<long>(fx) - s.x0, iy = static_cast<long>(fy) - s.y0;
    float wx[4], wy[4];
    cubicWeights(sx - fx, wx);
    cubicWeights(sy - fy, wy);
    float sum = 0.f;
    for (int j = 0; j < 4; ++j) {
        const long yy = iy - 1 + j;
        if (yy < 0 || yy >= s.height) continue;
        float row = 0.f;
        for (int i = 0; i < 4; ++i) {
            const long xx = ix - 1 + i;
            if (xx < 0 || xx >= s.width) continue;
            row += wx[i] * s.rgba[4 * (static_cast<std::size_t>(yy) * s.width + xx) + channel];
        }
        sum += wy[j] * row;
    }
    return sum;
}

}  // namespace

void warp(const Geometry& g, int sourceWidth, int sourceHeight, const WarpSource& source, double outputScale, int x0, int y0,
          int width, int height, float* out) {
    const double outW = g.outputWidth * outputScale;
    const double outH = g.outputHeight * outputScale;
    for (int j = 0; j < height; ++j) {
        for (int i = 0; i < width; ++i) {
            const double u = (x0 + i + 0.5) / outW;
            const double v = (y0 + j + 0.5) / outH;
            float* o = out + 4 * (static_cast<std::size_t>(j) * width + i);
            // Green (and alpha) at the geometry's own position; red and blue at theirs
            // when chromatic aberration scales them.
            const bool ca = g.caRed != 0.0 || g.caBlue != 0.0;
            for (int c = 0; c < 4; ++c) {
                const int mapChannel = c == 3 || !ca ? 1 : c;
                double x, y;
                mapToSource(g, sourceWidth, sourceHeight, u, v, mapChannel, &x, &y);
                // Full source pixels -> buffer pixel centres (float, as OpenCV's float maps).
                const float sx = static_cast<float>(x * source.scale - 0.5);
                const float sy = static_cast<float>(y * source.scale - 0.5);
                o[c] = sample(source, sx, sy, c);
            }
        }
    }
}

void footprint(const Geometry& g, int sourceWidth, int sourceHeight, int x0, int y0, int w, int h, int out[4]) {
    double minX = 1e30, minY = 1e30, maxX = -1e30, maxY = -1e30;
    constexpr int kSteps = 8;
    for (int sj = 0; sj <= kSteps; ++sj) {
        for (int si = 0; si <= kSteps; ++si) {
            const double u = (x0 + w * static_cast<double>(si) / kSteps) / g.outputWidth;
            const double v = (y0 + h * static_cast<double>(sj) / kSteps) / g.outputHeight;
            for (int c = 0; c < 3; ++c) {
                double x, y;
                mapToSource(g, sourceWidth, sourceHeight, u, v, c, &x, &y);
                minX = std::min(minX, x);
                minY = std::min(minY, y);
                maxX = std::max(maxX, x);
                maxY = std::max(maxY, y);
            }
        }
    }
    // Cubic taps reach 2 px; a 1 px margin more covers curvature between the samples.
    constexpr int kMargin = 3;
    const int ax = std::clamp(static_cast<int>(std::floor(minX)) - kMargin, 0, sourceWidth);
    const int ay = std::clamp(static_cast<int>(std::floor(minY)) - kMargin, 0, sourceHeight);
    const int bx = std::clamp(static_cast<int>(std::ceil(maxX)) + kMargin, 0, sourceWidth);
    const int by = std::clamp(static_cast<int>(std::ceil(maxY)) + kMargin, 0, sourceHeight);
    out[0] = ax;
    out[1] = ay;
    out[2] = std::max(0, bx - ax);
    out[3] = std::max(0, by - ay);
}

namespace {

class VignettingOp final : public Op {
public:
    VignettingOp(float amount, float midpoint, float aspect) : amount_(amount), mid_(midpoint / 100.f * 0.8f), aspect_(aspect) {}
    OpType type() const override { return OpType::Vignetting; }
    bool isIdentity() const override { return amount_ == 0.f; }
    void gpu(GpuOpContext& context) const override {
        context.setShader("uniform float u_v[3];",
                          "vec2 uv = u_rect.xy + v_uv * u_rect.zw;\n"
                          "vec2 q = (uv - 0.5) * vec2(u_v[2], 1.0);\n"
                          "float r = length(q) / (0.5 * length(vec2(u_v[2], 1.0)));\n"
                          "float f = clamp((r - u_v[1]) / (1.0 - u_v[1]), 0.0, 1.0);\n"
                          "return vec4(c.rgb * exp2(u_v[0] * f * f), c.a);");
        const float u[3] = {amount_ / 100.f, mid_, aspect_};
        context.setUniform1fv("u_v", u, 3);
    }
    void cpuRef(const float* in, float* out, std::size_t pixels) const override {
        // Without a region: a 1-pixel-high strip is meaningless; treat it as one row.
        OpRegion r;
        r.width = static_cast<int>(pixels);
        r.height = 1;
        cpuRefRegion(in, out, r);
    }
    void cpuRefRegion(const float* in, float* out, const OpRegion& region) const override {
        const double norm = 0.5 * std::hypot(static_cast<double>(aspect_), 1.0);
        for (int j = 0; j < region.height; ++j) {
            for (int i = 0; i < region.width; ++i) {
                const double u = region.rect[0] + (i + 0.5) / region.width * region.rect[2];
                const double v = region.rect[1] + (j + 0.5) / region.height * region.rect[3];
                const double r = std::hypot((u - 0.5) * aspect_, v - 0.5) / norm;
                const double f = std::clamp((r - mid_) / (1.0 - mid_), 0.0, 1.0);
                const float gain = static_cast<float>(std::exp2(amount_ / 100.0 * f * f));
                const std::size_t k = 4 * (static_cast<std::size_t>(j) * region.width + i);
                for (int c = 0; c < 3; ++c) out[k + c] = in[k + c] * gain;
                out[k + 3] = in[k + 3];
            }
        }
    }

private:
    float amount_, mid_, aspect_;
};

}  // namespace

std::unique_ptr<Op> makeVignetting(float amount, float midpoint, float aspect) {
    return std::make_unique<VignettingOp>(amount, midpoint, aspect);
}

std::vector<std::uint8_t> warpRgba8(const Geometry& g, int sourceWidth, int sourceHeight, const std::uint8_t* rgba, int width,
                                    int height, int outWidth, int outHeight) {
    std::vector<float> src(static_cast<std::size_t>(width) * height * 4);
    for (std::size_t i = 0; i < src.size(); ++i) src[i] = rgba[i] / 255.f;
    WarpSource s;
    s.rgba = src.data();
    s.width = width;
    s.height = height;
    s.scale = static_cast<double>(width) / sourceWidth;
    std::vector<float> out(static_cast<std::size_t>(outWidth) * outHeight * 4);
    const double outputScale = static_cast<double>(outWidth) / g.outputWidth;
    warp(g, sourceWidth, sourceHeight, s, outputScale, 0, 0, outWidth, outHeight, out.data());
    std::vector<std::uint8_t> result(out.size());
    for (std::size_t i = 0; i < out.size(); ++i) result[i] = static_cast<std::uint8_t>(std::lround(std::clamp(out[i], 0.f, 1.f) * 255.f));
    return result;
}

}  // namespace lo
