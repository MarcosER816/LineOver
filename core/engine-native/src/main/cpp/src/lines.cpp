#include "lineover/lines.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <numeric>
#include <vector>

#include "op_util.h"

namespace lo {
namespace {

constexpr double kPi = 3.14159265358979323846;

struct Gray {
    int w = 0, h = 0;
    std::vector<float> v;
    float at(int x, int y) const { return v[static_cast<std::size_t>(y) * w + x]; }
};

// Encoded luminance (0..255, as LSD expects 8-bit levels), box-filtered to <= maxEdge.
Gray grayOf(const float* rgba, int width, int height, int maxEdge) {
    const int factor = std::max(1, (std::max(width, height) + maxEdge - 1) / maxEdge);
    Gray g;
    g.w = width / factor;
    g.h = height / factor;
    g.v.assign(static_cast<std::size_t>(g.w) * g.h, 0.f);
    for (int y = 0; y < g.h; ++y) {
        for (int x = 0; x < g.w; ++x) {
            double sum = 0.0;
            for (int j = 0; j < factor; ++j) {
                for (int i = 0; i < factor; ++i) {
                    const float* p = rgba + 4 * (static_cast<std::size_t>(y * factor + j) * width + (x * factor + i));
                    sum += 0.2627 * p[0] + 0.6780 * p[1] + 0.0593 * p[2];
                }
            }
            const float lum = static_cast<float>(sum / (factor * factor));
            g.v[static_cast<std::size_t>(y) * g.w + x] = 255.f * std::clamp(opsutil::encode1(lum), 0.f, 1.f);
        }
    }
    return g;
}

// Separable Gaussian, sigma 0.75 (LSD: 0.6 / scale 0.8): staircase edges become smooth
// level lines, so a slanted line grows as one region instead of one per pixel step.
void gaussianSmooth(Gray& g) {
    const float k[5] = {0.0303f, 0.2172f, 0.5050f, 0.2172f, 0.0303f};
    std::vector<float> tmp(g.v.size());
    for (int y = 0; y < g.h; ++y)
        for (int x = 0; x < g.w; ++x) {
            float s = 0.f;
            for (int i = -2; i <= 2; ++i) s += k[i + 2] * g.at(std::clamp(x + i, 0, g.w - 1), y);
            tmp[static_cast<std::size_t>(y) * g.w + x] = s;
        }
    for (int y = 0; y < g.h; ++y)
        for (int x = 0; x < g.w; ++x) {
            float s = 0.f;
            for (int i = -2; i <= 2; ++i) s += k[i + 2] * tmp[static_cast<std::size_t>(std::clamp(y + i, 0, g.h - 1)) * g.w + x];
            g.v[static_cast<std::size_t>(y) * g.w + x] = s;
        }
}

double angleDiff(double a, double b) {
    double d = std::fabs(a - b);
    while (d > kPi) d = std::fabs(d - 2 * kPi);
    return d;
}

// Joins pieces of one straight edge that pixel steps split (same direction within 2 deg,
// on one line within 1.5 px, gaps up to 2 % of the image): vanishing points want long lines.
std::vector<LineSegment> mergeCollinear(std::vector<LineSegment> segments, int w, int h) {
    auto px = [&](const LineSegment& s, double* x1, double* y1, double* x2, double* y2) {
        *x1 = s.x1 * w;
        *y1 = s.y1 * h;
        *x2 = s.x2 * w;
        *y2 = s.y2 * h;
    };
    const double maxGap = 0.02 * std::max(w, h);
    bool merged = true;
    while (merged) {
        merged = false;
        for (std::size_t i = 0; i < segments.size() && !merged; ++i) {
            for (std::size_t j = i + 1; j < segments.size() && !merged; ++j) {
                double ax1, ay1, ax2, ay2, bx1, by1, bx2, by2;
                px(segments[i], &ax1, &ay1, &ax2, &ay2);
                px(segments[j], &bx1, &by1, &bx2, &by2);
                const double la = std::hypot(ax2 - ax1, ay2 - ay1);
                const double dx = (ax2 - ax1) / la, dy = (ay2 - ay1) / la;
                const double lb = std::hypot(bx2 - bx1, by2 - by1);
                const double cross = std::fabs(dx * (by2 - by1) - dy * (bx2 - bx1)) / lb;
                if (cross > std::sin(2.0 * kPi / 180.0)) continue;
                const double d1 = std::fabs(-(bx1 - ax1) * dy + (by1 - ay1) * dx);
                const double d2 = std::fabs(-(bx2 - ax1) * dy + (by2 - ay1) * dx);
                if (d1 > 1.5 || d2 > 1.5) continue;
                // Positions along a's direction; merge when the intervals overlap or nearly touch.
                const double t[4] = {0.0, la, (bx1 - ax1) * dx + (by1 - ay1) * dy, (bx2 - ax1) * dx + (by2 - ay1) * dy};
                const double bmin = std::min(t[2], t[3]), bmax = std::max(t[2], t[3]);
                if (bmin > la + maxGap || bmax < -maxGap) continue;
                const double lo = std::min(0.0, bmin), hi = std::max(la, bmax);
                LineSegment m;
                m.x1 = static_cast<float>((ax1 + lo * dx) / w);
                m.y1 = static_cast<float>((ay1 + lo * dy) / h);
                m.x2 = static_cast<float>((ax1 + hi * dx) / w);
                m.y2 = static_cast<float>((ay1 + hi * dy) / h);
                m.width = std::max(segments[i].width, segments[j].width);
                segments[i] = m;
                segments.erase(segments.begin() + static_cast<std::ptrdiff_t>(j));
                merged = true;
            }
        }
    }
    return segments;
}

}  // namespace

std::vector<LineSegment> detectSegments(const float* rgba, int width, int height) {
    Gray g = grayOf(rgba, width, height, 1024);
    gaussianSmooth(g);
    const int w = g.w, h = g.h;
    std::vector<LineSegment> out;
    if (w < 8 || h < 8) return out;
    // LSD parameters: angle tolerance 22.5 deg, gradient quantisation q = 2.
    const double tau = kPi / 8.0;
    const double rho = 2.0 / std::sin(tau);
    std::vector<float> mag(static_cast<std::size_t>(w) * h, 0.f), ang(mag.size(), 0.f);
    float maxMag = 0.f;
    for (int y = 0; y < h - 1; ++y) {
        for (int x = 0; x < w - 1; ++x) {
            const float a = g.at(x, y), b = g.at(x + 1, y), c = g.at(x, y + 1), d = g.at(x + 1, y + 1);
            const float gx = 0.5f * (b + d - a - c);
            const float gy = 0.5f * (c + d - a - b);
            const float m = std::hypot(gx, gy);
            const std::size_t k = static_cast<std::size_t>(y) * w + x;
            mag[k] = m;
            ang[k] = static_cast<float>(std::atan2(gx, -gy));  // level-line angle
            maxMag = std::max(maxMag, m);
        }
    }
    if (maxMag <= rho) return out;
    // Pseudo-ordering: strongest gradients seed first (1024 bins).
    constexpr int kBins = 1024;
    std::vector<std::vector<std::size_t>> bins(kBins);
    for (std::size_t k = 0; k < mag.size(); ++k) {
        if (mag[k] <= rho) continue;
        const int bin = std::min(kBins - 1, static_cast<int>(mag[k] / maxMag * (kBins - 1)));
        bins[bin].push_back(k);
    }
    std::vector<unsigned char> used(mag.size(), 0);
    const double minLength = 0.03 * std::max(w, h);
    std::vector<std::size_t> region;
    for (int bin = kBins - 1; bin >= 0; --bin) {
        for (const std::size_t seed : bins[bin]) {
            if (used[seed]) continue;
            // Region growing by level-line angle.
            region.clear();
            region.push_back(seed);
            used[seed] = 1;
            double sx = std::cos(ang[seed]), sy = std::sin(ang[seed]);
            double regAngle = ang[seed];
            for (std::size_t r = 0; r < region.size(); ++r) {
                const int px = static_cast<int>(region[r] % w), py = static_cast<int>(region[r] / w);
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int nx = px + dx, ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= w - 1 || ny >= h - 1) continue;
                        const std::size_t k = static_cast<std::size_t>(ny) * w + nx;
                        if (used[k] || mag[k] <= rho || angleDiff(ang[k], regAngle) > tau) continue;
                        used[k] = 1;
                        region.push_back(k);
                        sx += std::cos(ang[k]);
                        sy += std::sin(ang[k]);
                        regAngle = std::atan2(sy, sx);
                    }
                }
            }
            if (region.size() < 12) continue;
            // Rectangle: centre and orientation from the magnitude-weighted inertia.
            double sw = 0.0, cx = 0.0, cy = 0.0;
            for (const std::size_t k : region) {
                sw += mag[k];
                cx += mag[k] * static_cast<double>(k % w);
                cy += mag[k] * static_cast<double>(k / w);
            }
            cx /= sw;
            cy /= sw;
            double ixx = 0.0, iyy = 0.0, ixy = 0.0;
            for (const std::size_t k : region) {
                const double dx = static_cast<double>(k % w) - cx, dy = static_cast<double>(k / w) - cy;
                ixx += mag[k] * dy * dy;
                iyy += mag[k] * dx * dx;
                ixy -= mag[k] * dx * dy;
            }
            const double lambda = 0.5 * (ixx + iyy - std::sqrt((ixx - iyy) * (ixx - iyy) + 4.0 * ixy * ixy));
            double theta = std::fabs(ixx) > std::fabs(iyy) ? std::atan2(lambda - ixx, ixy) : std::atan2(ixy, lambda - iyy);
            if (angleDiff(theta, regAngle) > tau) theta += kPi;
            const double dx = std::cos(theta), dy = std::sin(theta);
            double lmin = 0, lmax = 0, wmin = 0, wmax = 0;
            for (const std::size_t k : region) {
                const double rx = static_cast<double>(k % w) - cx, ry = static_cast<double>(k / w) - cy;
                const double l = rx * dx + ry * dy, t = -rx * dy + ry * dx;
                lmin = std::min(lmin, l);
                lmax = std::max(lmax, l);
                wmin = std::min(wmin, t);
                wmax = std::max(wmax, t);
            }
            const double length = lmax - lmin, width = std::max(1.0, wmax - wmin);
            const double density = static_cast<double>(region.size()) / (length * width);
            if (length < minLength || density < 0.7 || width > 0.25 * length) continue;
            // Pixel (x, y) of the 2 x 2 gradient sits at the centre (x + 1, y + 1) of its cell.
            LineSegment s;
            s.x1 = static_cast<float>((cx + lmin * dx + 1.0) / w);
            s.y1 = static_cast<float>((cy + lmin * dy + 1.0) / h);
            s.x2 = static_cast<float>((cx + lmax * dx + 1.0) / w);
            s.y2 = static_cast<float>((cy + lmax * dy + 1.0) / h);
            s.width = static_cast<float>(width);
            out.push_back(s);
        }
    }
    return mergeCollinear(std::move(out), w, h);
}

