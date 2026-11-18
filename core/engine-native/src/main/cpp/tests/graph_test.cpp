// Edit-graph JSON: the C++ side reads and writes exactly what Kotlin's EditGraphJson
// writes (shared fixture testdata/graph/edit_graph_v1.json, also round-tripped by
// EditGraphJsonTest in :core:model).
#include <fstream>
#include <sstream>
#include <string>

#include "lineover/graph.h"
#include "lo_test.h"

namespace {

std::string fixture() {
    std::ifstream file(std::string(LO_TESTDATA_DIR) + "/graph/edit_graph_v1.json");
    std::stringstream text;
    text << file.rdbuf();
    std::string s = text.str();
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    return s;
}

}  // namespace

LO_TEST(fixture_round_trips_byte_for_byte) {
    const std::string text = fixture();
    LO_CHECK(!text.empty());
    lo::Graph graph;
    std::string error;
    LO_CHECK(lo::parseGraph(text, &graph, &error));
    LO_CHECK(error.empty());
    LO_CHECK(graph.schemaVersion == 1);
    LO_CHECK(graph.layers.size() == 2);
    LO_CHECK(graph.layers[0].id == "background" && graph.layers[0].ops.size() == 2);
    LO_CHECK(graph.layers[0].ops[1].op == "exposure_test" && graph.layers[0].ops[1].params[0].second == 1.5);
    const std::string again = lo::serializeGraph(graph);
    if (again != text) std::cerr << "  expected " << text << "\n  actual   " << again << "\n";
    LO_CHECK(again == text);
}

LO_TEST(unknown_keys_and_ops_are_ignored) {
    lo::Graph graph;
    std::string error;
    const std::string text =
        R"({"schemaVersion":1,"future":{"x":[1,2]},"layers":[{"id":"l0","blend":"multiply","ops":[{"op":"identity"},{"op":"curves_v9","points":[0,1]},{"op":"exposure_test","stops":-2.0,"extra":true}]}]})";
    LO_CHECK(lo::parseGraph(text, &graph, &error));
    std::string skipped;
    const auto ops = lo::buildOps(graph, &skipped);
    LO_CHECK(skipped == "curves_v9");
    LO_CHECK(ops->ops().size() == 2);
    const float in[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    float out[4];
    ops->cpuRef(in, out, 1);
    LO_CHECK(out[0] == 0.25f && out[3] == 1.0f);
}

LO_TEST(malformed_json_is_rejected_with_a_message) {
    lo::Graph graph;
    std::string error;
    LO_CHECK(!lo::parseGraph("{\"layers\":[", &graph, &error));
    LO_CHECK(!error.empty());
    LO_CHECK(!lo::parseGraph("[1,2]", &graph, &error));
    LO_CHECK(!lo::parseGraph(R"({"layers":[{"id":"x","ops":[{"stops":1}]}]})", &graph, &error));
}

LO_TEST(floats_are_written_like_kotlin) {
    LO_CHECK(lo::formatKotlinFloat(1.5f) == "1.5");
    LO_CHECK(lo::formatKotlinFloat(2.0f) == "2.0");
    LO_CHECK(lo::formatKotlinFloat(-0.25f) == "-0.25");
    LO_CHECK(lo::formatKotlinFloat(0.0f) == "0.0");
    LO_CHECK(lo::formatKotlinFloat(0.1f) == "0.1");
    LO_CHECK(lo::formatKotlinFloat(100.0f) == "100.0");
    LO_CHECK(lo::formatKotlinFloat(1234.5f) == "1234.5");
    LO_CHECK(lo::formatKotlinFloat(0.001f) == "0.001");
    LO_CHECK(lo::formatKotlinFloat(1.0e-4f) == "1.0E-4");
    LO_CHECK(lo::formatKotlinFloat(1.25e7f) == "1.25E7");
}

LO_TEST(an_empty_graph_serializes_to_the_kotlin_default) {
    LO_CHECK(lo::serializeGraph(lo::Graph{}) == R"({"schemaVersion":1,"layers":[]})");
}

int main() { return lo::testing::runAllTests(); }
