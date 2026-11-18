#include "gpu_pipeline.h"

#include <cstring>
#include <vector>

#include "gl_util.h"

namespace lo::android {
namespace {

// Unit quad as a triangle strip: (0,0) is the top-left of the image / tile.
constexpr float kQuad[8] = {0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 1.f};

constexpr const char* kOpVertex = R"(#version 300 es
layout(location = 0) in vec2 a_position;
out vec2 v_uv;
void main() {
    v_uv = a_position;
    gl_Position = vec4(a_position * 2.0 - 1.0, 0.0, 1.0);
}
)";

constexpr const char* kDisplayVertex = R"(#version 300 es
layout(location = 0) in vec2 a_position;
uniform mat3 u_matrix;
out vec2 v_uv;
void main() {
    v_uv = a_position;
    vec3 p = u_matrix * vec3(a_position, 1.0);
    gl_Position = vec4(p.xy, 0.0, 1.0);
}
)";

// Clipping overlay (DESIGN_SYSTEM clipHi #FF2D55 / clipLo #2D7BFF, given as linear
// values of the display's primaries): a channel that would show as 255 is a clipped highlight (linear >= 0.99621,
// the value encoding to 254.5 / 255); all channels that would show as 0 are crushed
// shadows (linear < 0.5 / 255 / 12.92).
constexpr const char* kDisplayFragment = R"(#version 300 es
precision highp float;
uniform sampler2D u_texture;
uniform mat3 u_toDisplay;
uniform int u_encode;
uniform int u_clip;
uniform vec3 u_clipHi;
uniform vec3 u_clipLo;
in vec2 v_uv;
out vec4 o_color;
float oetf(float x) {
    return x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}
void main() {
    vec4 c = texture(u_texture, v_uv);
    vec3 raw = u_toDisplay * c.rgb;
    vec3 d = clamp(raw, 0.0, 1.0);
    if (u_clip == 1) {
        float m = max(raw.r, max(raw.g, raw.b));
        if (m >= 0.99621) d = u_clipHi;
        else if (m < 0.00015177) d = u_clipLo;
    }
    if (u_encode == 1) d = vec3(oetf(d.r), oetf(d.g), oetf(d.b));
    float a = clamp(c.a, 0.0, 1.0);
    o_color = vec4(d * a, a);
}
)";

// 256-bin histograms of the sRGB display values and of luminance (reference:
// histogram_srgb in tools/golden/reference.py). Workgroup-local counts, then one
// atomicAdd per non-empty bin into the buffer.
constexpr const char* kHistogramCompute = R"(#version 310 es
precision highp float;
layout(local_size_x = 16, local_size_y = 16) in;
layout(binding = 0) uniform highp sampler2D u_image;
layout(std430, binding = 0) buffer Bins { uint bins[1024]; };
uniform mat3 u_toSrgb;
uniform ivec2 u_size;
shared uint localBins[1024];
float oetf(float x) {
    return x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}
uint binOf(float v) { return uint(min(floor(v * 256.0), 255.0)); }
void main() {
    uint li = gl_LocalInvocationIndex;
    for (uint i = li; i < 1024u; i += 256u) localBins[i] = 0u;
    barrier();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x < u_size.x && p.y < u_size.y) {
        vec3 c = texelFetch(u_image, p, 0).rgb;
        vec3 d = clamp(u_toSrgb * c, 0.0, 1.0);
        float y = clamp(dot(c, vec3(0.2627, 0.6780, 0.0593)), 0.0, 1.0);
        atomicAdd(localBins[binOf(oetf(d.r))], 1u);
        atomicAdd(localBins[256u + binOf(oetf(d.g))], 1u);
        atomicAdd(localBins[512u + binOf(oetf(d.b))], 1u);
        atomicAdd(localBins[768u + binOf(oetf(y))], 1u);
    }
    barrier();
    for (uint i = li; i < 1024u; i += 256u) {
        if (localBins[i] != 0u) atomicAdd(bins[i], localBins[i]);
    }
}
)";

