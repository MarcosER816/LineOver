// Smart selections (Stage 16); see smart_select.h and tools/golden/smart_reference.py.
#include "lineover/smart_select.h"

#include <algorithm>
#include <array>
#include <climits>
#include <cmath>
#include <cstddef>
#include <functional>
#include <limits>
#include <queue>
#include <utility>

namespace lo::smart {

namespace {

constexpr double kSigmaS = 1.0;
constexpr double kSigmaR = 0.1;
constexpr int kReach = 2;
constexpr double kPi = 3.14159265358979323846;

int reflect101(int i, int n) {
    if (n == 1) return 0;
    while (i < 0 || i >= n) i = i < 0 ? -i : 2 * n - 2 - i;
    return i;
}

}  // namespace

std::vector<double> guide(const float* rgba, int width, int height) {
    const std::size_t n = static_cast<std::size_t>(width) * height;
    std::vector<double> g(n * 3);
    for (std::size_t p = 0; p < n; ++p)
        for (int c = 0; c < 3; ++c) g[3 * p + c] = std::sqrt(std::clamp(static_cast<double>(rgba[4 * p + c]), 0.0, 1.0));
    return g;
}

std::vector<double> lowGuide(const std::vector<double>& g, int width, int height, int lw, int lh) {
    const std::size_t ln = static_cast<std::size_t>(lw) * lh;
    std::vector<double> acc(ln * 3, 0.0);
    std::vector<int> count(ln, 0);
    for (int j = 0; j < height; ++j) {
        const int qy = std::min(static_cast<int>((j + 0.5) * lh / height), lh - 1);
        for (int i = 0; i < width; ++i) {
            const int qx = std::min(static_cast<int>((i + 0.5) * lw / width), lw - 1);
            const std::size_t q = static_cast<std::size_t>(qy) * lw + qx;
            const std::size_t p = static_cast<std::size_t>(j) * width + i;
            for (int c = 0; c < 3; ++c) acc[3 * q + c] += g[3 * p + c];
            ++count[q];
        }
    }
    for (int qy = 0; qy < lh; ++qy)
        for (int qx = 0; qx < lw; ++qx) {
            const std::size_t q = static_cast<std::size_t>(qy) * lw + qx;
            if (count[q] > 0) {
                for (int c = 0; c < 3; ++c) acc[3 * q + c] /= count[q];
            } else {  // no guide centre inside: the nearest guide pixel
                const int y = std::clamp(static_cast<int>(std::floor((qy + 0.5) * height / lh)), 0, height - 1);
                const int x = std::clamp(static_cast<int>(std::floor((qx + 0.5) * width / lw)), 0, width - 1);
                const std::size_t p = static_cast<std::size_t>(y) * width + x;
                for (int c = 0; c < 3; ++c) acc[3 * q + c] = g[3 * p + c];
            }
        }
    return acc;
}

void jointBilateralUpsample(const float* low, int lw, int lh, const std::vector<double>& lg, const std::vector<double>& g,
                            int width, int height, float* out) {
    for (int j = 0; j < height; ++j) {
        const double v = (j + 0.5) * lh / height - 0.5;
        const int fv = static_cast<int>(std::floor(v));
        for (int i = 0; i < width; ++i) {
            const double u = (i + 0.5) * lw / width - 0.5;
            const int fu = static_cast<int>(std::floor(u));
            const double* gp = &g[3 * (static_cast<std::size_t>(j) * width + i)];
            double num = 0.0, den = 0.0;
            for (int ky = -kReach; ky <= kReach; ++ky) {
                const int qy = fv + ky;
                const double dy = qy - v;
                if (qy < 0 || qy >= lh || std::abs(dy) > kReach) continue;
                for (int kx = -kReach; kx <= kReach; ++kx) {
                    const int qx = fu + kx;
                    const double dx = qx - u;
                    if (qx < 0 || qx >= lw || std::abs(dx) > kReach) continue;
                    const std::size_t q = static_cast<std::size_t>(qy) * lw + qx;
                    const double d0 = gp[0] - lg[3 * q], d1 = gp[1] - lg[3 * q + 1], d2 = gp[2] - lg[3 * q + 2];
                    const double dd = d0 * d0 + d1 * d1 + d2 * d2;
                    const double w = std::exp(-(dx * dx + dy * dy) / (2 * kSigmaS * kSigmaS)) *
                                     std::exp(-dd / (2 * kSigmaR * kSigmaR));
                    num += w * low[q];
                    den += w;
                }
            }
            out[static_cast<std::size_t>(j) * width + i] = static_cast<float>(den > 0.0 ? num / den : 0.0);
        }
    }
}

namespace {

// Normalised box mean of radius r with BORDER_REFLECT_101 (cv2.boxFilter).
std::vector<double> boxMean(const std::vector<double>& in, int width, int height, int r) {
    const double norm = 1.0 / ((2.0 * r + 1.0) * (2.0 * r + 1.0));
    std::vector<double> rows(in.size());
    for (int j = 0; j < height; ++j) {
        const double* line = &in[static_cast<std::size_t>(j) * width];
        double sum = 0.0;
        for (int k = -r; k <= r; ++k) sum += line[reflect101(k, width)];
        for (int i = 0; i < width; ++i) {
            rows[static_cast<std::size_t>(j) * width + i] = sum;
            sum += line[reflect101(i + r + 1, width)] - line[reflect101(i - r, width)];
        }
    }
    std::vector<double> out(in.size());
    for (int i = 0; i < width; ++i) {
        double sum = 0.0;
        for (int k = -r; k <= r; ++k) sum += rows[static_cast<std::size_t>(reflect101(k, height)) * width + i];
        for (int j = 0; j < height; ++j) {
            out[static_cast<std::size_t>(j) * width + i] = sum * norm;
            sum += rows[static_cast<std::size_t>(reflect101(j + r + 1, height)) * width + i] -
                   rows[static_cast<std::size_t>(reflect101(j - r, height)) * width + i];
        }
    }
    return out;
}

}  // namespace

void guidedMatte(const float* s, const std::vector<double>& g, int width, int height, int r, double eps, float* out) {
    const std::size_t n = static_cast<std::size_t>(width) * height;
    std::vector<double> p(s, s + n), ch[3], tmp(n);
    for (int c = 0; c < 3; ++c) {
        ch[c].resize(n);
        for (std::size_t q = 0; q < n; ++q) ch[c][q] = g[3 * q + c];
    }
    std::vector<double> mi[3], cov[3];
    const std::vector<double> mp = boxMean(p, width, height, r);
    for (int c = 0; c < 3; ++c) {
        mi[c] = boxMean(ch[c], width, height, r);
        for (std::size_t q = 0; q < n; ++q) tmp[q] = ch[c][q] * p[q];
        cov[c] = boxMean(tmp, width, height, r);
        for (std::size_t q = 0; q < n; ++q) cov[c][q] -= mi[c][q] * mp[q];
    }
    std::vector<double> sig[6];  // aa, ab, ac, bb, bc, cc
    int k = 0;
    for (int a = 0; a < 3; ++a)
        for (int b = a; b < 3; ++b, ++k) {
            for (std::size_t q = 0; q < n; ++q) tmp[q] = ch[a][q] * ch[b][q];
            sig[k] = boxMean(tmp, width, height, r);
            for (std::size_t q = 0; q < n; ++q) sig[k][q] = sig[k][q] - mi[a][q] * mi[b][q] + (a == b ? eps : 0.0);
        }
    std::vector<double> coef[3], bb(n);
    for (auto& v : coef) v.resize(n);
    for (std::size_t q = 0; q < n; ++q) {
        const double s00 = sig[0][q], s01 = sig[1][q], s02 = sig[2][q], s11 = sig[3][q], s12 = sig[4][q], s22 = sig[5][q];
        // Inverse of the symmetric covariance (cofactors).
        const double c00 = s11 * s22 - s12 * s12, c01 = s02 * s12 - s01 * s22, c02 = s01 * s12 - s02 * s11;
        const double c11 = s00 * s22 - s02 * s02, c12 = s01 * s02 - s00 * s12, c22 = s00 * s11 - s01 * s01;
        const double det = s00 * c00 + s01 * c01 + s02 * c02;
        const double r0 = cov[0][q], r1 = cov[1][q], r2 = cov[2][q];
        coef[0][q] = (c00 * r0 + c01 * r1 + c02 * r2) / det;
        coef[1][q] = (c01 * r0 + c11 * r1 + c12 * r2) / det;
        coef[2][q] = (c02 * r0 + c12 * r1 + c22 * r2) / det;
        bb[q] = mp[q] - (coef[0][q] * mi[0][q] + coef[1][q] * mi[1][q] + coef[2][q] * mi[2][q]);
    }
    std::vector<double> ma[3];
    for (int c = 0; c < 3; ++c) ma[c] = boxMean(coef[c], width, height, r);
    const std::vector<double> mb = boxMean(bb, width, height, r);
    for (std::size_t q = 0; q < n; ++q)
        out[q] = static_cast<float>(ma[0][q] * ch[0][q] + ma[1][q] * ch[1][q] + ma[2][q] * ch[2][q] + mb[q]);
}

void refine(std::vector<float>* s, const float* rgba, int width, int height, double radius, double contrast) {
    if (rgba == nullptr || width <= 0 || height <= 0) return;
    const int r = std::max(1, static_cast<int>(std::nearbyint(radius)));  // Python round(): half to even
    std::vector<float> q(s->size());
    guidedMatte(s->data(), guide(rgba, width, height), width, height, r, 2e-4, q.data());
    const double k = 1.0 + contrast / 20.0;
    for (std::size_t p = 0; p < s->size(); ++p)
        (*s)[p] = static_cast<float>(std::clamp((static_cast<double>(q[p]) - 0.5) * k + 0.5, 0.0, 1.0));
}

// --- Live-wire ---------------------------------------------------------------------------------

void LiveWire::setImage(const float* grey, int width, int height) {
    width_ = width;
    height_ = height;
    anchor_ = -1;
    const std::size_t n = static_cast<std::size_t>(width) * height;
    auto at = [&](int x, int y) {
        return static_cast<double>(grey[static_cast<std::size_t>(reflect101(y, height)) * width + reflect101(x, width)]);
    };
    std::vector<double> gx(n), gy(n), mag(n), lap(n);
    double mx = 0.0;
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            const std::size_t p = static_cast<std::size_t>(y) * width + x;
            const double sx = (at(x + 1, y - 1) + 2.0 * at(x + 1, y) + at(x + 1, y + 1)) -
                              (at(x - 1, y - 1) + 2.0 * at(x - 1, y) + at(x - 1, y + 1));
            const double sy = (at(x - 1, y + 1) + 2.0 * at(x, y + 1) + at(x + 1, y + 1)) -
                              (at(x - 1, y - 1) + 2.0 * at(x, y - 1) + at(x + 1, y - 1));
            gx[p] = sx / 8.0;
            gy[p] = sy / 8.0;
            mag[p] = std::sqrt(gx[p] * gx[p] + gy[p] * gy[p]);
            mx = std::max(mx, mag[p]);
            lap[p] = at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1) - 4.0 * at(x, y);
        }
    fz_.assign(n, 1.0);
    fg_.resize(n);
    dx_.resize(n);
    dy_.resize(n);
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            const std::size_t p = static_cast<std::size_t>(y) * width + x;
            const double c = lap[p];
            if (c == 0.0) {
                fz_[p] = 0.0;
            } else {
                const int nb[4][2] = {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}};
                for (const auto& q : nb) {
                    if (q[0] < 0 || q[0] >= width || q[1] < 0 || q[1] >= height) continue;
                    const double o = lap[static_cast<std::size_t>(q[1]) * width + q[0]];
                    if ((c > 0.0) != (o > 0.0) && std::abs(c) <= std::abs(o)) {
                        fz_[p] = 0.0;
                        break;
                    }
                }
            }
            fg_[p] = 1.0 - (mx > 0.0 ? mag[p] / mx : 0.0);
            dx_[p] = mag[p] > 0.0 ? gy[p] / mag[p] : 0.0;
            dy_[p] = mag[p] > 0.0 ? -gx[p] / mag[p] : 0.0;
        }
}

