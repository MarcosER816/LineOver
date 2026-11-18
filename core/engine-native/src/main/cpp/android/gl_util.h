// Small GLES 3 helpers for the engine's render thread.
#pragma once

#include <EGL/egl.h>
#include <GLES3/gl31.h>

#include <string>

#include "lineover/ops.h"

namespace lo::android {

// Compiles and links a program; returns 0 and fills `error` on failure.
GLuint linkProgram(const std::string& vertex, const std::string& fragment, std::string* error);

// Compiles and links a GLES 3.1 compute program; 0 and `error` on failure.
GLuint linkCompute(const std::string& source, std::string* error);

// An RGBA16F (or SRGB8_ALPHA8 fallback) texture; float pixels are rounded to half here.
// of width x height with linear filtering.
GLuint createTexture(int width, int height, bool halfFloat, const void* floatPixels);

// Uploads float RGBA pixels into an existing texture.
void uploadFloat(GLuint texture, int width, int height, bool halfFloat, const float* pixels);

// RG32F texture of guided-filter coefficients (A, B), or a 1 x 1 (1, 0) when null.
GLuint createGuidedTexture(const GuidedCoefficients* coefficients);

bool hasExtension(const char* extensions, const char* name);

// True when RGBA16F is colour-renderable (GLES 3.2, EXT_color_buffer_half_float or
// EXT_color_buffer_float). Must be called with a current context.
bool halfFloatRenderable();

std::string glErrorString();

}  // namespace lo::android
