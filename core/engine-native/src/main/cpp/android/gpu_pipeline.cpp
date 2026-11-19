#include "gpu_pipeline.h"

#include <cstring>
#include <vector>

#include "gl_util.h"
#include "../src/op_util.h"

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
// Gamut mapping (color::gamutMap, reference.gamut_map): out-of-gamut colours keep their
// OKLab lightness (clamped) and hue; 16 bisection steps of chroma towards the grey L^3.
constexpr const char* kDisplayHead = "#version 300 es\nprecision highp float;\n" LO_GLSL_OKLAB R"(
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
bool lo_inside(vec3 d) {
    return all(greaterThanEqual(d, vec3(-5e-4))) && all(lessThanEqual(d, vec3(1.0005)));
}
vec3 lo_gamut(vec3 w, vec3 raw) {
    if (lo_inside(raw)) return clamp(raw, 0.0, 1.0);
    vec3 lab = lo_toLab(w);
    lab.x = clamp(lab.x, 0.0, 1.0);
    vec3 grey = lo_toLab(vec3(lab.x * lab.x * lab.x));
    float lo = 0.0;
    float hi = 1.0;
    for (int i = 0; i < 16; ++i) {
        float mid = 0.5 * (lo + hi);
        if (lo_inside(u_toDisplay * lo_fromLab(mix(grey, lab, mid)))) lo = mid; else hi = mid;
    }
    return clamp(u_toDisplay * lo_fromLab(mix(grey, lab, lo)), 0.0, 1.0);
}
)";

// The plain source of the display pass: the texture as drawn.
constexpr const char* kPlainSource = R"(
vec4 lo_source() { return texture(u_texture, v_uv); }
)";

// Geometry (Stage 9, geometry.h): the drawn quad is a rect of the OUTPUT image; each
// fragment maps back to the source (matrix, lens distortion, a radial scale per channel)
// and samples the texture with OpenCV's bicubic (Keys a = -0.75, exact positions, border 0
// for the whole image; clamped inside a tile, whose neighbours are other draws). A tile's
// fragments outside its own source rect are discarded.
constexpr const char* kGeometrySource = R"(
uniform mat3 u_geom;      // output normalised -> source pixels (before lens)
uniform vec2 u_outSize;   // output pixels
uniform vec2 u_srcSize;   // source pixels
uniform vec4 u_lens;      // k1, k2, caRed, caBlue
uniform vec4 u_dist;      // a lens profile's distortion p1..p4 (s += p1 r + p2 r^2 + p3 r^3 + p4 r^4)
uniform vec3 u_tcaR;      // its red / blue scale terms on the distorted radius: tv, tc, tb
uniform vec3 u_tcaB;
uniform vec4 u_srcRect;   // the texture's rect in normalised source coordinates
uniform vec4 u_drawRect;  // the drawn quad in output pixels
uniform int u_clampTile;
// ch: 0 red, 1 green, 2 blue (geometry.cpp mapToSource).
vec2 lo_map(vec2 uvOut, int ch) {
    vec3 p = u_geom * vec3(uvOut, 1.0);
    vec2 px = p.xy / p.z;
    vec2 c = 0.5 * u_srcSize;
    float radius = 0.5 * length(u_srcSize);
    vec2 q = px - c;
    float r2 = dot(q, q) / (radius * radius);
    float r = sqrt(r2);
    float s = 1.0 + u_lens.x * r2 + u_lens.y * r2 * r2 + r * (u_dist.x + r * (u_dist.y + r * (u_dist.z + r * u_dist.w)));
    if (ch != 1) {
        vec3 t = ch == 0 ? u_tcaR : u_tcaB;
        float rd = r * s;
        s *= 1.0 + (ch == 0 ? u_lens.z : u_lens.w) + t.x + rd * (t.y + rd * t.z);
    }
    return c + q * s;
}
vec4 lo_cubic(float t) {
    const float A = -0.75;
    float w0 = ((A * (t + 1.0) - 5.0 * A) * (t + 1.0) + 8.0 * A) * (t + 1.0) - 4.0 * A;
    float w1 = ((A + 2.0) * t - (A + 3.0)) * t * t + 1.0;
    float w2 = ((A + 2.0) * (1.0 - t) - (A + 3.0)) * (1.0 - t) * (1.0 - t) + 1.0;
    return vec4(w0, w1, w2, 1.0 - w0 - w1 - w2);
}
// One channel at source pixel position px (edges at 0 and size).
float lo_tap(ivec2 p, ivec2 size, int ch) {
    if (u_clampTile == 1) p = clamp(p, ivec2(0), size - 1);
    else if (p.x < 0 || p.y < 0 || p.x >= size.x || p.y >= size.y) return 0.0;
    vec4 t = texelFetch(u_texture, p, 0);
    return ch == 0 ? t.r : ch == 1 ? t.g : ch == 2 ? t.b : t.a;
}
float lo_channel(vec2 px, int ch) {
    ivec2 size = textureSize(u_texture, 0);
    vec2 t = ((px / u_srcSize - u_srcRect.xy) / u_srcRect.zw) * vec2(size) - 0.5;
    vec2 f = floor(t);
    vec4 wx = lo_cubic(t.x - f.x);
    vec4 wy = lo_cubic(t.y - f.y);
    ivec2 i = ivec2(f) - 1;
    float sum = 0.0;
    for (int y = 0; y < 4; ++y) {
        float row = wx.x * lo_tap(i + ivec2(0, y), size, ch) + wx.y * lo_tap(i + ivec2(1, y), size, ch) +
                    wx.z * lo_tap(i + ivec2(2, y), size, ch) + wx.w * lo_tap(i + ivec2(3, y), size, ch);
        sum += (y == 0 ? wy.x : y == 1 ? wy.y : y == 2 ? wy.z : wy.w) * row;
    }
    return sum;
}
vec4 lo_source() {
    vec2 uvOut = (u_drawRect.xy + v_uv * u_drawRect.zw) / u_outSize;
    vec2 g = lo_map(uvOut, 1);
    vec2 s = g / u_srcSize;
    // The whole image: taps outside it read 0 (OpenCV's BORDER_CONSTANT), so the edge
    // fades exactly like the reference and wedges stay transparent. A tile draws only its
    // own rect (its neighbours are other draws).
    if (u_clampTile == 1 && (s.x < u_srcRect.x || s.y < u_srcRect.y || s.x >= u_srcRect.x + u_srcRect.z || s.y >= u_srcRect.y + u_srcRect.w)) discard;
    vec4 c = vec4(0.0, lo_channel(g, 1), 0.0, lo_channel(g, 3));
    bool ca = u_lens.z != 0.0 || u_lens.w != 0.0 || any(notEqual(u_tcaR, vec3(0.0))) || any(notEqual(u_tcaB, vec3(0.0)));
    c.r = lo_channel(ca ? lo_map(uvOut, 0) : g, 0);
    c.b = lo_channel(ca ? lo_map(uvOut, 2) : g, 2);
    return c;
}
)";

