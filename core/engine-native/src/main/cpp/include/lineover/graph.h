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
};

struct Layer {
    std::string id;
    std::vector<OpParams> ops;
};

struct Graph {
    static constexpr int kSchemaVersion = 1;
    int schemaVersion = kSchemaVersion;
    std::vector<Layer> layers;
};

// Returns false (and sets `error`) on malformed JSON or a wrong shape.
bool parseGraph(const std::string& json, Graph* out, std::string* error);

std::string serializeGraph(const Graph& graph);

// The ops of the graph's layers, bottom to top, ready to run. Ops this engine does not
// know are skipped and reported in `skipped` (comma-separated names) when not null.
std::unique_ptr<OpList> buildOps(const Graph& graph, std::string* skipped);

// Kotlin's Float.toString for a float value ("1.5", "2.0", "-0.25", "1.0E-4").
std::string formatKotlinFloat(float value);

}  // namespace lo
