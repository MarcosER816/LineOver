// Tests of the test helper itself: tolerances, heatmap output, PNG round trip.
#include <cmath>
#include <cstdlib>
#include <filesystem>

#include "golden_assert.h"
#include "lo_test.h"
#include "png_io.h"

using namespace lo::testing;

namespace {

ImageU8 gradient(int width, int height) {
    ImageU8 image{width, height, 4, {}};
    image.data.resize(static_cast<std::size_t>(width * height * 4));
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            std::uint8_t* p = &image.data[static_cast<std::size_t>((y * width + x) * 4)];
            p[0] = static_cast<std::uint8_t>(x * 255 / (width - 1));
            p[1] = static_cast<std::uint8_t>(y * 255 / (height - 1));
            p[2] = static_cast<std::uint8_t>((x * 7 + y * 13) % 256);
            p[3] = static_cast<std::uint8_t>(255 - x);
        }
    }
    return image;
}

}  // namespace

LO_TEST(identical_images_pass) {
    const ImageU8 image = gradient(16, 8);
    const GoldenResult result = compareGolden(image, image, "self_identical");
    LO_CHECK(result.passed);
    LO_CHECK(result.maxAbs == 0.0 && result.meanAbs == 0.0);
    LO_CHECK(result.heatmapPath.empty());
}

LO_TEST(u8_max_tolerance_is_two_levels) {
    const ImageU8 expected = gradient(16, 8);
    ImageU8 actual = expected;
    actual.data[5] = static_cast<std::uint8_t>(actual.data[5] ^ 2);  // one sample off by exactly 2
    LO_CHECK(compareGolden(actual, expected, "self_off_by_two").passed);

    actual = expected;
    actual.data[5] = static_cast<std::uint8_t>(actual.data[5] > 100 ? actual.data[5] - 3 : actual.data[5] + 3);
    const GoldenResult result = compareGolden(actual, expected, "self_off_by_three");
    LO_CHECK(!result.passed);
    LO_CHECK(std::abs(result.maxAbs - 3.0 / 255.0) < 1e-12);
}

LO_TEST(u8_mean_tolerance_is_half_a_level) {
    const ImageU8 expected = gradient(16, 8);
    ImageU8 actual = expected;
    for (std::uint8_t& sample : actual.data) {
        sample = static_cast<std::uint8_t>(sample < 255 ? sample + 1 : sample - 1);  // every sample off by 1
    }
    const GoldenResult result = compareGolden(actual, expected, "self_mean_off_by_one");
    LO_CHECK(!result.passed);  // max 1/255 is fine, mean 1/255 > 0.5/255 is not
    LO_CHECK(result.maxAbs <= kToleranceU8.maxAbs);
}

LO_TEST(gpu_tolerance_is_three_levels) {
    const ImageU8 expected = gradient(16, 8);
    ImageU8 actual = expected;
    for (std::uint8_t& sample : actual.data) {
        sample = static_cast<std::uint8_t>(sample < 250 ? sample + 3 : sample - 3);
    }
    LO_CHECK(compareGolden(actual, expected, "self_gpu_three", kToleranceGpuVsCpu).passed);
    actual.data[0] = static_cast<std::uint8_t>(expected.data[0] + 4);
    LO_CHECK(!compareGolden(actual, expected, "self_gpu_four", kToleranceGpuVsCpu).passed);
}

LO_TEST(float_tolerance_and_nan) {
    ImageF32 expected{2, 1, 3, {0.0f, 0.5f, 1.0f, 2.0f, 4.0f, 8.0f}};
    ImageF32 actual = expected;
    actual.data[4] += 5e-4f;
    LO_CHECK(compareGolden(actual, expected, "self_float_close").passed);
    actual.data[4] = expected.data[4] + 2e-3f;
    LO_CHECK(!compareGolden(actual, expected, "self_float_far").passed);
    actual = expected;
    actual.data[1] = std::nanf("");
    LO_CHECK(!compareGolden(actual, expected, "self_float_nan").passed);
}

LO_TEST(shape_mismatch_fails) {
    LO_CHECK(!compareGolden(gradient(16, 8), gradient(8, 16), "self_shape").passed);
}

LO_TEST(failure_writes_heatmap_png) {
    const ImageU8 expected = gradient(16, 8);
    ImageU8 actual = expected;
    actual.data[0] = static_cast<std::uint8_t>(expected.data[0] + 40);
    const GoldenResult result = compareGolden(actual, expected, "self_heatmap");
    LO_CHECK(!result.passed);
    LO_CHECK(!result.heatmapPath.empty() && std::filesystem::exists(result.heatmapPath));

    const ImageU8 heatmap = readPng(result.heatmapPath);
    LO_CHECK(heatmap.width == 16 && heatmap.height == 8 && heatmap.channels == 3);
    LO_CHECK(heatmap.data[0] == 255);                                        // failing pixel is hot
    LO_CHECK(heatmap.data[3] == 0 && heatmap.data[4] == 0 && heatmap.data[5] == 0);  // identical pixel is black
}

LO_TEST(png_round_trip_is_lossless) {
    const ImageU8 image = gradient(33, 17);
    const std::filesystem::path path = std::filesystem::temp_directory_path() / "lo_png_round_trip.png";
    writePng(path.string(), image);
    const ImageU8 decoded = readPng(path.string());
    LO_CHECK(decoded.sameShape(image));
    LO_CHECK(decoded.data == image.data);
    std::filesystem::remove(path);
}

int main() {
    // These tests fail comparisons on purpose; keep their heatmaps out of the
    // directory that CI uploads as "real" golden diffs.
    const std::filesystem::path scratch = std::filesystem::temp_directory_path() / "lo_golden_assert_self_test";
    setenv("LO_GOLDEN_DIFF_DIR", scratch.string().c_str(), 1);
    const int status = lo::testing::runAllTests();
    std::filesystem::remove_all(scratch);
    return status;
}
