// The edit graph as the engine sees it: the JSON written by Kotlin's EditGraphJson
// (:core:model), schemaVersion 1. Parsing ignores unknown keys and unknown ops (a newer
// app's project still opens); serializing writes the canonical form Kotlin writes, so
// both sides round-trip the same text (graph_test and EditGraphJsonTest share a fixture).
#pragma once

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "lineover/ops.h"

namespace lo {

struct OpParams {
    std::string op;                                       // stable @SerialName, e.g. "exposure_test"
    std::vector<std::pair<std::string, double>> params;  // in declaration order
    // Number arrays (e.g. the HSL mixer's 8 bands), written after the scalars: Kotlin
    // ops declare their list fields last.
    std::vector<std::pair<std::string, std::vector<double>>> arrays;
    // String fields (Stage 11: a LUT's id, a profile's name), written first: Kotlin ops
    // declare their string fields first.
    std::vector<std::pair<std::string, std::string>> strings;
};

// A layer (Stage 13). The first layer of type "background" (or without a type: graphs from
// before Stage 13) is the photo; its ops are the develop edit. The other fields are written
// only when they differ from these defaults (Kotlin omits default values the same way).
// Layer masks (Stage 14): paint in document pixels, rendered by masks.h.
struct MaskPaint {
    std::string kind;  // "stroke" or "gradient"
    // stroke: a round brush along a polyline (x0, y0, x1, y1, ...)
    double value = 1.0;      // 1 paints white (reveal), 0 black (hide)
    double radius = 10.0;    // document px
    double hardness = 1.0;   // 0..1: where the falloff starts
    double opacity = 100.0;  // percent
    std::vector<double> points;
    // gradient: replaces the mask with a linear ramp from (x0, y0) to (x1, y1)
    double x0 = 0.0, y0 = 0.0, x1 = 0.0, y1 = 0.0;
    double from = 1.0, to = 0.0;
    // raster (Stage 15, a saved selection): replaces the mask with a registered R16F raster of
    // width x height pixels covering the docWidth x docHeight document, sampled bilinearly
    std::string source;
    double width = 0.0, height = 0.0, docWidth = 0.0, docHeight = 0.0;
};

// A selection operation (Stage 15; selection.h evaluates them). Coordinates in document px.
struct SelectionOp {
    std::string kind;          // rect, ellipse, lasso, polygon, wand, color_range, all, invert, grow, feather, smooth, border
    std::string mode = "new";  // new, add, subtract, intersect
    bool antiAlias = true;
    double x0 = 0.0, y0 = 0.0, x1 = 0.0, y1 = 0.0;  // rect / ellipse box
    std::vector<double> points;                    // lasso / polygon: x0, y0, x1, y1, ...
    double x = 0.0, y = 0.0, tolerance = 0.0;      // wand: seed, delta E
    bool contiguous = true;
    bool allLayers = true;
    double fuzziness = 0.0;                        // color range: delta E
    std::vector<double> add, subtract;             // color range samples: x, y, ...
    double px = 0.0;                               // grow (< 0 shrinks), feather, smooth, border
};

// {"ops":[...]} (Kotlin's Selection) into [out].
bool parseSelection(const std::string& json, std::vector<SelectionOp>* out, std::string* error);

struct LayerMask {
    double base = 1.0;  // 1 = white (reveal all), 0 = black (hide all)
    bool enabled = true;
    bool inverted = false;
    double density = 100.0;  // percent
    double feather = 0.0;    // Gaussian sigma, document px
    std::vector<MaskPaint> paint;
};

struct Layer {
    std::string id;
    std::vector<OpParams> ops;
    std::string type;  // "" / "background", "raster", "adjustment", "group", "smart", "text", "shape"
    std::string name;
    bool visible = true;
    bool locked = false;
    double opacity = 100.0;  // percent
    double fill = 100.0;     // percent
    std::string blend = "normal";
    bool isolated = false;     // groups: isolated, else pass-through
    std::string source;        // raster / smart: the registered layer image (compositor.h)
    std::vector<double> sourceSize;  // its full pixel size (w, h)
    std::vector<double> transform;   // a, b, c, d, e, f: source px -> document px; empty = identity
    std::vector<Layer> children;     // groups
    bool clip = false;               // clipped to the nearest unclipped layer below (Stage 14)
    bool hasMask = false;
    LayerMask mask;
};

struct Graph {
    static constexpr int kSchemaVersion = 1;
    int schemaVersion = kSchemaVersion;
    std::vector<Layer> layers;
    std::string blendSpace = "gamma";  // "gamma" (default) or "linear"
};

// The photo's layer, or null.
const Layer* backgroundLayer(const Graph& graph);

// True when an op reads only its own pixel: what adjustment layers and smart objects may run.
bool isPointOp(const std::string& op);

// [ops] as an OpList of point ops; others are skipped and reported.
std::unique_ptr<OpList> buildPointOps(const std::vector<OpParams>& ops, std::string* skipped);

// Returns false (and sets `error`) on malformed JSON or a wrong shape.
bool parseGraph(const std::string& json, Graph* out, std::string* error);

// A layer mask as the graph writes it (a cache key for rendered masks).
std::string serializeMaskJson(const LayerMask& mask);
std::string serializeGraph(const Graph& graph);

// The background layer's ops, ready to run (other layers are composited). Ops this engine does not
// know are skipped and reported in `skipped` (comma-separated names) when not null.
std::unique_ptr<OpList> buildOps(const Graph& graph, std::string* skipped);

// Kotlin's Float.toString for a float value ("1.5", "2.0", "-0.25", "1.0E-4").
std::string formatKotlinFloat(float value);

}  // namespace lo
