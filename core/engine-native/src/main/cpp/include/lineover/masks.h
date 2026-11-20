// Layer masks (Stage 14): a mask's paint (round brush strokes, linear gradients; graph.h) is
// rasterised at buffer pixel centres, inverted, feathered with OpenCV's Gaussian
// (GaussianBlur: ksize = round(8 sigma + 1) | 1, BORDER_REFLECT_101 at the document edges)
// and scaled by density, then kept as R16F (half-float) tiles. Any region of the document can
// be rendered on its own: it reads a border of neighbour pixels (the blur's reach) first, so
// tiles meet without seams. tools/golden/blend_reference.py is the reference (ctest mask_test).
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "lineover/graph.h"

namespace lo::masks {

std::uint16_t toHalf(float value);  // IEEE half, round to nearest even
float fromHalf(std::uint16_t half);

// A mask held as R16F tiles (kTile x kTile).
class MaskTiles {
public:
    static constexpr int kTile = 256;
    void reset(int width, int height);
    int width() const { return width_; }
    int height() const { return height_; }
    void set(int x, int y, float value);
    float at(int x, int y) const;

private:
    int width_ = 0, height_ = 0, columns_ = 0;
    std::vector<std::vector<std::uint16_t>> tiles_;
};

// Saved selections (Stage 15; MaskPaint "raster"): R16F-rounded rasters by name, process-wide.
void registerRaster(const std::string& name, std::shared_ptr<const std::vector<float>> values, int width, int height);
void unregisterRaster(const std::string& name);
bool hasRaster(const std::string& name);
// The registered raster [name]; false when there is none.
bool findRaster(const std::string& name, std::shared_ptr<const std::vector<float>>* values, int* width, int* height);

// OpenCV's Gaussian kernel for [sigma] (pixels); empty when sigma <= 0.
std::vector<double> gaussianKernel(double sigma);

// A separable Gaussian over a whole w x h image with BORDER_REFLECT_101 (cv::GaussianBlur).
void gaussianBlur(const float* in, int width, int height, double sigma, float* out);

// The raw mask (base and paint, or the components; before invert / feather / density) at buffer pixels
// (x0 + i, y0 + j), i.e. document points ((x0 + i + 0.5) / scale, (y0 + j + 0.5) / scale).
// [guideRgba] (optional, Stage 16): the image under those pixels (working space, RGBA); rasters
// with a guide are then upsampled by joint bilateral filtering against it.
void rasterize(const LayerMask& mask, int x0, int y0, int width, int height, double scale, float* out,
               const float* guideRgba = nullptr);

// Stage 17: local mask components (graph.h MaskComponent). [image] is the region's pixels (the
// layer's input, RGBA working space); components that read it (colour / luminance range, the
// brush's auto-mask, guided rasters) select nothing without it.
bool componentReadsImage(const MaskComponent& component);
bool readsImage(const LayerMask& mask);
// One component's A (0..1, inverted when it says so) at buffer pixels (x0 + i, y0 + j).
void evaluateComponent(const MaskComponent& component, int x0, int y0, int width, int height, double scale, float* out,
                       const float* image);
// M combined with A: add max, subtract min(M, 1 - A), intersect min(M, A).
float combine(float m, float a, const std::string& mode);
// The group: every component combined in order from 0 (rasterize() calls it when there are any).
void rasterizeComponents(const LayerMask& mask, int x0, int y0, int width, int height, double scale, float* out,
                         const float* image);

// Which buffer pixels a mask is rendered for: [originX, originX + width) x [originY, ...)
// of a document that is docWidth x docHeight buffer pixels at [scale].
struct MaskRegion {
    int originX = 0, originY = 0, width = 0, height = 0;
    int docWidth = 0, docHeight = 0;
    double scale = 1.0;
};

// The final mask (1 = shown) of [region] into [out] (R16F tiles of the region's size).
// [guideRgba] (optional, Stage 16): the region's image (exports pass the full-resolution
// photo); used for guided rasters when the mask has no feather (a feather's reach would read
// pixels outside the region, so feathered masks stay bilinear and tiles never differ).
void render(const LayerMask& mask, const MaskRegion& region, MaskTiles* out, const float* guideRgba = nullptr);

}  // namespace lo::masks