double LiveWire::linkCost(int p, int q) const {
    const int px = p % width_, py = p / width_, qx = q % width_, qy = q / width_;
    const int lx = qx - px, ly = qy - py;
    const bool diagonal = lx != 0 && ly != 0;
    const double norm = diagonal ? std::sqrt(2.0) : 1.0;
    double ux = lx / norm, uy = ly / norm;
    if (dx_[p] * ux + dy_[p] * uy < 0.0) {
        ux = -ux;
        uy = -uy;
    }
    const double dp = std::clamp(dx_[p] * ux + dy_[p] * uy, -1.0, 1.0);
    const double dq = std::clamp(ux * dx_[q] + uy * dy_[q], -1.0, 1.0);
    const double fd = (2.0 / (3.0 * kPi)) * (std::acos(dp) + std::acos(dq));
    const double s = diagonal ? 1.0 : 1.0 / std::sqrt(2.0);
    return 0.43 * fz_[q] + 0.43 * fg_[q] * s + 0.14 * fd;
}

void LiveWire::setAnchor(int x, int y, int reach) {
    if (width_ == 0) return;
    x = std::clamp(x, 0, width_ - 1);
    y = std::clamp(y, 0, height_ - 1);
    x0_ = reach > 0 ? std::max(0, x - reach) : 0;
    y0_ = reach > 0 ? std::max(0, y - reach) : 0;
    x1_ = reach > 0 ? std::min(width_, x + reach + 1) : width_;
    y1_ = reach > 0 ? std::min(height_, y + reach + 1) : height_;
    const int ww = x1_ - x0_, wh = y1_ - y0_;
    const std::size_t n = static_cast<std::size_t>(ww) * wh;
    auto local = [&](int p) { return static_cast<std::size_t>(p / width_ - y0_) * ww + (p % width_ - x0_); };
    std::vector<double> dist(n, std::numeric_limits<double>::infinity());
    std::vector<std::uint8_t> done(n, 0);
    parent_.assign(n, -1);
    anchor_ = y * width_ + x;
    using Item = std::pair<double, int>;
    std::priority_queue<Item, std::vector<Item>, std::greater<Item>> heap;
    dist[local(anchor_)] = 0.0;
    heap.emplace(0.0, anchor_);
    static const int kNb[8][2] = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
    while (!heap.empty()) {
        const auto [d, p] = heap.top();
        heap.pop();
        const std::size_t lp = local(p);
        if (done[lp]) continue;
        done[lp] = 1;
        const int px = p % width_, py = p / width_;
        for (const auto& o : kNb) {
            const int qx = px + o[0], qy = py + o[1];
            if (qx < x0_ || qx >= x1_ || qy < y0_ || qy >= y1_) continue;
            const int q = qy * width_ + qx;
            const std::size_t lq = local(q);
            if (done[lq]) continue;
            const double nd = d + linkCost(p, q);
            if (nd < dist[lq]) {
                dist[lq] = nd;
                parent_[lq] = p;
                heap.emplace(nd, q);
            }
        }
    }
}

