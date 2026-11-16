#include <cstdio>
#include <cstring>
#include <vector>

#include "lineover/engine.h"
#include "lo_test.h"

LO_TEST(version_is_semantic) {
    const char* version = lo::version();
    LO_CHECK(version != nullptr);
    int major = -1, minor = -1, patch = -1;
    LO_CHECK(std::sscanf(version, "%d.%d.%d", &major, &minor, &patch) == 3);
    LO_CHECK(major >= 0 && minor >= 0 && patch >= 0);
}

LO_TEST(identity_copies_u8) {
    const std::vector<std::uint8_t> source = {0, 1, 127, 128, 254, 255};
    std::vector<std::uint8_t> result(source.size(), 42);
    lo::ops::identity(source.data(), result.data(), source.size());
    LO_CHECK(result == source);
}

LO_TEST(identity_copies_float_bit_exact) {
    const std::vector<float> source = {0.0f, -0.0f, 1.0f, 16.5f, -3.25f, 1e-30f};
    std::vector<float> result(source.size(), 7.0f);
    lo::ops::identity(source.data(), result.data(), source.size());
    LO_CHECK(std::memcmp(result.data(), source.data(), source.size() * sizeof(float)) == 0);
}

LO_TEST(identity_in_place_is_allowed) {
    std::vector<std::uint8_t> buffer = {9, 8, 7};
    lo::ops::identity(buffer.data(), buffer.data(), buffer.size());
    LO_CHECK((buffer == std::vector<std::uint8_t>{9, 8, 7}));
}

int main() { return lo::testing::runAllTests(); }
