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

// Half floats converted here (round to nearest even, like numpy's float16) rather than
// by the driver, whose float -> half rounding is not specified: the histogram and the
// GPU tests then see exactly the values the Python reference rounds to.
std::vector<std::uint16_t> toHalf(const float* pixels, std::size_t count) {
    std::vector<std::uint16_t> halves(count);
    for (std::size_t i = 0; i < count; ++i) halves[i] = color::floatToHalf(pixels[i]);
    return halves;
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

GLuint linkCompute(const std::string& source, std::string* error) {
    GLuint shader = compile(GL_COMPUTE_SHADER, source, error);
    if (shader == 0) return 0;
    GLuint program = glCreateProgram();
    glAttachShader(program, shader);
    glLinkProgram(program);
    glDeleteShader(shader);
    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        char log[1024] = {0};
        glGetProgramInfoLog(program, sizeof(log) - 1, nullptr, log);
        *error = std::string("compute link failed: ") + log;
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
        std::vector<std::uint16_t> halves;
        if (floatPixels != nullptr) {
            halves = toHalf(static_cast<const float*>(floatPixels), static_cast<std::size_t>(width) * height * 4);
        }
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT,
                     floatPixels != nullptr ? halves.data() : nullptr);
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
        const std::vector<std::uint16_t> halves = toHalf(pixels, static_cast<std::size_t>(width) * height * 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_HALF_FLOAT, halves.data());
    } else {
        const std::vector<GLubyte> bytes = encodeFallback(pixels, static_cast<std::size_t>(width) * height * 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes.data());
    }
}

GLuint createGuidedTexture(const GuidedCoefficients* coefficients) {
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    // Read with texelFetch only (RG32F is not filterable on GLES).
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (coefficients != nullptr && !coefficients->ab.empty()) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG32F, coefficients->width, coefficients->height, 0, GL_RG, GL_FLOAT,
                     coefficients->ab.data());
    } else {
        const float identity[2] = {1.f, 0.f};  // base = L: what cpuRef does without coefficients
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG32F, 1, 1, 0, GL_RG, GL_FLOAT, identity);
    }
    return texture;
}

namespace {
GLuint nearestFloatTexture(GLenum internal, GLenum format, int width, int height, const float* data) {
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL_FLOAT, data);
    return texture;
}
}  // namespace

void createFieldTextures(const DetailFields* fields, GLuint* detail, GLuint* haze) {
    const bool has = fields != nullptr && !fields->clarity.ab.empty() && fields->clarity.width == fields->texture.width &&
                     fields->clarity.height == fields->texture.height && !fields->haze.ab.empty();
    if (!has) {
        const float identity[4] = {1.f, 0.f, 1.f, 0.f};
        const float none[2] = {0.f, 0.f};
        *detail = nearestFloatTexture(GL_RGBA32F, GL_RGBA, 1, 1, identity);
        *haze = nearestFloatTexture(GL_RG32F, GL_RG, 1, 1, none);
        return;
    }
    const std::size_t n = static_cast<std::size_t>(fields->clarity.width) * fields->clarity.height;
    std::vector<float> packed(4 * n);
    for (std::size_t i = 0; i < n; ++i) {
        packed[4 * i] = fields->clarity.ab[2 * i];
        packed[4 * i + 1] = fields->clarity.ab[2 * i + 1];
        packed[4 * i + 2] = fields->texture.ab[2 * i];
        packed[4 * i + 3] = fields->texture.ab[2 * i + 1];
    }
    *detail = nearestFloatTexture(GL_RGBA32F, GL_RGBA, fields->clarity.width, fields->clarity.height, packed.data());
    *haze = nearestFloatTexture(GL_RG32F, GL_RG, fields->haze.width, fields->haze.height, fields->haze.ab.data());
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
