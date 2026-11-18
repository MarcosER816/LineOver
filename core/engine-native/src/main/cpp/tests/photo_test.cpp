// The owner's real photos (testdata/photos, 32 x 32 block means: 96 x 128): the phone
// checklist edits, built from the same graph JSON the app sends, against the Python
// reference in testdata/golden/photos.
#include <string>

#include "golden_assert.h"
#include "lineover/graph.h"
#include "lineover/light.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

namespace {

// Kept in step with RealPhotoTest (Kotlin) and reference.PHOTO_EDITS.
constexpr const char* kSCurve =
    R"({"op":"curves","free":0.0,"rgb":[0,0,0.25,0.18,0.75,0.82,1,1],"red":[],"green":[],"blue":[],"luma":[]})";
constexpr const char* kTealOrange =
    R"({"op":"color_grading","blending":50.0,"balance":0.0,"shadows":[200,60,0],"midtones":[0,0,0],)"
    R"("highlights":[55,50,0],"global":[0,0,0]})";
constexpr const char* kOrangeSaturation =
    R"({"op":"hsl_mixer","hue":[0,0,0,0,0,0,0,0],"saturation":[0,60,0,0,0,0,0,0],"luminance":[0,0,0,0,0,0,0,0]})";
constexpr const char* kHighlightsShadows = R"({"op":"highlights_shadows","highlights":-80.0,"shadows":60.0})";

void checkEdit(const std::string& edit, const std::string& photo, const char* opJson) {
    const ImageF32 input = readNpyF32(goldenPath("photos/" + photo + "_rec2020_f32.npy"));
    lo::Graph graph;
    std::string error;
    const std::string json = std::string(R"({"schemaVersion":1,"layers":[{"id":"background","ops":[)") + opJson + "]}]}";
    LO_CHECK(lo::parseGraph(json, &graph, &error));
    std::string skipped;
    const auto ops = lo::buildOps(graph, &skipped);
    LO_CHECK(skipped.empty());
    LO_CHECK(ops->ops().size() == 1u);
    // Every op runs region-aware, as tiles do: Highlights / Shadows reads the photo's
    // guided-filter coefficients, the pointwise ops ignore them.
    const lo::GuidedCoefficients c = lo::guidedCoefficients(input.data.data(), input.width, input.height);
    lo::OpRegion region;
    region.width = input.width;
    region.height = input.height;
    region.coefficients = &c;
    ImageF32 actual = input;
    ops->cpuRefRegion(input.data.data(), actual.data.data(), region);
    LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath("photos/" + edit + "_f32.npy")), "photo_" + edit,
                                  kToleranceFloat));
}

}  // namespace

LO_TEST(drop_s_curve_matches_python) { checkEdit("drop_s_curve", "drop", kSCurve); }

LO_TEST(bird_teal_orange_grade_matches_python) { checkEdit("bird_teal_orange", "bird", kTealOrange); }

LO_TEST(bird_highlights_shadows_matches_python) { checkEdit("bird_highlights_shadows", "bird", kHighlightsShadows); }

LO_TEST(marigold_orange_saturation_matches_python) {
    checkEdit("marigold_orange_saturation", "marigold", kOrangeSaturation);
}

// The decode side: 8-bit Display P3 -> working space (p38ToWorking) is linear in each
// block, so the block means of the photo stay the identity's golden (checked on the device
// against the real decoder in RealPhotoTest).
LO_TEST(photo_inputs_are_opaque_working_space) {
    for (const char* name : {"drop", "bird", "marigold"}) {
        const ImageF32 image = readNpyF32(goldenPath(std::string("photos/") + name + "_rec2020_f32.npy"));
        LO_CHECK(image.width == 96 && image.height == 128);
        for (std::size_t i = 3; i < image.data.size(); i += 4) LO_CHECK(image.data[i] == 1.0f);
    }
}

int main() { return runAllTests(); }
