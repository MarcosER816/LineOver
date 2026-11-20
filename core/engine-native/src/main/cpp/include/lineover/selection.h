// Selections (Stage 15): a recipe of operations (graph.h SelectionOp) evaluated on a grid of
// buffer pixels into a soft mask (0..1, rounded to R16F), and its outline for the marching
// ants. tools/golden/selection_reference.py is the reference (ctest selection_test):
// the flood fill equals cv2.floodFill, the distance transform cv2.distanceTransform
// (DIST_MASK_PRECISE), feather cv2.GaussianBlur.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "lineover/graph.h"

namespace lo::selection {

// The pixels 4- or 8-connected to (seedX, seedY) whose [within] is non-zero (out: 0 / 1).
// A scanline fill: spans, not single pixels, go on the stack.
void floodFill(const std::uint8_t* within, int width, int height, int seedX, int seedY, int connectivity,
               std::uint8_t* out);

// cv2.floodFill(FLOODFILL_FIXED_RANGE | MASK_ONLY): pixels within [seed - low, seed + high].
void floodFillRange(const std::uint8_t* image, int width, int height, int seedX, int seedY, int low, int high,
                    int connectivity, std::uint8_t* out);

// For every pixel, the Euclidean distance to the nearest pixel whose [nonzero] is 0 (0 there):
// cv2.distanceTransform(DIST_L2, DIST_MASK_PRECISE), exact (Felzenszwalb & Huttenlocher).
void distanceTransform(const std::uint8_t* nonzero, int width, int height, float* out);

// Grow (r > 0) or shrink (r < 0) by r buffer pixels; border of [width] pixels; smooth.
void grow(std::vector<float>* s, int width, int height, double r);
void border(std::vector<float>* s, int width, int height, double band);
void smooth(std::vector<float>* s, int width, int height, double r);

// The grid a selection lives on: width x height buffer pixels at [scale] (buffer px per
// document px); [rgba] (working space, optional) is what the wand and color range read.
struct Grid {
    int width = 0;
    int height = 0;
    double scale = 1.0;
    const float* rgba = nullptr;       // all layers (the composite)
    const float* rgbaPhoto = nullptr;  // the photo alone ("sample all layers" off); null = rgba
};

// The selection of [ops] on [grid], from nothing (R16F-rounded values).
std::vector<float> evaluate(const std::vector<SelectionOp>& ops, const Grid& grid);

// Marching squares at 0.5: segments (x0, y0, x1, y1) in document px.
void contours(const float* s, int width, int height, double scale, std::vector<float>* segments);

}  // namespace lo::selection