std::vector<int> LiveWire::pathTo(int x, int y) const {
    std::vector<int> path;
    if (anchor_ < 0) return path;
    x = std::clamp(x, x0_, x1_ - 1);
    y = std::clamp(y, y0_, y1_ - 1);
    const int ww = x1_ - x0_;
    for (int p = y * width_ + x; p != -1; p = parent_[static_cast<std::size_t>(p / width_ - y0_) * ww + (p % width_ - x0_)]) {
        path.push_back(p % width_);
        path.push_back(p / width_);
    }
    // Reverse the (x, y) pairs: anchor first.
    for (std::size_t a = 0, b = path.size() - 2; a < b; a += 2, b -= 2) {
        std::swap(path[a], path[b]);
        std::swap(path[a + 1], path[b + 1]);
    }
    return path;
}

// --- Max flow (Boykov-Kolmogorov) ---------------------------------------------------------------

GraphCut::GraphCut(int vertices, int edges) : v_(static_cast<std::size_t>(vertices)) {
    e_.reserve(static_cast<std::size_t>(edges) * 2 + 2);
    e_.resize(2);  // edge 0 means "none"; pairs (2k, 2k + 1) are sisters
}

void GraphCut::addTerminal(int i, double source, double sink) {
    const double dw = v_[static_cast<std::size_t>(i)].weight;
    if (dw > 0) {
        source += dw;
    } else {
        sink -= dw;
    }
    flow_ += std::min(source, sink);
    v_[static_cast<std::size_t>(i)].weight = source - sink;
}

