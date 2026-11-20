// Selections (Stage 15); see selection.h and tools/golden/selection_reference.py.
#include "lineover/selection.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <memory>
#include <limits>
#include <utility>

#include "lineover/color_ops.h"
#include "lineover/masks.h"
#include "lineover/smart_select.h"

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
    if (op.kind == "lasso" || op.kind == "polygon" || op.kind == "magnetic") {
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

std::vector<float> labGrid(const Grid& g, const float* rgba) {
    std::vector<float> lab(static_cast<std::size_t>(g.width) * g.height * 3, 0.f);
    if (rgba == nullptr) return lab;
    for (std::size_t p = 0; p < static_cast<std::size_t>(g.width) * g.height; ++p) {
        float l[3];
        colorops::toOklab(rgba + 4 * p, l);
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

// A brush stroke's coverage (the mask brush of masks.h: value 1, full opacity).
std::vector<float> brush(const SelectionOp& op, const Grid& g) {
    LayerMask m;
    m.base = 0.0;
    MaskPaint p;
    p.kind = "stroke";
    p.value = 1.0;
    p.radius = op.radius;
    p.hardness = op.hardness;
    p.points = op.points;
    m.paint.push_back(p);
    std::vector<float> a(static_cast<std::size_t>(g.width) * g.height);
    masks::rasterize(m, 0, 0, g.width, g.height, g.scale, a.data());
    return a;
}

// A model mask covering the document, brought to the grid by joint bilateral upsampling with the
// grid's image (bilinear without one); nothing while it is not registered.
std::vector<float> raster(const SelectionOp& op, const Grid& g) {
    std::vector<float> a(static_cast<std::size_t>(g.width) * g.height, 0.f);
    std::shared_ptr<const std::vector<float>> values;
    int rw = 0, rh = 0;
    if (!masks::findRaster(op.source, &values, &rw, &rh) || rw <= 0 || rh <= 0) return a;
    if (g.rgba != nullptr) {
        const std::vector<double> guide = smart::guide(g.rgba, g.width, g.height);
        smart::jointBilateralUpsample(values->data(), rw, rh, smart::lowGuide(guide, g.width, g.height, rw, rh), guide,
                                      g.width, g.height, a.data());
        return a;
    }
    LayerMask m;
    MaskPaint p;
    p.kind = "raster";
    p.source = op.source;
    p.docWidth = g.width / g.scale;
    p.docHeight = g.height / g.scale;
    m.paint.push_back(p);
    masks::rasterize(m, 0, 0, g.width, g.height, g.scale, a.data());
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

namespace {

bool costly(const std::string& kind) { return kind == "raster" || kind == "quick" || kind == "refine"; }

// The longest checkpoint whose recipe starts [ops] (on the same grid), or null.
const Cache::Checkpoint* resume(const std::vector<SelectionOp>& ops, const Grid& g, Cache* cache) {
    if (cache == nullptr) return nullptr;
    if (cache->generation != g.generation || cache->width != g.width || cache->height != g.height || cache->scale != g.scale) {
        cache->checkpoints.clear();
        cache->generation = g.generation;
        cache->width = g.width;
        cache->height = g.height;
        cache->scale = g.scale;
        return nullptr;
    }
    const Cache::Checkpoint* best = nullptr;
    for (const Cache::Checkpoint& c : cache->checkpoints) {
        if (c.ops.size() > ops.size() || (best != nullptr && c.ops.size() <= best->ops.size())) continue;
        if (std::equal(c.ops.begin(), c.ops.end(), ops.begin())) best = &c;
    }
    return best;
}

constexpr std::size_t kCheckpoints = 4;

}  // namespace

std::vector<float> evaluate(const std::vector<SelectionOp>& ops, const Grid& g, Cache* cache) {
    std::vector<float> s(static_cast<std::size_t>(g.width) * g.height, 0.f);
    if (g.width <= 0 || g.height <= 0) return s;
    std::size_t from = 0;
    if (const Cache::Checkpoint* c = resume(ops, g, cache)) {
        s = c->s;
        from = c->ops.size();
    }
    std::vector<float> lab, labPhoto;
    for (std::size_t index = from; index < ops.size(); ++index) {
        const SelectionOp& op = ops[index];
        const std::string& k = op.kind;
        if (k == "rect" || k == "ellipse" || k == "lasso" || k == "polygon" || k == "magnetic") {
            combine(&s, shape(op, g), op.mode);
        } else if (k == "brush") {
            combine(&s, brush(op, g), op.mode);
        } else if (k == "raster") {
            combine(&s, raster(op, g), op.mode);
        } else if (k == "quick") {
            combine(&s, quickSelect(op, g), op.mode);
        } else if (k == "refine") {
            smart::refine(&s, g.rgba, g.width, g.height, op.px * g.scale, op.contrast);
        } else if (k == "wand" || k == "color_range") {
            const bool photo = !op.allLayers && g.rgbaPhoto != nullptr;
            std::vector<float>& source = photo ? labPhoto : lab;
            if (source.empty()) source = labGrid(g, photo ? g.rgbaPhoto : g.rgba);
            combine(&s, k == "wand" ? wand(op, g, source) : colorRange(op, g, source), op.mode);
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
        if (cache != nullptr && costly(k)) {
            cache->checkpoints.push_back({std::vector<SelectionOp>(ops.begin(), ops.begin() + static_cast<std::ptrdiff_t>(index) + 1), s});
            if (cache->checkpoints.size() > kCheckpoints) cache->checkpoints.erase(cache->checkpoints.begin());
        }
    }
    for (float& v : s) v = masks::fromHalf(masks::toHalf(v));  // R16F
    return s;
}

std::vector<float> lightness(const float* rgba, int width, int height) {
    const std::size_t n = static_cast<std::size_t>(width) * height;
    std::vector<float> l(n);
    for (std::size_t p = 0; p < n; ++p) {
        float lab[3];
        colorops::toOklab(rgba + 4 * p, lab);
        l[p] = std::clamp(lab[0], 0.f, 1.f);
    }
    return l;
}

std::vector<float> quickSelect(const SelectionOp& op, const Grid& g) {
    const std::size_t n = static_cast<std::size_t>(g.width) * g.height;
    std::vector<float> out(n, 0.f);
    const std::size_t count = op.points.size() / 2;
    if (count == 0 || g.rgba == nullptr || op.radius <= 0.0) return out;
    // The region around the stroke (grid px): its box grown by 3 radii and 60 % of its size.
    double bx0 = op.points[0], by0 = op.points[1], bx1 = bx0, by1 = by0;
    for (std::size_t k = 1; k < count; ++k) {
        bx0 = std::min(bx0, op.points[2 * k]);
        bx1 = std::max(bx1, op.points[2 * k]);
        by0 = std::min(by0, op.points[2 * k + 1]);
        by1 = std::max(by1, op.points[2 * k + 1]);
    }
    const double r = op.radius * g.scale;
    const double margin = std::max({3.0 * r, 0.6 * std::max(bx1 - bx0, by1 - by0) * g.scale, 24.0});
    const int x0 = std::max(0, static_cast<int>(std::floor(bx0 * g.scale - margin)));
    const int y0 = std::max(0, static_cast<int>(std::floor(by0 * g.scale - margin)));
    const int x1 = std::min(g.width, static_cast<int>(std::ceil(bx1 * g.scale + margin)));
    const int y1 = std::min(g.height, static_cast<int>(std::ceil(by1 * g.scale + margin)));
    const int rw = x1 - x0, rh = y1 - y0;
    if (rw < 4 || rh < 4) return out;
    // Work at <= 320 px (GrabCut's graph), then bring the cut back with the image as the guide.
    const double f = std::max(1.0, std::max(rw, rh) / 320.0);
    const int lw = std::max(1, static_cast<int>(std::ceil(rw / f))), lh = std::max(1, static_cast<int>(std::ceil(rh / f)));
    std::vector<float> roi(static_cast<std::size_t>(rw) * rh * 4);
    for (int j = 0; j < rh; ++j)
        std::copy(g.rgba + 4 * (static_cast<std::size_t>(y0 + j) * g.width + x0),
                  g.rgba + 4 * (static_cast<std::size_t>(y0 + j) * g.width + x1), roi.begin() + 4 * static_cast<std::ptrdiff_t>(j) * rw);
    const std::vector<double> guide = smart::guide(roi.data(), rw, rh);
    const std::vector<double> lowG = smart::lowGuide(guide, rw, rh, lw, lh);
    std::vector<double> rgb(lowG.size());
    for (std::size_t q = 0; q < lowG.size(); ++q) rgb[q] = lowG[q] * 255.0;
    // Trimap: the stroke is object; the region's edges are background where they are not the
    // image's edges (the object may run off the photo); the rest is probably background.
    std::vector<std::uint8_t> tri(static_cast<std::size_t>(lw) * lh, smart::kPrBgd);
    // Low pixel (i, j) is the document point ((x0 + (i + 0.5) f) / scale, ...).
    for (int j = 0; j < lh; ++j)
        for (int i = 0; i < lw; ++i) {
            const double dx = (x0 + (i + 0.5) * rw / lw) / g.scale, dy = (y0 + (j + 0.5) * rh / lh) / g.scale;
            double best = std::numeric_limits<double>::infinity();
            for (std::size_t k = 0; k < count; ++k) {
                const double ax = op.points[2 * k], ay = op.points[2 * k + 1];
                const double bx = k + 1 < count ? op.points[2 * k + 2] : ax, by = k + 1 < count ? op.points[2 * k + 3] : ay;
                const double sx = bx - ax, sy = by - ay, ll = sx * sx + sy * sy;
                const double t = ll > 0.0 ? std::clamp(((dx - ax) * sx + (dy - ay) * sy) / ll, 0.0, 1.0) : 0.0;
                best = std::min(best, std::hypot(dx - (ax + t * sx), dy - (ay + t * sy)));
            }
            std::uint8_t& v = tri[static_cast<std::size_t>(j) * lw + i];
            if (best <= op.radius) {
                v = smart::kFgd;
            } else if ((i == 0 && x0 > 0) || (j == 0 && y0 > 0) || (i == lw - 1 && x1 < g.width) || (j == lh - 1 && y1 < g.height)) {
                v = smart::kBgd;
            }
        }
    smart::grabCut(rgb, lw, lh, &tri, 4);
    std::vector<float> low(tri.size());
    for (std::size_t q = 0; q < tri.size(); ++q) low[q] = (tri[q] == smart::kFgd || tri[q] == smart::kPrFgd) ? 1.f : 0.f;
    std::vector<float> up(static_cast<std::size_t>(rw) * rh);
    smart::jointBilateralUpsample(low.data(), lw, lh, lowG, guide, rw, rh, up.data());
    for (int j = 0; j < rh; ++j)
        std::copy(up.begin() + static_cast<std::ptrdiff_t>(j) * rw, up.begin() + static_cast<std::ptrdiff_t>(j + 1) * rw,
                  out.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(y0 + j) * g.width + x0));
    return out;
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
