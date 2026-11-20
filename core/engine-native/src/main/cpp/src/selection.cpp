// Selections (Stage 15); see selection.h and tools/golden/selection_reference.py.
#include "lineover/selection.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <utility>

#include "lineover/color_ops.h"
#include "lineover/masks.h"

namespace lo::selection {

namespace {

constexpr int kSub = 4;  // samples per pixel side when anti-aliased

}  // namespace

void floodFill(const std::uint8_t* within, int width, int height, int seedX, int seedY, int connectivity,
               std::uint8_t* out) {
    std::fill(out, out + static_cast<std::size_t>(width) * height, 0);
    if (seedX < 0 || seedY < 0 || seedX >= width || seedY >= height) return;
    auto at = [&](int x, int y) { return static_cast<std::size_t>(y) * width + x; };
    if (!within[at(seedX, seedY)]) return;
    const int reach = connectivity == 8 ? 1 : 0;  // 8-connected spans also touch diagonally
    std::vector<std::pair<int, int>> stack{{seedX, seedY}};
    while (!stack.empty()) {
        auto [x, y] = stack.back();
        stack.pop_back();
        if (out[at(x, y)] || !within[at(x, y)]) continue;
        int left = x, right = x;
        while (left > 0 && within[at(left - 1, y)] && !out[at(left - 1, y)]) --left;
        while (right < width - 1 && within[at(right + 1, y)] && !out[at(right + 1, y)]) ++right;
        for (int i = left; i <= right; ++i) out[at(i, y)] = 1;
        for (int ny : {y - 1, y + 1}) {
            if (ny < 0 || ny >= height) continue;
            const int from = std::max(0, left - reach), to = std::min(width - 1, right + reach);
            bool inSpan = false;
            for (int i = from; i <= to; ++i) {
                const bool open = within[at(i, ny)] && !out[at(i, ny)];
                if (open && !inSpan) stack.emplace_back(i, ny);  // one seed per run
                inSpan = open;
            }
        }
    }
}

void floodFillRange(const std::uint8_t* image, int width, int height, int seedX, int seedY, int low, int high,
                    int connectivity, std::uint8_t* out) {
    const std::size_t n = static_cast<std::size_t>(width) * height;
    std::vector<std::uint8_t> within(n, 0);
    if (seedX >= 0 && seedY >= 0 && seedX < width && seedY < height) {
        const int seed = image[static_cast<std::size_t>(seedY) * width + seedX];
        for (std::size_t p = 0; p < n; ++p) within[p] = image[p] >= seed - low && image[p] <= seed + high;
    }
    floodFill(within.data(), width, height, seedX, seedY, connectivity, out);
}

namespace {

// 1-D squared Euclidean distance transform of f (Felzenszwalb & Huttenlocher 2012).
void edt1d(const std::vector<double>& f, std::vector<double>* d, std::vector<int>* v, std::vector<double>* z) {
    const int n = static_cast<int>(f.size());
    constexpr double kInf = std::numeric_limits<double>::infinity();
    int k = 0;
    (*v)[0] = 0;
    (*z)[0] = -kInf;
    (*z)[1] = kInf;
    for (int q = 1; q < n; ++q) {
        if (f[q] == kInf) continue;  // no parabola from an infinite site
        if (f[(*v)[k]] == kInf) {
            (*v)[k] = q;
            continue;
        }
        double s;
        while (true) {
            const int p = (*v)[k];
            s = ((f[q] + static_cast<double>(q) * q) - (f[p] + static_cast<double>(p) * p)) / (2.0 * q - 2.0 * p);
            if (s <= (*z)[k] && k > 0) {
                --k;
                continue;
            }
            break;
        }
        ++k;
        (*v)[k] = q;
        (*z)[k] = s;
        (*z)[k + 1] = kInf;
    }
    k = 0;
    for (int q = 0; q < n; ++q) {
        while ((*z)[k + 1] < q) ++k;
        const int p = (*v)[k];
        (*d)[q] = f[p] == kInf ? kInf : (static_cast<double>(q) - p) * (q - p) + f[p];
    }
}

}  // namespace

void distanceTransform(const std::uint8_t* nonzero, int width, int height, float* out) {
    constexpr double kInf = std::numeric_limits<double>::infinity();
    const int n = std::max(width, height);
    std::vector<double> f(static_cast<std::size_t>(n)), d(static_cast<std::size_t>(n));
    std::vector<int> v(static_cast<std::size_t>(n));
    std::vector<double> z(static_cast<std::size_t>(n) + 1);
    std::vector<double> grid(static_cast<std::size_t>(width) * height);
    // Columns, then rows.
    f.resize(static_cast<std::size_t>(height));
    d.resize(static_cast<std::size_t>(height));
    for (int x = 0; x < width; ++x) {
        for (int y = 0; y < height; ++y) f[y] = nonzero[static_cast<std::size_t>(y) * width + x] ? kInf : 0.0;
        edt1d(f, &d, &v, &z);
        for (int y = 0; y < height; ++y) grid[static_cast<std::size_t>(y) * width + x] = d[y];
    }
    f.resize(static_cast<std::size_t>(width));
    d.resize(static_cast<std::size_t>(width));
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) f[x] = grid[static_cast<std::size_t>(y) * width + x];
        edt1d(f, &d, &v, &z);
        for (int x = 0; x < width; ++x) out[static_cast<std::size_t>(y) * width + x] = static_cast<float>(std::sqrt(d[x]));
    }
}

