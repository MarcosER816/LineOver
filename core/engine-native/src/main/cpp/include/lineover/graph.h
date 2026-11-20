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

std::string serializeGraph(const Graph& graph);

// The background layer's ops, ready to run (other layers are composited). Ops this engine does not
// know are skipped and reported in `skipped` (comma-separated names) when not null.
std::unique_ptr<OpList> buildOps(const Graph& graph, std::string* skipped);

// Kotlin's Float.toString for a float value ("1.5", "2.0", "-0.25", "1.0E-4").
std::string formatKotlinFloat(float value);

}  // namespace lo