bool horizonAngle(const float* rgba, int width, int height, float* degrees) {
    Gray g = grayOf(rgba, width, height, 512);
    const int w = g.w, h = g.h;
    if (w < 16 || h < 16) return false;
    // Canny: Gaussian (sigma 1.4, 5 taps), Sobel, non-maximum suppression, hysteresis.
    const float kernel[5] = {0.0545f, 0.2442f, 0.4026f, 0.2442f, 0.0545f};
    std::vector<float> tmp(g.v.size()), blur(g.v.size());
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float s = 0.f;
            for (int i = -2; i <= 2; ++i) s += kernel[i + 2] * g.at(std::clamp(x + i, 0, w - 1), y);
            tmp[static_cast<std::size_t>(y) * w + x] = s;
        }
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float s = 0.f;
            for (int i = -2; i <= 2; ++i) s += kernel[i + 2] * tmp[static_cast<std::size_t>(std::clamp(y + i, 0, h - 1)) * w + x];
            blur[static_cast<std::size_t>(y) * w + x] = s;
        }
    auto B = [&](int x, int y) { return blur[static_cast<std::size_t>(std::clamp(y, 0, h - 1)) * w + std::clamp(x, 0, w - 1)]; };
    std::vector<float> mag(g.v.size(), 0.f), gxs(g.v.size()), gys(g.v.size());
    for (int y = 1; y < h - 1; ++y)
        for (int x = 1; x < w - 1; ++x) {
            const float gx = B(x + 1, y - 1) + 2 * B(x + 1, y) + B(x + 1, y + 1) - B(x - 1, y - 1) - 2 * B(x - 1, y) - B(x - 1, y + 1);
            const float gy = B(x - 1, y + 1) + 2 * B(x, y + 1) + B(x + 1, y + 1) - B(x - 1, y - 1) - 2 * B(x, y - 1) - B(x + 1, y - 1);
            const std::size_t k = static_cast<std::size_t>(y) * w + x;
            mag[k] = std::hypot(gx, gy);
            gxs[k] = gx;
            gys[k] = gy;
        }
    std::vector<float> sorted;
    for (float m : mag)
        if (m > 0.f) sorted.push_back(m);
    if (sorted.size() < 32) return false;
    std::nth_element(sorted.begin(), sorted.begin() + static_cast<std::ptrdiff_t>(sorted.size() * 9 / 10), sorted.end());
    const float high = std::max(20.f, sorted[sorted.size() * 9 / 10]);
    const float low = 0.4f * high;
    std::vector<unsigned char> edge(mag.size(), 0);  // 1 weak, 2 strong
    for (int y = 1; y < h - 1; ++y)
        for (int x = 1; x < w - 1; ++x) {
            const std::size_t k = static_cast<std::size_t>(y) * w + x;
            const float m = mag[k];
            if (m < low) continue;
            const double a = std::atan2(gys[k], gxs[k]);
            const int ox = static_cast<int>(std::lround(std::cos(a))), oy = static_cast<int>(std::lround(std::sin(a)));
            const float m1 = mag[static_cast<std::size_t>(y + oy) * w + (x + ox)];
            const float m2 = mag[static_cast<std::size_t>(y - oy) * w + (x - ox)];
            if (m < m1 || m < m2) continue;
            edge[k] = m >= high ? 2 : 1;
        }
    // Hysteresis: weak edges connected to strong ones.
    std::vector<std::size_t> stack;
    for (std::size_t k = 0; k < edge.size(); ++k)
        if (edge[k] == 2) stack.push_back(k);
    while (!stack.empty()) {
        const std::size_t k = stack.back();
        stack.pop_back();
        const int x = static_cast<int>(k % w), y = static_cast<int>(k / w);
        for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
                const int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                const std::size_t n = static_cast<std::size_t>(ny) * w + nx;
                if (edge[n] == 1) {
                    edge[n] = 2;
                    stack.push_back(n);
                }
            }
    }
    // Hough over line normals 45..135 deg (lines within 45 deg of horizontal), 0.25 deg steps.
    constexpr int kAngles = 361;
    const int rhoMax = static_cast<int>(std::ceil(std::hypot(w, h)));
    std::vector<int> acc(static_cast<std::size_t>(kAngles) * (2 * rhoMax + 1), 0);
    std::vector<double> cs(kAngles), sn(kAngles);
    for (int a = 0; a < kAngles; ++a) {
        const double phi = (45.0 + 0.25 * a) * kPi / 180.0;
        cs[a] = std::cos(phi);
        sn[a] = std::sin(phi);
    }
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            if (edge[static_cast<std::size_t>(y) * w + x] != 2) continue;
            for (int a = 0; a < kAngles; ++a) {
                const int r = static_cast<int>(std::lround(x * cs[a] + y * sn[a])) + rhoMax;
                ++acc[static_cast<std::size_t>(a) * (2 * rhoMax + 1) + r];
            }
        }
    const auto best = std::max_element(acc.begin(), acc.end());
    if (*best < w / 4) return false;  // a line across at least a quarter of the width
    const int a = static_cast<int>((best - acc.begin()) / (2 * rhoMax + 1));
    // Normal angle phi; the line is at phi - 90 (y down: positive = clockwise on screen).
    *degrees = static_cast<float>(45.0 + 0.25 * a - 90.0);
    return true;
}