void GraphCut::addEdge(int a, int b, double w, double reverse) {
    Edge from{b, v_[static_cast<std::size_t>(a)].first, w};
    v_[static_cast<std::size_t>(a)].first = static_cast<int>(e_.size());
    e_.push_back(from);
    Edge to{a, v_[static_cast<std::size_t>(b)].first, reverse};
    v_[static_cast<std::size_t>(b)].first = static_cast<int>(e_.size());
    e_.push_back(to);
}

double GraphCut::maxFlow() {
    constexpr int kTerminal = -1, kOrphan = -2;
    Vertex stub;
    Vertex* nil = &stub;
    Vertex* first = nil;
    Vertex* last = nil;
    int currTs = 0;
    stub.next = nil;
    std::vector<Vertex*> orphans;
    for (Vertex& v : v_) {
        v.next = nullptr;
        v.ts = 0;
        if (v.weight != 0) {
            last = last->next = &v;
            v.dist = 1;
            v.parent = kTerminal;
            v.t = v.weight < 0;
        } else {
            v.parent = 0;
        }
    }
    first = first->next;
    last->next = nil;
    nil->next = nullptr;
    Edge* edges = e_.data();
    Vertex* verts = v_.data();
    for (;;) {
        Vertex* v;
        Vertex* u;
        int e0 = -1, ei = 0, ej = 0;
        // Grow the trees.
        while (first != nil) {
            v = first;
            if (v->parent) {
                const std::uint8_t vt = v->t;
                for (ei = v->first; ei != 0; ei = edges[ei].next) {
                    if (edges[ei ^ vt].weight == 0) continue;
                    u = verts + edges[ei].dst;
                    if (!u->parent) {
                        u->t = vt;
                        u->parent = ei ^ 1;
                        u->ts = v->ts;
                        u->dist = v->dist + 1;
                        if (!u->next) {
                            u->next = nil;
                            last = last->next = u;
                        }
                        continue;
                    }
                    if (u->t != vt) {
                        e0 = ei ^ vt;
                        break;
                    }
                    if (u->dist > v->dist + 1 && u->ts <= v->ts) {
                        u->parent = ei ^ 1;
                        u->ts = v->ts;
                        u->dist = v->dist + 1;
                    }
                }
                if (e0 > 0) break;
            }
            first = first->next;
            v->next = nullptr;
        }
        if (e0 <= 0) break;
        // Augment along the path found.
        double minWeight = edges[e0].weight;
        for (int k = 1; k >= 0; --k) {
            for (v = verts + edges[e0 ^ k].dst;; v = verts + edges[ei].dst) {
                if ((ei = v->parent) < 0) break;
                minWeight = std::min(minWeight, edges[ei ^ k].weight);
            }
            minWeight = std::min(minWeight, std::abs(v->weight));
        }
        edges[e0].weight -= minWeight;
        edges[e0 ^ 1].weight += minWeight;
        flow_ += minWeight;
        for (int k = 1; k >= 0; --k) {
            for (v = verts + edges[e0 ^ k].dst;; v = verts + edges[ei].dst) {
                if ((ei = v->parent) < 0) break;
                edges[ei ^ (k ^ 1)].weight += minWeight;
                if ((edges[ei ^ k].weight -= minWeight) == 0) {
                    orphans.push_back(v);
                    v->parent = kOrphan;
                }
            }
            v->weight = v->weight + minWeight * (1 - k * 2);
            if (v->weight == 0) {
                orphans.push_back(v);
                v->parent = kOrphan;
            }
        }
        // Adopt the orphans.
        ++currTs;
        while (!orphans.empty()) {
            Vertex* v2 = orphans.back();
            orphans.pop_back();
            int d, minDist = INT_MAX;
            e0 = 0;
            const std::uint8_t vt = v2->t;
            for (ei = v2->first; ei != 0; ei = edges[ei].next) {
                if (edges[ei ^ (vt ^ 1)].weight == 0) continue;
                u = verts + edges[ei].dst;
                if (u->t != vt || u->parent == 0) continue;
                for (d = 0;;) {  // the distance to the tree's root
                    if (u->ts == currTs) {
                        d += u->dist;
                        break;
                    }
                    ej = u->parent;
                    ++d;
                    if (ej < 0) {
                        if (ej == kOrphan) {
                            d = INT_MAX - 1;
                        } else {
                            u->ts = currTs;
                            u->dist = 1;
                        }
                        break;
                    }
                    u = verts + edges[ej].dst;
                }
                if (++d < INT_MAX) {
                    if (d < minDist) {
                        minDist = d;
                        e0 = ei;
                    }
                    for (u = verts + edges[ei].dst; u->ts != currTs; u = verts + edges[u->parent].dst) {
                        u->ts = currTs;
                        u->dist = --d;
                    }
                }
            }
            if ((v2->parent = e0) > 0) {
                v2->ts = currTs;
                v2->dist = minDist;
                continue;
            }
            v2->ts = 0;  // no parent: free, and its children become orphans
            for (ei = v2->first; ei != 0; ei = edges[ei].next) {
                u = verts + edges[ei].dst;
                ej = u->parent;
                if (u->t != vt || !ej) continue;
                if (edges[ei ^ (vt ^ 1)].weight != 0 && !u->next) {
                    u->next = nil;
                    last = last->next = u;
                }
                if (ej > 0 && verts + edges[ej].dst == v2) {
                    orphans.push_back(u);
                    u->parent = kOrphan;
                }
            }
        }
    }
    return flow_;
}