namespace {

// Distances for the selected (true) or unselected (false) side of s >= 0.5.
std::vector<float> sideDistances(const std::vector<float>& s, int width, int height, bool selectedSide) {
    std::vector<std::uint8_t> nz(s.size());
    for (std::size_t p = 0; p < s.size(); ++p) nz[p] = (s[p] >= 0.5f) == selectedSide;
    std::vector<float> d(s.size());
    distanceTransform(nz.data(), width, height, d.data());
    return d;
}

float clamp01(double v) { return static_cast<float>(std::clamp(v, 0.0, 1.0)); }

}  // namespace

void grow(std::vector<float>* s, int width, int height, double r) {
    if (r >= 0.0) {
        const std::vector<float> dOut = sideDistances(*s, width, height, false);
        for (std::size_t p = 0; p < s->size(); ++p) (*s)[p] = std::max((*s)[p], clamp01(r + 1.0 - dOut[p]));
    } else {
        const std::vector<float> dIn = sideDistances(*s, width, height, true);
        for (std::size_t p = 0; p < s->size(); ++p) (*s)[p] = std::min((*s)[p], clamp01(dIn[p] + r));
    }
}

void border(std::vector<float>* s, int width, int height, double band) {
    const std::vector<float> dIn = sideDistances(*s, width, height, true);
    const std::vector<float> dOut = sideDistances(*s, width, height, false);
    for (std::size_t p = 0; p < s->size(); ++p) {
        const double d = (*s)[p] >= 0.5f ? dIn[p] : dOut[p];
        (*s)[p] = clamp01(band / 2.0 + 1.0 - d);
    }
}

void smooth(std::vector<float>* s, int width, int height, double r) {
    if (r > 0.0) {
        std::vector<float> blurred(s->size());
        masks::gaussianBlur(s->data(), width, height, r / 2.0, blurred.data());
        s->swap(blurred);
    }
    for (float& v : *s) v = clamp01((static_cast<double>(v) - 0.5) * 4.0 + 0.5);
}