bool estimateChromaticAberration(const float* rgba, int width, int height, float* caRed, float* caBlue) {
    if (width < 32 || height < 32) return false;
    const double cx = width / 2.0, cy = height / 2.0, radius = 0.5 * std::hypot(width, height);
    auto at = [&](int c, double x, double y) {
        // Bilinear, clamped: sub-pixel positions matter (CA is a fraction of a pixel here).
        x = std::clamp(x - 0.5, 0.0, width - 1.001);
        y = std::clamp(y - 0.5, 0.0, height - 1.001);
        const int ix = static_cast<int>(x), iy = static_cast<int>(y);
        const double fx = x - ix, fy = y - iy;
        auto p = [&](int xx, int yy) { return static_cast<double>(rgba[4 * (static_cast<std::size_t>(yy) * width + xx) + c]); };
        return (1 - fy) * ((1 - fx) * p(ix, iy) + fx * p(ix + 1, iy)) + fy * ((1 - fx) * p(ix, iy + 1) + fx * p(ix + 1, iy + 1));
    };
    // Edge points away from the centre (CA grows with the radius), strongest radial
    // green gradients first.
    struct Point {
        double x, y, nx, ny, dg;
    };
    std::vector<Point> points;
    for (int y = 2; y < height - 2; y += 2)
        for (int x = 2; x < width - 2; x += 2) {
            const double qx = x + 0.5 - cx, qy = y + 0.5 - cy;
            const double r = std::hypot(qx, qy);
            if (r < 0.3 * radius) continue;
            const double nx = qx / r, ny = qy / r;
            const double dg = at(1, x + 0.5 + nx, y + 0.5 + ny) - at(1, x + 0.5 - nx, y + 0.5 - ny);
            points.push_back({x + 0.5, y + 0.5, nx, ny, dg});
        }
    std::sort(points.begin(), points.end(), [](const Point& a, const Point& b) { return std::fabs(a.dg) > std::fabs(b.dg); });
    if (points.empty()) return false;
    // Real edges only: at least a fifth of the strongest radial step.
    const double floor = 0.2 * std::fabs(points.front().dg);
    points.erase(std::find_if(points.begin(), points.end(), [&](const Point& p) { return std::fabs(p.dg) < floor; }), points.end());
    points.resize(std::min<std::size_t>(points.size(), 4000));
    if (points.size() < 50 || floor <= 1e-4) return false;
    // For a scale s, the channel's radial derivative sampled at the scaled position should
    // match green's: maximise their normalised correlation.
    auto score = [&](int c, double s) {
        double sab = 0, saa = 0, sbb = 0;
        for (const Point& p : points) {
            const double x = cx + (p.x - cx) * s, y = cy + (p.y - cy) * s;
            const double d = at(c, x + p.nx, y + p.ny) - at(c, x - p.nx, y - p.ny);
            sab += d * p.dg;
            saa += d * d;
            sbb += p.dg * p.dg;
        }
        return saa > 0 && sbb > 0 ? sab / std::sqrt(saa * sbb) : -1.0;
    };
    auto search = [&](int c) {
        double best = 0.0, bestScore = -2.0;
        for (int i = -40; i <= 40; ++i) {  // +-1 % in 0.025 % steps, then refined
            const double e = i * 0.00025;
            const double sc = score(c, 1.0 + e);
            if (sc > bestScore) {
                bestScore = sc;
                best = e;
            }
        }
        for (int i = -10; i <= 10; ++i) {
            const double e = best + i * 0.000025;
            const double sc = score(c, 1.0 + e);
            if (sc > bestScore) {
                bestScore = sc;
                best = e;
            }
        }
        return best;
    };
    *caRed = static_cast<float>(search(0));
    *caBlue = static_cast<float>(search(2));
    return true;
}

}  // namespace lo
