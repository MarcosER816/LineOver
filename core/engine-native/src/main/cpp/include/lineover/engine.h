// Public C++ API of the LineOver engine core.
#pragma once

#include <cstddef>
#include <cstdint>

namespace lo {

// Semantic version of the engine, e.g. "0.0.1".
const char* version();

namespace ops {

// Identity: copies `count` samples from src to dst unchanged. The buffers may be
// the same pointer but must not partially overlap.
void identity(const std::uint8_t* src, std::uint8_t* dst, std::size_t count);
void identity(const float* src, float* dst, std::size_t count);

}  // namespace ops
}  // namespace lo
