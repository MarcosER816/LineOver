// Op interface: cpuRef against the Python reference, and what gpu() hands the backend.
#include <string>
#include <vector>

#include "golden_assert.h"
#include "lineover/ops.h"
#include "lo_test.h"
#include "npy_io.h"

using namespace lo::testing;

namespace {

class Recorder final : public lo::GpuOpContext {
public:
    std::string declarations, body;
    std::vector<std::pair<std::string, float>> uniforms;
    void setShader(const char* d, const char* b) override {
        declarations = d;
        body = b;
    }
    void setUniform1f(const char* name, float value) override { uniforms.emplace_back(name, value); }
};

}  // namespace

LO_TEST(exposure_test_cpu_ref_matches_python_reference) {
    const ImageF32 input = readNpyF32(goldenPath("input/pattern_rec2020_f32.npy"));
    ImageF32 actual = input;
    lo::makeExposureTest(1.5f)->cpuRef(input.data.data(), actual.data.data(), input.data.size() / 4);
    LO_CHECK_GOLDEN(compareGolden(actual, readNpyF32(goldenPath("exposure_test/expected_rec2020_f32.npy")),
                                  "exposure_test_rec2020_f32", kToleranceFloat));
}

LO_TEST(identity_cpu_ref_matches_python_reference_in_place) {
    ImageF32 image = readNpyF32(goldenPath("input/pattern_rec2020_f32.npy"));
    lo::makeIdentity()->cpuRef(image.data.data(), image.data.data(), image.data.size() / 4);
    LO_CHECK_GOLDEN(compareGolden(image, readNpyF32(goldenPath("identity/expected_rec2020_f32.npy")),
                                  "identity_in_place_f32", kToleranceFloat));
}

LO_TEST(op_list_applies_ops_in_order_and_knows_identity) {
    lo::OpList list;
    list.add(lo::makeIdentity());
    LO_CHECK(list.isIdentity());
    list.add(lo::makeExposureTest(1.0f));
    list.add(lo::makeExposureTest(-3.0f));
    LO_CHECK(!list.isIdentity());
    const float in[4] = {0.5f, 0.25f, 1.0f, 0.75f};
    float out[4];
    list.cpuRef(in, out, 1);
    LO_CHECK(out[0] == 0.125f && out[1] == 0.0625f && out[2] == 0.25f && out[3] == 0.75f);
    LO_CHECK(lo::makeExposureTest(0.0f)->isIdentity());
}

LO_TEST(gpu_describes_shader_and_uniforms) {
    Recorder recorder;
    lo::makeExposureTest(2.0f)->gpu(recorder);
    LO_CHECK(recorder.declarations == "uniform float u_gain;");
    LO_CHECK(recorder.body.find("c.rgb * u_gain") != std::string::npos);
    LO_CHECK(recorder.uniforms.size() == 1 && recorder.uniforms[0].first == "u_gain" && recorder.uniforms[0].second == 4.0f);
    Recorder identity;
    lo::makeIdentity()->gpu(identity);
    LO_CHECK(identity.body == "return c;" && identity.uniforms.empty());
}

int main() { return runAllTests(); }
