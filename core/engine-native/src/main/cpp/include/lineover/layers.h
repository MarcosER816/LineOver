// Layers (Stage 13): the 27 blend modes and the compositor that draws a graph's layers over
// its rendered background. tools/golden/blend_reference.py is the reference; ctest
// blend_test checks every mode (both blending spaces) and the compositor scene against it.
//
// Blending is the W3C Compositing formula on non-premultiplied colour, in the document's
// blending space ("gamma": the sRGB curve on the working primaries, the default; "linear").
// Layers are composited in document output space (after the background's geometry), so the
// compositor works on any buffer that maps to the document: the preview, a thumbnail, an
// export band.
#pragma once

#include <memory>
#include <string>

#include "lineover/graph.h"
#include "lineover/thumbnail.h"

namespace lo::layers {

enum class BlendMode : int {
    Normal = 0, Dissolve, Darken, Multiply, ColorBurn, LinearBurn, DarkerColor, Lighten, Screen, ColorDodge,
    LinearDodge, LighterColor, Overlay, SoftLight, HardLight, VividLight, LinearLight, PinLight, HardMix,
    Difference, Exclusion, Subtract, Divide, Hue, Saturation, Color, Luminosity,
};
inline constexpr int kBlendModeCount = 27;

// The graph's names ("multiply", "color_burn", ...); false for an unknown name.
bool blendModeFromName(const std::string& name, BlendMode* out);
const char* blendModeName(BlendMode mode);

// B(Cb, Cs) for one RGB triple in the blending space; every mode but Normal / Dissolve
// clamps its inputs to 0..1.
void blendColor(BlendMode mode, const float cb[3], const float cs[3], float out[3]);

// Source over backdrop (straight RGBA, working linear) with [mode]; [amount] (opacity x
// fill, 0..1) scales the source alpha; (docX, docY) picks Dissolve's threshold.
void compositePixel(BlendMode mode, float amount, bool gamma, const float backdrop[4], const float source[4], double docX,
                    double docY, float out[4]);

// Layer images (raster and smart object sources), process-wide by id, in the working space
// with straight alpha, at any resolution: the graph maps them through `sourceSize`. The
// preview set feeds the canvas and thumbnails, the full set exports.
void registerLayerImage(const std::string& id, std::shared_ptr<const ImageF32> image, bool full);
void unregisterLayerImage(const std::string& id);
// Drops every export image (after an export; the preview set stays for the canvas).
void releaseFullLayerImages();
std::shared_ptr<const ImageF32> findLayerImage(const std::string& id, bool full);

// True when the graph has a visible layer above the background that draws something.
bool hasLayers(const Graph& graph);

// Buffer pixel (i, j) is the document point ((originX + i + 0.5) / scale, (originY + j + 0.5) / scale).
// The document is docWidth x docHeight buffer pixels (0 = the region reaches its far edges);
// masks reflect their feather at the document's edges, not the region's (no seams).
struct Region {
    int width = 0;
    int height = 0;
    double originX = 0.0;
    double originY = 0.0;
    double scale = 1.0;
    int docWidth = 0;
    int docHeight = 0;
};

// What the canvas shows of one layer's mask (Stage 14): the mask in grey, or a red overlay
// where the mask hides (50 %).
enum class MaskView : int { None = 0, MaskOnly = 1, Overlay = 2 };

// Draws the graph's layers over [rgba] (the rendered background of [region], RGBA, replaced
// by the composite). Layers whose image is not registered are skipped and named in
// [skipped] ("layer:<source>"); [full] picks the export images.
void compositeLayers(const Graph& graph, float* rgba, const Region& region, bool full, std::string* skipped);

// [rgba] (a composite of [region]) shows layer [layerId]'s mask as [view]; nothing when the
// layer has no enabled mask.
void showMask(const Graph& graph, const std::string& layerId, MaskView view, float* rgba, const Region& region);

}  // namespace lo::layers
