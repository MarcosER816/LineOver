#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace lo::android {

// Creates an off-screen GLES 3 context, clears it to the given colour and reads
// one pixel back. Returns false and fills `error` if any EGL/GL step fails.
bool glClearAndReadPixel(float r, float g, float b, float a,
                         std::array<std::uint8_t, 4>* rgba, std::string* error);

}  // namespace lo::android