bool GraphCut::inSource(int i) const { return v_[static_cast<std::size_t>(i)].t == 0; }

// --- GrabCut ------------------------------------------------------------------------------------

namespace {

constexpr int kComponents = 5;

struct Gmm {
    std::array<double, kComponents> coef{};
    std::array<std::array<double, 3>, kComponents> mean{};
    std::array<std::array<double, 9>, kComponents> inv{};
    std::array<double, kComponents> det{};
    // Learning sums.
    std::array<std::array<double, 3>, kComponents> sums{};
    std::array<std::array<double, 9>, kComponents> prods{};
    std::array<int, kComponents> counts{};
    int total = 0;

    double component(int k, const double* c) const {
        if (coef[static_cast<std::size_t>(k)] <= 0.0) return 0.0;
        const auto& m = mean[static_cast<std::size_t>(k)];
        const auto& a = inv[static_cast<std::size_t>(k)];
        const double d0 = c[0] - m[0], d1 = c[1] - m[1], d2 = c[2] - m[2];
        const double mult = d0 * (d0 * a[0] + d1 * a[3] + d2 * a[6]) + d1 * (d0 * a[1] + d1 * a[4] + d2 * a[7]) +
                            d2 * (d0 * a[2] + d1 * a[5] + d2 * a[8]);
        return 1.0 / std::sqrt(det[static_cast<std::size_t>(k)]) * std::exp(-0.5 * mult);
    }
    double operator()(const double* c) const {
        double r = 0.0;
        for (int k = 0; k < kComponents; ++k) r += coef[static_cast<std::size_t>(k)] * component(k, c);
        return r;
    }
    int which(const double* c) const {
        int best = 0;
        double bestP = 0.0;
        for (int k = 0; k < kComponents; ++k) {
            const double p = component(k, c);
            if (p > bestP) {
                best = k;
                bestP = p;
            }
        }
        return best;
    }
    void beginLearning() {
        for (auto& s : sums) s.fill(0.0);
        for (auto& p : prods) p.fill(0.0);
        counts.fill(0);
        total = 0;
    }
    void add(int k, const double* c) {
        auto& s = sums[static_cast<std::size_t>(k)];
        auto& p = prods[static_cast<std::size_t>(k)];
        for (int a = 0; a < 3; ++a) {
            s[static_cast<std::size_t>(a)] += c[a];
            for (int b = 0; b < 3; ++b) p[static_cast<std::size_t>(3 * a + b)] += c[a] * c[b];
        }
        ++counts[static_cast<std::size_t>(k)];
        ++total;
    }
    void endLearning() {
        for (int k = 0; k < kComponents; ++k) {
            const auto ku = static_cast<std::size_t>(k);
            const int n = counts[ku];
            if (n == 0) {
                coef[ku] = 0.0;
                continue;
            }
            coef[ku] = static_cast<double>(n) / total;
            for (int a = 0; a < 3; ++a) mean[ku][static_cast<std::size_t>(a)] = sums[ku][static_cast<std::size_t>(a)] / n;
            std::array<double, 9> c{};
            for (int a = 0; a < 3; ++a)
                for (int b = 0; b < 3; ++b)
                    c[static_cast<std::size_t>(3 * a + b)] =
                        prods[ku][static_cast<std::size_t>(3 * a + b)] / n - mean[ku][static_cast<std::size_t>(a)] * mean[ku][static_cast<std::size_t>(b)];
            double d = c[0] * (c[4] * c[8] - c[5] * c[7]) - c[1] * (c[3] * c[8] - c[5] * c[6]) + c[2] * (c[3] * c[7] - c[4] * c[6]);
            if (d <= std::numeric_limits<double>::epsilon()) {  // singular: add white noise (cv2: 0.01)
                c[0] += 0.01;
                c[4] += 0.01;
                c[8] += 0.01;
                d = c[0] * (c[4] * c[8] - c[5] * c[7]) - c[1] * (c[3] * c[8] - c[5] * c[6]) + c[2] * (c[3] * c[7] - c[4] * c[6]);
            }
            det[ku] = d;
            auto& i = inv[ku];
            i[0] = (c[4] * c[8] - c[5] * c[7]) / d;
            i[1] = -(c[1] * c[8] - c[2] * c[7]) / d;
            i[2] = (c[1] * c[5] - c[2] * c[4]) / d;
            i[3] = -(c[3] * c[8] - c[5] * c[6]) / d;
            i[4] = (c[0] * c[8] - c[2] * c[6]) / d;
            i[5] = -(c[0] * c[5] - c[2] * c[3]) / d;
            i[6] = (c[3] * c[7] - c[4] * c[6]) / d;
            i[7] = -(c[0] * c[7] - c[1] * c[6]) / d;
            i[8] = (c[0] * c[4] - c[1] * c[3]) / d;
        }
    }
};

// k-means (k-means++ seeding from a fixed generator, 10 Lloyd iterations) of 3-vectors.
std::vector<int> kmeans(const std::vector<std::array<double, 3>>& x, int k) {
    const std::size_t n = x.size();
    std::vector<int> labels(n, 0);
    if (n == 0) return labels;
    k = std::min<int>(k, static_cast<int>(n));
    std::uint64_t state = 0x9E3779B97F4A7C15ull;
    auto uniform = [&]() {
        state = state * 6364136223846793005ull + 1442695040888963407ull;
        return static_cast<double>(state >> 11) * (1.0 / 9007199254740992.0);
    };
    auto d2 = [](const std::array<double, 3>& a, const std::array<double, 3>& b) {
        return (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]);
    };
    std::vector<std::array<double, 3>> centres;
    centres.push_back(x[std::min(n - 1, static_cast<std::size_t>(uniform() * n))]);
    std::vector<double> best(n, std::numeric_limits<double>::infinity());
    while (static_cast<int>(centres.size()) < k) {
        double sum = 0.0;
        for (std::size_t p = 0; p < n; ++p) {
            best[p] = std::min(best[p], d2(x[p], centres.back()));
            sum += best[p];
        }
        if (sum <= 0.0) break;
        double pick = uniform() * sum;
        std::size_t chosen = n - 1;
        for (std::size_t p = 0; p < n; ++p) {
            pick -= best[p];
            if (pick <= 0.0) {
                chosen = p;
                break;
            }
        }
        centres.push_back(x[chosen]);
    }
    for (int iter = 0; iter < 10; ++iter) {
        for (std::size_t p = 0; p < n; ++p) {
            double bd = std::numeric_limits<double>::infinity();
            for (std::size_t c = 0; c < centres.size(); ++c) {
                const double d = d2(x[p], centres[c]);
                if (d < bd) {
                    bd = d;
                    labels[p] = static_cast<int>(c);
                }
            }
        }
        std::vector<std::array<double, 3>> sums(centres.size(), {0.0, 0.0, 0.0});
        std::vector<int> counts(centres.size(), 0);
        for (std::size_t p = 0; p < n; ++p) {
            auto& s = sums[static_cast<std::size_t>(labels[p])];
            for (int c = 0; c < 3; ++c) s[static_cast<std::size_t>(c)] += x[p][static_cast<std::size_t>(c)];
            ++counts[static_cast<std::size_t>(labels[p])];
        }
        for (std::size_t c = 0; c < centres.size(); ++c)
            if (counts[c] > 0)
                for (int a = 0; a < 3; ++a) centres[c][static_cast<std::size_t>(a)] = sums[c][static_cast<std::size_t>(a)] / counts[c];
    }
    return labels;
}

