// Minimal PNG codec for tests: 8-bit RGB / RGBA, non-interlaced. Uses zlib only.
#pragma once

#include <string>

#include "image.h"

namespace lo::testing {

// Throws std::runtime_error on I/O errors or unsupported PNG variants.
ImageU8 readPng(const std::string& path);
void writePng(const std::string& path, const ImageU8& image);

}  // namespace lo::testing