// Image-wide inputs every op program may use (unused uniforms are optimised away).
// lo_guided() is sampleGuided() of ops.cpp: same pixel-centre convention, same clamping.
constexpr const char* kOpPrelude = R"(
uniform highp sampler2D u_guided;
uniform vec4 u_rect;
uniform ivec2 u_guidedSize;
vec2 lo_texel(ivec2 p) { return texelFetch(u_guided, clamp(p, ivec2(0), u_guidedSize - 1), 0).rg; }
vec2 lo_guided() {
    vec2 uv = u_rect.xy + v_uv * u_rect.zw;
    vec2 p = uv * vec2(u_guidedSize) - 0.5;
    vec2 f = floor(p);
    vec2 t = p - f;
    ivec2 i = ivec2(f);
    vec2 top = mix(lo_texel(i), lo_texel(i + ivec2(1, 0)), t.x);
    vec2 bottom = mix(lo_texel(i + ivec2(0, 1)), lo_texel(i + ivec2(1, 1)), t.x);
    return mix(top, bottom, t.y);
}
)";

// An sRGB token colour (0xRRGGBB) as linear values of the display's primaries.
void clipColor(std::uint32_t rgb, const color::Mat3& rec2020ToDisplay, float out[3]) {
    float linear[4] = {color::srgbEotf(((rgb >> 16) & 0xFFu) / 255.f), color::srgbEotf(((rgb >> 8) & 0xFFu) / 255.f),
                       color::srgbEotf((rgb & 0xFFu) / 255.f), 1.f};
    color::applyMatrix(color::kSrgbToRec2020, linear, linear, 1);
    color::applyMatrix(rec2020ToDisplay, linear, linear, 1);
    for (int c = 0; c < 3; ++c) out[c] = linear[c] < 0.f ? 0.f : (linear[c] > 1.f ? 1.f : linear[c]);
}

}  // namespace

bool GpuPipeline::init(std::string* error) {
    halfFloat_ = halfFloatRenderable();
    glGenVertexArrays(1, &vao_);
    glBindVertexArray(vao_);
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuad), kQuad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glGenFramebuffers(1, &fbo_);
    displayProgram_ = linkProgram(kDisplayVertex, kDisplayFragment, error);
    if (displayProgram_ == 0) return false;
    displayTex_ = glGetUniformLocation(displayProgram_, "u_texture");
    displayMatrix_ = glGetUniformLocation(displayProgram_, "u_matrix");
    displayToDisplay_ = glGetUniformLocation(displayProgram_, "u_toDisplay");
    displayEncode_ = glGetUniformLocation(displayProgram_, "u_encode");
    displayClip_ = glGetUniformLocation(displayProgram_, "u_clip");
    displayClipHi_ = glGetUniformLocation(displayProgram_, "u_clipHi");
    displayClipLo_ = glGetUniformLocation(displayProgram_, "u_clipLo");
    GLint major = 0, minor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    glGetIntegerv(GL_MINOR_VERSION, &minor);
    if (major > 3 || (major == 3 && minor >= 1)) {
        std::string computeError;
        histogramProgram_ = linkCompute(kHistogramCompute, &computeError);
        if (histogramProgram_ != 0) {
            histogramToSrgb_ = glGetUniformLocation(histogramProgram_, "u_toSrgb");
            histogramSize_ = glGetUniformLocation(histogramProgram_, "u_size");
            glGenBuffers(1, &histogramBuffer_);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, histogramBuffer_);
            glBufferData(GL_SHADER_STORAGE_BUFFER, kHistogramSize * sizeof(std::uint32_t), nullptr, GL_DYNAMIC_READ);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        } else {
            histogramError_ = computeError;
        }
    }
    return true;
}

void GpuPipeline::release() {
    for (auto& [key, program] : opPrograms_) glDeleteProgram(program);
    opPrograms_.clear();
    for (auto& [size, texture] : scratch_) glDeleteTextures(1, &texture);
    scratch_.clear();
    if (displayProgram_ != 0) glDeleteProgram(displayProgram_);
    if (histogramProgram_ != 0) glDeleteProgram(histogramProgram_);
    if (histogramBuffer_ != 0) glDeleteBuffers(1, &histogramBuffer_);
    histogramProgram_ = histogramBuffer_ = 0;
    releaseReadback();
    if (fbo_ != 0) glDeleteFramebuffers(1, &fbo_);
    if (vbo_ != 0) glDeleteBuffers(1, &vbo_);
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    displayProgram_ = fbo_ = vbo_ = vao_ = 0;
}