namespace {

double sampleOffset(int k, bool antiAlias) { return antiAlias ? (k + 0.5) / kSub : 0.5; }

// Coverage of a shape whose inside test is [inside](x, y), in the pixel box [i0, i1) x [j0, j1).
template <typename Inside>
void coverBox(const Grid& g, bool antiAlias, int i0, int i1, int j0, int j1, Inside inside, std::vector<float>* a) {
    const int samples = antiAlias ? kSub : 1;
    for (int j = std::max(0, j0); j < std::min(g.height, j1); ++j)
        for (int i = std::max(0, i0); i < std::min(g.width, i1); ++i) {
            int count = 0;
            for (int sy = 0; sy < samples; ++sy) {
                const double y = (j + sampleOffset(sy, antiAlias)) / g.scale;
                for (int sx = 0; sx < samples; ++sx) count += inside((i + sampleOffset(sx, antiAlias)) / g.scale, y) ? 1 : 0;
            }
            (*a)[static_cast<std::size_t>(j) * g.width + i] = static_cast<float>(static_cast<double>(count) / (samples * samples));
        }
}

void polygon(const SelectionOp& op, const Grid& g, std::vector<float>* a) {
    const std::size_t n = op.points.size() / 2;
    if (n < 3) return;
    const int samples = op.antiAlias ? kSub : 1;
    std::vector<int> counts(static_cast<std::size_t>(g.width));
    std::vector<double> xs;
    for (int j = 0; j < g.height; ++j) {
        std::fill(counts.begin(), counts.end(), 0);
        for (int sy = 0; sy < samples; ++sy) {
            const double y = (j + sampleOffset(sy, op.antiAlias)) / g.scale;
            xs.clear();
            for (std::size_t k = 0; k < n; ++k) {
                const double x0 = op.points[2 * k], y0 = op.points[2 * k + 1];
                const double x1 = op.points[2 * ((k + 1) % n)], y1 = op.points[2 * ((k + 1) % n) + 1];
                if (y0 == y1 || (y0 <= y) == (y1 <= y)) continue;
                xs.push_back(x0 + (y - y0) * (x1 - x0) / (y1 - y0));
            }
            if (xs.empty()) continue;
            std::sort(xs.begin(), xs.end());
            for (int i = 0; i < g.width; ++i)
                for (int sx = 0; sx < samples; ++sx) {
                    const double x = (i + sampleOffset(sx, op.antiAlias)) / g.scale;
                    // Even-odd: an odd number of crossings strictly left of the sample.
                    const auto left = std::lower_bound(xs.begin(), xs.end(), x) - xs.begin();
                    counts[static_cast<std::size_t>(i)] += static_cast<int>(left & 1);
                }
        }
        for (int i = 0; i < g.width; ++i)
            (*a)[static_cast<std::size_t>(j) * g.width + i] = static_cast<float>(static_cast<double>(counts[i]) / (samples * samples));
    }
}

std::vector<float> shape(const SelectionOp& op, const Grid& g) {
    std::vector<float> a(static_cast<std::size_t>(g.width) * g.height, 0.f);
    if (op.kind == "lasso" || op.kind == "polygon") {
        polygon(op, g, &a);
        return a;
    }
    const double x0 = std::min(op.x0, op.x1), x1 = std::max(op.x0, op.x1);
    const double y0 = std::min(op.y0, op.y1), y1 = std::max(op.y0, op.y1);
    const int i0 = static_cast<int>(std::floor(x0 * g.scale)) - 1, i1 = static_cast<int>(std::ceil(x1 * g.scale)) + 1;
    const int j0 = static_cast<int>(std::floor(y0 * g.scale)) - 1, j1 = static_cast<int>(std::ceil(y1 * g.scale)) + 1;
    if (op.kind == "rect") {
        coverBox(g, op.antiAlias, i0, i1, j0, j1, [&](double x, double y) { return x >= x0 && x < x1 && y >= y0 && y < y1; }, &a);
    } else if (op.kind == "ellipse") {
        const double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, rx = (x1 - x0) / 2, ry = (y1 - y0) / 2;
        if (rx <= 0.0 || ry <= 0.0) return a;
        coverBox(g, op.antiAlias, i0, i1, j0, j1,
                 [&](double x, double y) {
                     const double u = (x - cx) / rx, v = (y - cy) / ry;
                     return u * u + v * v <= 1.0;
                 },
                 &a);
    }
    return a;
}

int seedIndex(double v, double scale, int n) {
    return std::clamp(static_cast<int>(std::floor(v * scale)), 0, n - 1);
}

std::vector<float> labGrid(const Grid& g) {
    std::vector<float> lab(static_cast<std::size_t>(g.width) * g.height * 3, 0.f);
    if (g.rgba == nullptr) return lab;
    for (std::size_t p = 0; p < static_cast<std::size_t>(g.width) * g.height; ++p) {
        float l[3];
        colorops::toOklab(g.rgba + 4 * p, l);
        for (int c = 0; c < 3; ++c) lab[3 * p + c] = l[c] * 100.f;
    }
    return lab;
}

double deltaE(const std::vector<float>& lab, std::size_t p, const float seed[3]) {
    const double a = lab[3 * p] - static_cast<double>(seed[0]);
    const double b = lab[3 * p + 1] - static_cast<double>(seed[1]);
    const double c = lab[3 * p + 2] - static_cast<double>(seed[2]);
    return std::sqrt(a * a + b * b + c * c);
}

std::vector<float> wand(const SelectionOp& op, const Grid& g, const std::vector<float>& lab) {
    const std::size_t n = static_cast<std::size_t>(g.width) * g.height;
    const int sx = seedIndex(op.x, g.scale, g.width), sy = seedIndex(op.y, g.scale, g.height);
    const std::size_t s = static_cast<std::size_t>(sy) * g.width + sx;
    const float seed[3] = {lab[3 * s], lab[3 * s + 1], lab[3 * s + 2]};
    std::vector<std::uint8_t> within(n);
    for (std::size_t p = 0; p < n; ++p) within[p] = deltaE(lab, p, seed) <= op.tolerance;
    std::vector<float> a(n);
    if (op.contiguous) {
        std::vector<std::uint8_t> filled(n);
        floodFill(within.data(), g.width, g.height, sx, sy, 4, filled.data());
        for (std::size_t p = 0; p < n; ++p) a[p] = filled[p];
    } else {
        for (std::size_t p = 0; p < n; ++p) a[p] = within[p];
    }
    return a;
}

std::vector<double> nearest(const std::vector<double>& points, const Grid& g, const std::vector<float>& lab) {
    const std::size_t n = static_cast<std::size_t>(g.width) * g.height;
    std::vector<double> best(n, std::numeric_limits<double>::infinity());
    for (std::size_t k = 0; k + 1 < points.size(); k += 2) {
        const std::size_t s = static_cast<std::size_t>(seedIndex(points[k + 1], g.scale, g.height)) * g.width +
                              seedIndex(points[k], g.scale, g.width);
        const float seed[3] = {lab[3 * s], lab[3 * s + 1], lab[3 * s + 2]};
        for (std::size_t p = 0; p < n; ++p) best[p] = std::min(best[p], deltaE(lab, p, seed));
    }
    return best;
}

std::vector<float> colorRange(const SelectionOp& op, const Grid& g, const std::vector<float>& lab) {
    const std::size_t n = static_cast<std::size_t>(g.width) * g.height;
    const double fuzz = std::max(op.fuzziness, 1e-6);
    std::vector<float> a(n, 0.f);
    if (op.add.size() >= 2) {
        const std::vector<double> d = nearest(op.add, g, lab);
        for (std::size_t p = 0; p < n; ++p) a[p] = clamp01(1.0 - d[p] / fuzz);
    }
    if (op.subtract.size() >= 2) {
        const std::vector<double> d = nearest(op.subtract, g, lab);
        for (std::size_t p = 0; p < n; ++p) a[p] = static_cast<float>(a[p] * std::clamp(d[p] / fuzz, 0.0, 1.0));
    }
    return a;
}

void combine(std::vector<float>* s, const std::vector<float>& a, const std::string& mode) {
    for (std::size_t p = 0; p < s->size(); ++p) {
        float& v = (*s)[p];
        if (mode == "add") {
            v = std::max(v, a[p]);
        } else if (mode == "subtract") {
            v = std::min(v, 1.f - a[p]);
        } else if (mode == "intersect") {
            v = std::min(v, a[p]);
        } else {
            v = a[p];
        }
    }
}

}  // namespace

