// Reader for NumPy .npy files holding little-endian float32, C-order, shape (H, W, C).
#pragma once

#include <string>

#include "image.h"

namespace lo::testing {

// Throws std::runtime_error on I/O errors or unsupported dtype / layout.
ImageF32 readNpyF32(const std::string& path);

}  // namespace lo::testing