GLuint GpuPipeline::programFor(const std::string& declarations, const std::string& body, std::string* error) {
    const std::string key = declarations + "\n" + body;
    auto it = opPrograms_.find(key);
    if (it != opPrograms_.end()) return it->second;
    const std::string fragment = "#version 300 es\nprecision highp float;\nuniform sampler2D u_source;\n" + declarations +
                                 "\nin vec2 v_uv;\nout vec4 o_color;\n" + kOpPrelude + "vec4 lo_op(vec4 c) {\n" + body +
                                 "\n}\nvoid main() { o_color = lo_op(texture(u_source, v_uv)); }\n";
    const GLuint program = linkProgram(kOpVertex, fragment, error);
    if (program != 0) opPrograms_[key] = program;
    return program;
}

GLuint GpuPipeline::scratch(int width, int height) {
    auto it = scratch_.find({width, height});
    if (it != scratch_.end()) return it->second;
    const GLuint texture = createTexture(width, height, halfFloat_, nullptr);
    scratch_[{width, height}] = texture;
    return texture;
}

namespace {

// Collects one op's shader and uniforms.
class Recorder final : public GpuOpContext {
public:
    std::string declarations, body;
    std::vector<std::pair<std::string, float>> uniforms;
    std::vector<std::pair<std::string, std::vector<float>>> matrices;  // row-major mat3
    std::vector<std::pair<std::string, std::vector<float>>> arrays;
    void setShader(const char* d, const char* b) override {
        declarations = d;
        body = b;
    }
    void setUniform1f(const char* name, float value) override { uniforms.emplace_back(name, value); }
    void setUniformMat3(const char* name, const float* m) override { matrices.emplace_back(name, std::vector<float>(m, m + 9)); }
    void setUniform1fv(const char* name, const float* v, int n) override { arrays.emplace_back(name, std::vector<float>(v, v + n)); }
};

}  // namespace

bool GpuPipeline::runOps(const OpList& ops, GLuint source, GLuint target, int width, int height, const OpPassInputs& inputs,
                         std::string* error) {
    std::vector<const Op*> passes;
    for (const auto& op : ops.ops()) {
        if (!op->isIdentity()) passes.push_back(op.get());
    }
    static const std::unique_ptr<Op> kCopy = makeIdentity();
    if (passes.empty()) passes.push_back(kCopy.get());

    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glBindVertexArray(vao_);
    // Ping-pong so the last pass writes `target`: with an even pass count the first
    // pass writes the scratch texture.
    const GLuint temp = passes.size() > 1 ? scratch(width, height) : 0;
    GLuint in = source;
    for (std::size_t i = 0; i < passes.size(); ++i) {
        const std::size_t remaining = passes.size() - i;
        const GLuint out = remaining % 2 == 1 ? target : temp;
        Recorder recorder;
        passes[i]->gpu(recorder);
        const GLuint program = programFor(recorder.declarations, recorder.body, error);
        if (program == 0) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return false;
        }
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, out, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            *error = "op framebuffer incomplete";
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return false;
        }
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, in);
        glUniform1i(glGetUniformLocation(program, "u_source"), 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, inputs.guided);
        glUniform1i(glGetUniformLocation(program, "u_guided"), 1);
        glUniform4fv(glGetUniformLocation(program, "u_rect"), 1, inputs.rect);
        glUniform2i(glGetUniformLocation(program, "u_guidedSize"), inputs.guidedWidth, inputs.guidedHeight);
        glActiveTexture(GL_TEXTURE0);
        for (const auto& [name, value] : recorder.uniforms) glUniform1f(glGetUniformLocation(program, name.c_str()), value);
        for (const auto& [name, m] : recorder.matrices) glUniformMatrix3fv(glGetUniformLocation(program, name.c_str()), 1, GL_TRUE, m.data());
        for (const auto& [name, v] : recorder.arrays) {
            glUniform1fv(glGetUniformLocation(program, name.c_str()), static_cast<GLsizei>(v.size()), v.data());
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        in = out;
    }
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