std::vector<float> evaluate(const std::vector<SelectionOp>& ops, const Grid& g) {
    std::vector<float> s(static_cast<std::size_t>(g.width) * g.height, 0.f);
    if (g.width <= 0 || g.height <= 0) return s;
    std::vector<float> lab;
    for (const SelectionOp& op : ops) {
        const std::string& k = op.kind;
        if (k == "rect" || k == "ellipse" || k == "lasso" || k == "polygon") {
            combine(&s, shape(op, g), op.mode);
        } else if (k == "wand" || k == "color_range") {
            if (lab.empty()) lab = labGrid(g);
            combine(&s, k == "wand" ? wand(op, g, lab) : colorRange(op, g, lab), op.mode);
        } else if (k == "all") {
            std::fill(s.begin(), s.end(), 1.f);
        } else if (k == "invert") {
            for (float& v : s) v = 1.f - v;
        } else if (k == "grow") {
            grow(&s, g.width, g.height, op.px * g.scale);
        } else if (k == "feather") {
            if (op.px > 0.0) {
                std::vector<float> blurred(s.size());
                masks::gaussianBlur(s.data(), g.width, g.height, op.px * g.scale, blurred.data());
                s.swap(blurred);
            }
        } else if (k == "smooth") {
            smooth(&s, g.width, g.height, op.px * g.scale);
        } else if (k == "border") {
            border(&s, g.width, g.height, op.px * g.scale);
        }
    }
    for (float& v : s) v = masks::fromHalf(masks::toHalf(v));  // R16F
    return s;
}