constexpr const char* kDisplayMain = R"(
void main() {
    vec4 c = lo_source();
    vec3 raw = u_toDisplay * c.rgb;
    vec3 d = lo_gamut(c.rgb, raw);
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

// The geometry warp into a working-space texture (histogram of the output, self test).
constexpr const char* kWarpMain = R"(
void main() { o_color = lo_source(); }
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
uniform highp sampler2D u_lut;
// LUT row `row` at e (0..1, linear between the 4096 entries), slope 1 outside 0..1.
float lo_lut1(int row, float e) {
    float c = clamp(e, 0.0, 1.0);
    float p = c * 4095.0;
    float i = min(floor(p), 4094.0);
    float f = p - i;
    return mix(texelFetch(u_lut, ivec2(int(i), row), 0).r, texelFetch(u_lut, ivec2(int(i) + 1, row), 0).r, f) + (e - c);
}
vec3 lo_lut3(int row, float t) {
    float p = clamp(t, 0.0, 1.0) * 4095.0;
    float i = min(floor(p), 4094.0);
    float f = p - i;
    return mix(texelFetch(u_lut, ivec2(int(i), row), 0).rgb, texelFetch(u_lut, ivec2(int(i) + 1, row), 0).rgb, f);
}
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
    displayProgram_ = linkProgram(kDisplayVertex, (std::string(kDisplayHead) + kPlainSource + kDisplayMain).c_str(), error);
    if (displayProgram_ == 0) return false;
    displayGeomProgram_ = linkProgram(kDisplayVertex, (std::string(kDisplayHead) + kGeometrySource + kDisplayMain).c_str(), error);
    if (displayGeomProgram_ == 0) return false;
    warpProgram_ = linkProgram(kDisplayVertex, (std::string(kDisplayHead) + kGeometrySource + kWarpMain).c_str(), error);
    if (warpProgram_ == 0) return false;
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
    if (displayGeomProgram_ != 0) glDeleteProgram(displayGeomProgram_);
    if (warpProgram_ != 0) glDeleteProgram(warpProgram_);
    displayGeomProgram_ = warpProgram_ = 0;
    if (histogramProgram_ != 0) glDeleteProgram(histogramProgram_);
    if (histogramBuffer_ != 0) glDeleteBuffers(1, &histogramBuffer_);
    histogramProgram_ = histogramBuffer_ = 0;
    releaseReadback();
    for (auto& [hash, texture] : luts_) glDeleteTextures(1, &texture);
    luts_.clear();
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
    void setLut(const float* rgba, int w, int h) override {
        lut = rgba;
        lutWidth = w;
        lutHeight = h;
    }
    const float* lut = nullptr;
    int lutWidth = 0, lutHeight = 0;
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
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, recorder.lut != nullptr ? lutTexture(recorder.lut, recorder.lutWidth, recorder.lutHeight) : 0);
        glUniform1i(glGetUniformLocation(program, "u_lut"), 2);
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

