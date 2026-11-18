#include "gl_util.h"

#include <cstring>
#include <vector>

#include "lineover/color.h"

namespace lo::android {
namespace {

// Fallback storage: working values clipped to 0..1, sRGB-encoded into SRGB8_ALPHA8 (GL
// decodes on sampling and encodes on rendering, so shaders still see linear values).
std::vector<GLubyte> encodeFallback(const float* pixels, std::size_t count) {
    std::vector<GLubyte> bytes(count);
    for (std::size_t i = 0; i < count; ++i) {
        float v = pixels[i];
        v = v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
        if (i % 4 != 3) v = color::srgbOetf(v);
        bytes[i] = static_cast<GLubyte>(v * 255.0f + 0.5f);
    }
    return bytes;
}

GLuint compile(GLenum type, const std::string& source, std::string* error) {
    GLuint shader = glCreateShader(type);
    const char* text = source.c_str();
    glShaderSource(shader, 1, &text, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        char log[1024] = {0};
        glGetShaderInfoLog(shader, sizeof(log) - 1, nullptr, log);
        *error = std::string("shader compile failed: ") + log;
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}  // namespace

GLuint linkProgram(const std::string& vertex, const std::string& fragment, std::string* error) {
    GLuint vs = compile(GL_VERTEX_SHADER, vertex, error);
    if (vs == 0) return 0;
    GLuint fs = compile(GL_FRAGMENT_SHADER, fragment, error);
    if (fs == 0) {
        glDeleteShader(vs);
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "a_position");
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        char log[1024] = {0};
        glGetProgramInfoLog(program, sizeof(log) - 1, nullptr, log);
        *error = std::string("program link failed: ") + log;
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

GLuint createTexture(int width, int height, bool halfFloat, const void* floatPixels) {
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (halfFloat) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, floatPixels);
    } else {
        // Fallback when RGBA16F is not colour-renderable (some emulators): SRGB8_ALPHA8.
        // Never used on the phone; documented in STAGE_04.
        std::vector<GLubyte> bytes;
        if (floatPixels != nullptr) {
            bytes = encodeFallback(static_cast<const float*>(floatPixels), static_cast<std::size_t>(width) * height * 4);
        }
        glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     floatPixels != nullptr ? bytes.data() : nullptr);
    }
    return texture;
}

void uploadFloat(GLuint texture, int width, int height, bool halfFloat, const float* pixels) {
    glBindTexture(GL_TEXTURE_2D, texture);
    if (halfFloat) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_FLOAT, pixels);
    } else {
        const std::vector<GLubyte> bytes = encodeFallback(pixels, static_cast<std::size_t>(width) * height * 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes.data());
    }
}

bool hasExtension(const char* extensions, const char* name) {
    if (extensions == nullptr) return false;
    const std::size_t n = std::strlen(name);
    for (const char* p = extensions; (p = std::strstr(p, name)) != nullptr; p += n) {
        const bool startOk = p == extensions || p[-1] == ' ';
        const bool endOk = p[n] == ' ' || p[n] == '\0';
        if (startOk && endOk) return true;
    }
    return false;
}

bool halfFloatRenderable() {
    GLint major = 0, minor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    glGetIntegerv(GL_MINOR_VERSION, &minor);
    if (major > 3 || (major == 3 && minor >= 2)) return true;
    const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    return hasExtension(extensions, "GL_EXT_color_buffer_half_float") || hasExtension(extensions, "GL_EXT_color_buffer_float");
}

std::string glErrorString() {
    std::string out;
    for (GLenum e = glGetError(); e != GL_NO_ERROR; e = glGetError()) {
        if (!out.empty()) out += ",";
        out += std::to_string(static_cast<unsigned>(e));
    }
    return out;
}

}  // namespace lo::android