void contours(const float* s, int width, int height, double scale, std::vector<float>* segments) {
    segments->clear();
    // Cells between pixel centres, with a ring of 0 outside so outlines close at the edges.
    auto value = [&](int i, int j) -> float {
        if (i < 0 || j < 0 || i >= width || j >= height) return 0.f;
        return s[static_cast<std::size_t>(j) * width + i];
    };
    auto point = [&](double i, double j, float* out) {
        out[0] = static_cast<float>((i + 0.5) / scale);
        out[1] = static_cast<float>((j + 0.5) / scale);
    };
    auto cross = [](float a, float b) { return std::clamp((0.5f - a) / (b - a), 0.f, 1.f); };
    for (int j = -1; j < height; ++j)
        for (int i = -1; i < width; ++i) {
            const float v00 = value(i, j), v10 = value(i + 1, j), v01 = value(i, j + 1), v11 = value(i + 1, j + 1);
            const int code = (v00 >= 0.5f ? 1 : 0) | (v10 >= 0.5f ? 2 : 0) | (v11 >= 0.5f ? 4 : 0) | (v01 >= 0.5f ? 8 : 0);
            if (code == 0 || code == 15) continue;
            // Edge crossings: top, right, bottom, left.
            float e[4][2];
            point(i + cross(v00, v10), j, e[0]);
            point(i + 1, j + cross(v10, v11), e[1]);
            point(i + cross(v01, v11), j + 1, e[2]);
            point(i, j + cross(v00, v01), e[3]);
            static const int kSegments[16][4] = {{-1, -1, -1, -1}, {3, 0, -1, -1}, {0, 1, -1, -1}, {3, 1, -1, -1},
                                                 {1, 2, -1, -1},  {3, 0, 1, 2},   {0, 2, -1, -1}, {3, 2, -1, -1},
                                                 {2, 3, -1, -1},  {0, 2, -1, -1}, {0, 1, 2, 3},   {1, 2, -1, -1},
                                                 {1, 3, -1, -1},  {0, 1, -1, -1}, {3, 0, -1, -1}, {-1, -1, -1, -1}};
            for (int k = 0; k < 4 && kSegments[code][k] >= 0; k += 2) {
                const float* a = e[kSegments[code][k]];
                const float* b = e[kSegments[code][k + 1]];
                segments->insert(segments->end(), {a[0], a[1], b[0], b[1]});
            }
        }
}

}  // namespace lo::selection