namespace {

// matrix = imageToNdc * (unit quad -> rect in image pixels)
void quadMatrix(const float rect[4], const float imageToNdc[9], float m[9]) {
    const float tile[9] = {rect[2], 0.f, rect[0], 0.f, rect[3], rect[1], 0.f, 0.f, 1.f};
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            m[3 * r + c] = 0.f;
            for (int k = 0; k < 3; ++k) m[3 * r + c] += imageToNdc[3 * r + k] * tile[3 * k + c];
        }
}

void setGeometryUniforms(GLuint program, const GeometryDraw& g) {
    glUniformMatrix3fv(glGetUniformLocation(program, "u_geom"), 1, GL_TRUE, g.matrix);
    glUniform2fv(glGetUniformLocation(program, "u_outSize"), 1, g.outSize);
    glUniform2fv(glGetUniformLocation(program, "u_srcSize"), 1, g.srcSize);
    glUniform4fv(glGetUniformLocation(program, "u_lens"), 1, g.lens);
    glUniform4fv(glGetUniformLocation(program, "u_dist"), 1, g.dist);
    glUniform3fv(glGetUniformLocation(program, "u_tcaR"), 1, g.tcaRed);
    glUniform3fv(glGetUniformLocation(program, "u_tcaB"), 1, g.tcaBlue);
    glUniform4fv(glGetUniformLocation(program, "u_srcRect"), 1, g.srcRect);
    glUniform4fv(glGetUniformLocation(program, "u_drawRect"), 1, g.drawRect);
    glUniform1i(glGetUniformLocation(program, "u_clampTile"), g.clampTile ? 1 : 0);
}

}  // namespace

void GpuPipeline::drawImage(GLuint texture, const float rect[4], const float imageToNdc[9], color::Display display,
                            bool encode, const DisplayOptions& options, const GeometryDraw* geometry) {
    float m[9];
    quadMatrix(rect, imageToNdc, m);
    const color::Mat3& toDisplay = display == color::Display::DisplayP3 ? color::kRec2020ToP3 : color::kRec2020ToSrgb;
    const GLuint program = geometry != nullptr ? displayGeomProgram_ : displayProgram_;
    glUseProgram(program);
    glBindVertexArray(vao_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glUniform1i(glGetUniformLocation(program, "u_texture"), 0);
    glUniformMatrix3fv(glGetUniformLocation(program, "u_matrix"), 1, GL_TRUE, m);
    glUniformMatrix3fv(glGetUniformLocation(program, "u_toDisplay"), 1, GL_TRUE, toDisplay.m);
    glUniform1i(glGetUniformLocation(program, "u_encode"), encode ? 1 : 0);
    glUniform1i(glGetUniformLocation(program, "u_clip"), options.clipping ? 1 : 0);
    if (options.clipping) {
        float hi[3], lo[3];
        clipColor(0xFF2D55u, toDisplay, hi);
        clipColor(0x2D7BFFu, toDisplay, lo);
        glUniform3fv(glGetUniformLocation(program, "u_clipHi"), 1, hi);
        glUniform3fv(glGetUniformLocation(program, "u_clipLo"), 1, lo);
    }
    if (geometry != nullptr) setGeometryUniforms(program, *geometry);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

bool GpuPipeline::warp(GLuint texture, const GeometryDraw& geometry, GLuint target, int width, int height, std::string* error) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        if (error != nullptr) *error = "warp target is not renderable";
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return false;
    }
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    // The whole target is the output: the unit quad straight to NDC.
    const float toNdc[9] = {2.f, 0.f, -1.f, 0.f, 2.f, -1.f, 0.f, 0.f, 1.f};
    glUseProgram(warpProgram_);
    glBindVertexArray(vao_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glUniform1i(glGetUniformLocation(warpProgram_, "u_texture"), 0);
    glUniformMatrix3fv(glGetUniformLocation(warpProgram_, "u_matrix"), 1, GL_TRUE, toNdc);
    setGeometryUniforms(warpProgram_, geometry);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

GLuint GpuPipeline::lutTexture(const float* rgba, int width, int height) {
    // Content hash (FNV-1a over the bytes): a slider drag re-creates the op, not the table.
    const std::size_t bytes = static_cast<std::size_t>(width) * height * 4 * sizeof(float);
    std::uint64_t hash = 1469598103934665603ull ^ (static_cast<std::uint64_t>(width) << 32) ^ static_cast<std::uint64_t>(height);
    const auto* p = reinterpret_cast<const unsigned char*>(rgba);
    for (std::size_t i = 0; i < bytes; ++i) hash = (hash ^ p[i]) * 1099511628211ull;
    for (auto it = luts_.begin(); it != luts_.end(); ++it) {
        if (it->first == hash) {
            luts_.splice(luts_.begin(), luts_, it);
            return luts_.front().second;
        }
    }
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, rgba);
    luts_.emplace_front(hash, texture);
    while (luts_.size() > kMaxLuts) {
        glDeleteTextures(1, &luts_.back().second);
        luts_.pop_back();
    }
    return texture;
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