void GpuPipeline::drawImage(GLuint texture, const float rect[4], const float imageToNdc[9], color::Display display,
                            bool encode, const DisplayOptions& options) {
    // matrix = imageToNdc * (unit quad -> rect in image pixels)
    const float tile[9] = {rect[2], 0.f, rect[0], 0.f, rect[3], rect[1], 0.f, 0.f, 1.f};
    float m[9];
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            m[3 * r + c] = 0.f;
            for (int k = 0; k < 3; ++k) m[3 * r + c] += imageToNdc[3 * r + k] * tile[3 * k + c];
        }
    const color::Mat3& toDisplay = display == color::Display::DisplayP3 ? color::kRec2020ToP3 : color::kRec2020ToSrgb;
    glUseProgram(displayProgram_);
    glBindVertexArray(vao_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glUniform1i(displayTex_, 0);
    glUniformMatrix3fv(displayMatrix_, 1, GL_TRUE, m);
    glUniformMatrix3fv(displayToDisplay_, 1, GL_TRUE, toDisplay.m);
    glUniform1i(displayEncode_, encode ? 1 : 0);
    glUniform1i(displayClip_, options.clipping ? 1 : 0);
    if (options.clipping) {
        float hi[3], lo[3];
        clipColor(0xFF2D55u, toDisplay, hi);
        clipColor(0x2D7BFFu, toDisplay, lo);
        glUniform3fv(displayClipHi_, 1, hi);
        glUniform3fv(displayClipLo_, 1, lo);
    }
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

bool GpuPipeline::dispatchHistogram(GLuint texture, int width, int height) {
    if (histogramProgram_ == 0 || texture == 0) return false;
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, histogramBuffer_);
    static const std::vector<std::uint32_t> kZero(kHistogramSize, 0u);
    glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, kHistogramSize * sizeof(std::uint32_t), kZero.data());
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, histogramBuffer_);
    glUseProgram(histogramProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glUniformMatrix3fv(histogramToSrgb_, 1, GL_TRUE, color::kRec2020ToSrgb.m);
    glUniform2i(histogramSize_, width, height);
    glDispatchCompute(static_cast<GLuint>((width + 15) / 16), static_cast<GLuint>((height + 15) / 16), 1);
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    return true;
}

bool GpuPipeline::readHistogram(std::uint32_t out[kHistogramSize]) {
    if (histogramProgram_ == 0) return false;
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, histogramBuffer_);
    const void* mapped = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, kHistogramSize * sizeof(std::uint32_t), GL_MAP_READ_BIT);
    const bool ok = mapped != nullptr;
    if (ok) std::memcpy(out, mapped, kHistogramSize * sizeof(std::uint32_t));
    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    return ok;
}

bool GpuPipeline::startReadback(GLuint texture, int width, int height) {
    if (!halfFloat_ || texture == 0) return false;
    const std::size_t bytes = static_cast<std::size_t>(width) * height * 4 * sizeof(float);
    if (packBuffer_ == 0) glGenBuffers(1, &packBuffer_);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer_);
    if (bytes != packBytes_) {
        glBufferData(GL_PIXEL_PACK_BUFFER, static_cast<GLsizeiptr>(bytes), nullptr, GL_STREAM_READ);
        packBytes_ = bytes;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, nullptr);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    packWidth_ = width;
    packHeight_ = height;
    return true;
}

bool GpuPipeline::finishReadback(std::vector<float>* out) {
    if (packBuffer_ == 0 || packBytes_ == 0) return false;
    glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer_);
    const void* mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, static_cast<GLsizeiptr>(packBytes_), GL_MAP_READ_BIT);
    const bool ok = mapped != nullptr;
    if (ok) {
        out->resize(packBytes_ / sizeof(float));
        std::memcpy(out->data(), mapped, packBytes_);
    }
    glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    return ok;
}

void GpuPipeline::releaseReadback() {
    if (packBuffer_ != 0) glDeleteBuffers(1, &packBuffer_);
    packBuffer_ = 0;
    packBytes_ = 0;
}

bool GpuPipeline::readFloat(GLuint texture, int width, int height, float* out) { return readRegion(texture, 0, 0, width, height, out); }

bool GpuPipeline::readRegion(GLuint texture, int x, int y, int width, int height, float* out) {
    if (!halfFloat_) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(x, y, width, height, GL_RGBA, GL_FLOAT, out);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

}  // namespace lo::android