bool isForeground(std::uint8_t m) { return m == kFgd || m == kPrFgd; }

}  // namespace

void grabCut(const std::vector<double>& rgb, int width, int height, std::vector<std::uint8_t>* mask, int iterations) {
    const std::size_t n = static_cast<std::size_t>(width) * height;
    if (n == 0) return;
    std::vector<std::uint8_t>& m = *mask;
    const double* px = rgb.data();
    Gmm bgd, fgd;
    {  // Initialise both mixtures by k-means.
        std::vector<std::array<double, 3>> bs, fs;
        for (std::size_t p = 0; p < n; ++p) (isForeground(m[p]) ? fs : bs).push_back({px[3 * p], px[3 * p + 1], px[3 * p + 2]});
        if (bs.empty() || fs.empty()) return;  // GrabCut needs both
        const std::vector<int> bl = kmeans(bs, kComponents), fl = kmeans(fs, kComponents);
        bgd.beginLearning();
        fgd.beginLearning();
        for (std::size_t i = 0; i < bs.size(); ++i) bgd.add(bl[i], bs[i].data());
        for (std::size_t i = 0; i < fs.size(); ++i) fgd.add(fl[i], fs[i].data());
        bgd.endLearning();
        fgd.endLearning();
    }
    // Smoothness: beta from the mean squared neighbour difference, gamma 50 (cv2).
    constexpr double kGamma = 50.0, kLambda = 9.0 * kGamma;
    auto diff2 = [&](std::size_t a, std::size_t b) {
        const double d0 = px[3 * a] - px[3 * b], d1 = px[3 * a + 1] - px[3 * b + 1], d2 = px[3 * a + 2] - px[3 * b + 2];
        return d0 * d0 + d1 * d1 + d2 * d2;
    };
    double beta = 0.0;
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            const std::size_t p = static_cast<std::size_t>(y) * width + x;
            if (x > 0) beta += diff2(p, p - 1);
            if (y > 0 && x > 0) beta += diff2(p, p - width - 1);
            if (y > 0) beta += diff2(p, p - width);
            if (y > 0 && x < width - 1) beta += diff2(p, p - width + 1);
        }
    const double pairs = 4.0 * width * height - 3.0 * width - 3.0 * height + 2.0;
    beta = beta <= std::numeric_limits<double>::epsilon() ? 0.0 : 1.0 / (2.0 * beta / pairs);
    const double gDiag = kGamma / std::sqrt(2.0);
    std::vector<int> comp(n);
    for (int iter = 0; iter < iterations; ++iter) {
        for (std::size_t p = 0; p < n; ++p) comp[p] = (isForeground(m[p]) ? fgd : bgd).which(px + 3 * p);
        bgd.beginLearning();
        fgd.beginLearning();
        for (std::size_t p = 0; p < n; ++p) (isForeground(m[p]) ? fgd : bgd).add(comp[p], px + 3 * p);
        bgd.endLearning();
        fgd.endLearning();
        GraphCut graph(static_cast<int>(n), static_cast<int>(4 * n));
        for (int y = 0; y < height; ++y)
            for (int x = 0; x < width; ++x) {
                const std::size_t p = static_cast<std::size_t>(y) * width + x;
                const int v = static_cast<int>(p);
                double source, sink;
                if (m[p] == kPrBgd || m[p] == kPrFgd) {
                    source = -std::log(std::max(bgd(px + 3 * p), 1e-300));
                    sink = -std::log(std::max(fgd(px + 3 * p), 1e-300));
                } else if (m[p] == kBgd) {
                    source = 0.0;
                    sink = kLambda;
                } else {
                    source = kLambda;
                    sink = 0.0;
                }
                graph.addTerminal(v, source, sink);
                if (x > 0) {
                    const double w = kGamma * std::exp(-beta * diff2(p, p - 1));
                    graph.addEdge(v, v - 1, w, w);
                }
                if (x > 0 && y > 0) {
                    const double w = gDiag * std::exp(-beta * diff2(p, p - width - 1));
                    graph.addEdge(v, v - width - 1, w, w);
                }
                if (y > 0) {
                    const double w = kGamma * std::exp(-beta * diff2(p, p - width));
                    graph.addEdge(v, v - width, w, w);
                }
                if (x < width - 1 && y > 0) {
                    const double w = gDiag * std::exp(-beta * diff2(p, p - width + 1));
                    graph.addEdge(v, v - width + 1, w, w);
                }
            }
        graph.maxFlow();
        for (std::size_t p = 0; p < n; ++p)
            if (m[p] == kPrBgd || m[p] == kPrFgd) m[p] = graph.inSource(static_cast<int>(p)) ? kPrFgd : kPrBgd;
    }
}

}  // namespace lo::smart
