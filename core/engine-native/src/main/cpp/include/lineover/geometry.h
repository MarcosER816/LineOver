// Geometry and optics (Stage 9): the geometry op of the graph is ONE 3 x 3 matrix (crop,
// straighten, rotate / flip, Upright, manual transform and perspective crop composed by
// Kotlin's GeometryMath) plus Brown-Conrady lens distortion and a radial scale per channel
// for chromatic aberration. Pixels are resampled once: every output pixel maps back to the
// source (inverse mapping) and the source is sampled with OpenCV's bicubic (Keys a = -0.75,
// exact float positions as OpenCV 5 uses for float images, border 0), so the CPU path
// matches cv2.remap / cv2.warpPerspective (tools/golden/reference.py: geometry_warp).
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "lineover/graph.h"
#include "lineover/ops.h"

namespace lo {

struct Geometry {
    // Output normalised (u, v) in 0..1 -> source pixel coordinates (edges at 0 and width),
    // before lens distortion. Row major.
    double matrix[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    int outputWidth = 0;   // output size in pixels (the cropped, transformed image)
    int outputHeight = 0;
    int sourceWidth = 0;   // the source the matrix refers to (0: the image it is applied to)
    int sourceHeight = 0;
    double k1 = 0.0, k2 = 0.0;           // radial distortion, r normalised by the half diagonal
    double caRed = 0.0, caBlue = 0.0;    // per-channel radial scale - 1 (green is the reference)
    // A lens profile (Lensfun, converted exactly): distortion s += p1 r + p2 r^2 + p3 r^3 + p4 r^4,
    // and per channel scale += tv + tc rd + tb rd^2 on the distorted radius rd = r s.
    double dist[4] = {0, 0, 0, 0};
    double tcaRed[3] = {0, 0, 0}, tcaBlue[3] = {0, 0, 0};
    float vignette = 0.f, vignetteMid = 50.f;  // lens vignetting correction (a source-space op)

    bool hasProfile() const {
        for (double d : dist)
            if (d != 0.0) return true;
        for (int i = 0; i < 3; ++i)
            if (tcaRed[i] != 0.0 || tcaBlue[i] != 0.0) return true;
        return false;
    }
    bool hasLens() const { return k1 != 0.0 || k2 != 0.0 || caRed != 0.0 || caBlue != 0.0 || hasProfile(); }
    // The green (reference) channel's radial scale s(r) at r^2 = r2 (r = 1 at the corner).
    double radialScale(double r2) const;
};

// The geometry op of the bottom layer, if any (with a usable output size).
bool geometryOf(const Graph& graph, Geometry* out);

// Where output point (u, v) samples the source for `channel` (0 R, 1 G, 2 B): pixel
// coordinates of a source of `sourceWidth` x `sourceHeight` (the geometry's own source size),
// edges at 0 and width (subtract 0.5 for pixel centres).
void mapToSource(const Geometry& g, int sourceWidth, int sourceHeight, double u, double v, int channel, double* x, double* y);

// The forward map: source pixel (x, y) (distorted, as stored) -> output pixel (ox, oy),
// undistorting radially (Newton) and applying the inverse matrix. False when the point is
// behind the projection (it has no output position).
bool sourceToOutput(const Geometry& g, int sourceWidth, int sourceHeight, double x, double y, double* ox, double* oy);

// The output pixels a source rect [x, y, w, h] (source pixels) covers: the bounding box of
// its forward-mapped border, 3 px margin, clamped to the output. False when empty.
bool outputRectOf(const Geometry& g, int sourceWidth, int sourceHeight, const double sourceRect[4], double out[4]);

// Output pixels per source pixel near output point (u, v) (normalised): picks the tile level.
double outputScaleAt(const Geometry& g, int sourceWidth, int sourceHeight, double u, double v);

// OpenCV's cubic interpolation weights for fraction t (Keys, a = -0.75).
void cubicWeights(float t, float w[4]);

// Renders output pixels [x0, x0 + width) x [y0, y0 + height) of the geometry from `source`
// (RGBA, straight alpha). The geometry refers to a source of sourceWidth x sourceHeight
// pixels; `source` may be that image scaled (srcW x srcH: a preview or a thumbnail) and its
// part [srcX0, srcX0 + srcW) x [srcY0, ...) in those scaled pixels (a footprint of tiles);
// `outputScale` scales the output grid the same way (output pixel = full output pixel * scale).
struct WarpSource {
    const float* rgba = nullptr;
    int width = 0, height = 0;   // buffer size
    int x0 = 0, y0 = 0;          // its offset in the (scaled) source image
    double scale = 1.0;          // buffer pixels per full source pixel
};
void warp(const Geometry& g, int sourceWidth, int sourceHeight, const WarpSource& source, double outputScale, int x0, int y0,
          int width, int height, float* out);

// The source pixels (full resolution, clamped to the image) that output pixels
// [x0, x0 + w) x [y0, y0 + h) read, cubic taps included: x, y, width, height.
void footprint(const Geometry& g, int sourceWidth, int sourceHeight, int x0, int y0, int w, int h, int out[4]);

// Lens vignetting correction (reference.vignetting): RGB x 2^(amount/100 * f^2) / (1 + k1 r^2
// + k2 r^4 + k3 r^6) in source space (the second factor: a lens profile's "pa" model, r = 1 at
// the corner; `profile` may be null),
// space, applied before every other op.
std::unique_ptr<Op> makeVignetting(float amount, float midpoint, float aspect, const float profile[3] = nullptr);

// An RGBA8 image (the Ultra HDR gain map) warped with the same geometry: `sourceWidth` x
// `sourceHeight` is the photo's size, the gain map may be smaller; the result has the
// output's aspect at the gain map's resolution (outWidth x outHeight).
std::vector<std::uint8_t> warpRgba8(const Geometry& g, int sourceWidth, int sourceHeight, const std::uint8_t* rgba, int width,
                                    int height, int outWidth, int outHeight);

}  // namespace lo
