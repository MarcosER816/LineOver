#include <cstring>

#include "lineover/engine.h"

namespace lo::ops {

void identity(const std::uint8_t* src, std::uint8_t* dst, std::size_t count) {
    if (src != dst && count > 0) {
        std::memcpy(dst, src, count);
    }
}

void identity(const float* src, float* dst, std::size_t count) {
    if (src != dst && count > 0) {
        std::memcpy(dst, src, count * sizeof(float));
    }
}

}  // namespace lo::ops
