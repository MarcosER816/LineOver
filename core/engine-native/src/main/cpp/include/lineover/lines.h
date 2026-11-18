// Image analysis for Crop and Optics (Stage 9), on the preview, any thread:
// - line segments for Upright (LSD-style: level-line region growing, Grompone von Gioi et
//   al. 2012, without the a-contrario NFA test; segments are kept by density and length),
// - the horizon for auto-straighten (Canny edges + a Hough vote over near-horizontal lines),
// - chromatic aberration (the radial scale of red / blue that best re-aligns their edges
//   with green).
#pragma once

#include <vector>

namespace lo {

struct LineSegment {
    float x1, y1, x2, y2;  // normalised image coordinates (0..1)
    float width;           // in pixels of the analysed image
};

// Segments of an RGBA working-space image (any size; analysed at <= 1024 px).
std::vector<LineSegment> detectSegments(const float* rgba, int width, int height);

// The tilt of the dominant near-horizontal line (degrees, within +-45, positive =
// clockwise on screen); false when no line is strong enough.
bool horizonAngle(const float* rgba, int width, int height, float* degrees);

// Red / blue radial scale - 1 (as Geometry::caRed / caBlue) that undoes lateral chromatic
// aberration; false when the image has too few edges.
bool estimateChromaticAberration(const float* rgba, int width, int height, float* caRed, float* caBlue);

}  // namespace lo
