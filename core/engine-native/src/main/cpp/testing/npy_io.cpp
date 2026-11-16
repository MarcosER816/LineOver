#include "npy_io.h"

#include <cstring>
#include <fstream>
#include <iterator>
#include <stdexcept>

namespace lo::testing {

ImageF32 readNpyF32(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("NPY: cannot open " + path);
    const std::vector<char> bytes((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());

    static const char kMagic[6] = {'\x93', 'N', 'U', 'M', 'P', 'Y'};
    if (bytes.size() < 12 || std::memcmp(bytes.data(), kMagic, sizeof(kMagic)) != 0) {
        throw std::runtime_error("NPY: bad magic in " + path);
    }
    const int major = bytes[6];
    const auto byteAt = [&](std::size_t i) { return static_cast<std::size_t>(static_cast<unsigned char>(bytes[i])); };
    std::size_t headerLength = 0;
    std::size_t headerStart = 0;
    if (major == 1) {
        headerLength = byteAt(8) | (byteAt(9) << 8);
        headerStart = 10;
    } else {
        headerLength = byteAt(8) | (byteAt(9) << 8) | (byteAt(10) << 16) | (byteAt(11) << 24);
        headerStart = 12;
    }
    if (headerStart + headerLength > bytes.size()) throw std::runtime_error("NPY: truncated header in " + path);
    const std::string header(bytes.data() + headerStart, headerLength);

    if (header.find("'descr': '<f4'") == std::string::npos) {
        throw std::runtime_error("NPY: expected little-endian float32 in " + path);
    }
    if (header.find("'fortran_order': False") == std::string::npos) {
        throw std::runtime_error("NPY: expected C order in " + path);
    }

    const std::size_t open = header.find('(', header.find("'shape'"));
    const std::size_t close = header.find(')', open);
    if (open == std::string::npos || close == std::string::npos) throw std::runtime_error("NPY: no shape in " + path);
    std::vector<int> shape;
    int value = -1;
    for (std::size_t i = open + 1; i <= close; ++i) {
        const char c = header[i];
        if (c >= '0' && c <= '9') {
            value = (value < 0 ? 0 : value * 10) + (c - '0');
        } else if (value >= 0) {
            shape.push_back(value);
            value = -1;
        }
    }
    if (shape.size() != 3) throw std::runtime_error("NPY: expected shape (H, W, C) in " + path);

    ImageF32 image;
    image.height = shape[0];
    image.width = shape[1];
    image.channels = shape[2];
    const std::size_t count = static_cast<std::size_t>(shape[0]) * static_cast<std::size_t>(shape[1]) *
                              static_cast<std::size_t>(shape[2]);
    const std::size_t dataStart = headerStart + headerLength;
    if (dataStart + count * sizeof(float) != bytes.size()) throw std::runtime_error("NPY: size mismatch in " + path);
    image.data.resize(count);
    std::memcpy(image.data.data(), bytes.data() + dataStart, count * sizeof(float));  // host is little-endian
    return image;
}

}  // namespace lo::testing
