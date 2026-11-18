#include "gpu_pipeline.h"

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

constexpr const char* kDisplayFragment = R"(#version 300 es
precision highp float;
uniform sampler2D u_texture;
uniform mat3 u_toDisplay;
uniform int u_encode;
in vec2 v_uv;
out vec4 o_color;
float oetf(float x) {
    return x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}
void main() {
    vec4 c = texture(u_texture, v_uv);
    vec3 d = clamp(u_toDisplay * c.rgb, 0.0, 1.0);
    if (u_encode == 1) d = vec3(oetf(d.r), oetf(d.g), oetf(d.b));
    float a = clamp(c.a, 0.0, 1.0);
    o_color = vec4(d * a, a);
}
)";

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
    return true;
}

void GpuPipeline::release() {
    for (auto& [key, program] : opPrograms_) glDeleteProgram(program);
    opPrograms_.clear();
    for (auto& [size, texture] : scratch_) glDeleteTextures(1, &texture);
    scratch_.clear();
    if (displayProgram_ != 0) glDeleteProgram(displayProgram_);
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
                                 "\nin vec2 v_uv;\nout vec4 o_color;\nvec4 lo_op(vec4 c) {\n" + body +
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
    void setShader(const char* d, const char* b) override {
        declarations = d;
        body = b;
    }
    void setUniform1f(const char* name, float value) override { uniforms.emplace_back(name, value); }
};

}  // namespace

bool GpuPipeline::runOps(const OpList& ops, GLuint source, GLuint target, int width, int height, std::string* error) {
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
        for (const auto& [name, value] : recorder.uniforms) glUniform1f(glGetUniformLocation(program, name.c_str()), value);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        in = out;
    }
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

void GpuPipeline::drawImage(GLuint texture, const float rect[4], const float imageToNdc[9], color::Display display,
                            bool encode) {
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
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

}  // namespace lo::android
