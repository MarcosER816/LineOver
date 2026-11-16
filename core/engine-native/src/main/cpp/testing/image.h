// Plain interleaved images used by the test helpers.
#pragma once

#include <cstdint>
#include <vector>

namespace lo::testing {

template <typename T>
struct Image {
    int width = 0;
    int height = 0;
    int channels = 0;
    std::vector<T> data;  // row-major, interleaved, width * height * channels samples

    bool sameShape(const Image& other) const {
        return width == other.width && height == other.height && channels == other.channels;
    }
};

using ImageU8 = Image<std::uint8_t>;
using ImageF32 = Image<float>;

}  // namespace lo::testing
