// Small GLES 3 helpers for the engine's render thread.
#pragma once

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <string>

namespace lo::android {

// Compiles and links a program; returns 0 and fills `error` on failure.
GLuint linkProgram(const std::string& vertex, const std::string& fragment, std::string* error);

// An RGBA16F (or RGBA8 fallback) texture of width x height with linear filtering.
GLuint createTexture(int width, int height, bool halfFloat, const void* floatPixels);

// Uploads float RGBA pixels into an existing texture.
void uploadFloat(GLuint texture, int width, int height, bool halfFloat, const float* pixels);

bool hasExtension(const char* extensions, const char* name);

// True when RGBA16F is colour-renderable (GLES 3.2, EXT_color_buffer_half_float or
// EXT_color_buffer_float). Must be called with a current context.
bool halfFloatRenderable();

std::string glErrorString();

}  // namespace lo::android
