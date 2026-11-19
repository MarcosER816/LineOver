// Layers: blend modes and compositor (Stage 13); see layers.h and blend_reference.py.
#include "lineover/layers.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <list>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "lineover/color.h"

namespace lo::layers {
namespace {

constexpr const char* kNames[kBlendModeCount] = {
    "normal", "dissolve", "darken", "multiply", "color_burn", "linear_burn", "darker_color", "lighten", "screen",
    "color_dodge", "linear_dodge", "lighter_color", "overlay", "soft_light", "hard_light", "vivid_light", "linear_light",
    "pin_light", "hard_mix", "difference", "exclusion", "subtract", "divide", "hue", "saturation", "color", "luminosity",
};

constexpr float kLum[3] = {0.3f, 0.59f, 0.11f};

float lum(const float c[3]) { return kLum[0] * c[0] + kLum[1] * c[1] + kLum[2] * c[2]; }

// W3C ClipColor: L, n and x all come from the colour as it was passed in.
void clipColor(float c[3]) {
    const float l = lum(c);
    const float n = std::min({c[0], c[1], c[2]});
    const float x = std::max({c[0], c[1], c[2]});
    if (n < 0.f && l - n != 0.f)
        for (int k = 0; k < 3; ++k) c[k] = l + (c[k] - l) * l / (l - n);
    if (x > 1.f && x - l != 0.f)
        for (int k = 0; k < 3; ++k) c[k] = l + (c[k] - l) * (1.f - l) / (x - l);
}

void setLum(const float c[3], float l, float out[3]) {
    const float d = l - lum(c);
    for (int k = 0; k < 3; ++k) out[k] = c[k] + d;
    clipColor(out);
}

float sat(const float c[3]) { return std::max({c[0], c[1], c[2]}) - std::min({c[0], c[1], c[2]}); }

void setSat(const float c[3], float s, float out[3]) {
    const float mx = std::max({c[0], c[1], c[2]});
    const float mn = std::min({c[0], c[1], c[2]});
    const float span = mx - mn;
    for (int k = 0; k < 3; ++k) out[k] = span > 0.f ? (c[k] - mn) * s / span : 0.f;
}

float burn(float b, float s) {
    if (b >= 1.f) return 1.f;
    if (s <= 0.f) return 0.f;
    return 1.f - std::min(1.f, (1.f - b) / s);
}

float dodge(float b, float s) {
    if (b <= 0.f) return 0.f;
    if (s >= 1.f) return 1.f;
    return std::min(1.f, b / (1.f - s));
}

float hardLight(float b, float s) { return s <= 0.5f ? b * 2.f * s : b + (2.f * s - 1.f) - b * (2.f * s - 1.f); }

float softLight(float b, float s) {
    const float d = b <= 0.25f ? ((16.f * b - 12.f) * b + 4.f) * b : std::sqrt(b);
    return s <= 0.5f ? b - (1.f - 2.f * s) * b * (1.f - b) : b + (2.f * s - 1.f) * (d - b);
}

float separable(BlendMode m, float b, float s) {
    switch (m) {
        case BlendMode::Darken: return std::min(b, s);
        case BlendMode::Multiply: return b * s;
        case BlendMode::ColorBurn: return burn(b, s);
        case BlendMode::LinearBurn: return std::max(0.f, b + s - 1.f);
        case BlendMode::Lighten: return std::max(b, s);
        case BlendMode::Screen: return b + s - b * s;
        case BlendMode::ColorDodge: return dodge(b, s);
        case BlendMode::LinearDodge: return std::min(1.f, b + s);
        case BlendMode::Overlay: return hardLight(s, b);
        case BlendMode::SoftLight: return softLight(b, s);
        case BlendMode::HardLight: return hardLight(b, s);
        case BlendMode::VividLight: return s <= 0.5f ? burn(b, 2.f * s) : dodge(b, 2.f * s - 1.f);
        case BlendMode::LinearLight: return std::clamp(b + 2.f * s - 1.f, 0.f, 1.f);
        case BlendMode::PinLight: return s <= 0.5f ? std::min(b, 2.f * s) : std::max(b, 2.f * s - 1.f);
        case BlendMode::HardMix: return b + s >= 1.f ? 1.f : 0.f;
        case BlendMode::Difference: return std::fabs(b - s);
        case BlendMode::Exclusion: return b + s - 2.f * b * s;
        case BlendMode::Subtract: return std::max(0.f, b - s);
        case BlendMode::Divide: return s <= 0.f ? (b <= 0.f ? 0.f : 1.f) : std::min(1.f, b / s);
        default: return s;
    }
}

// Dissolve thresholds: a 256 x 256 LCG table (blend_reference.dissolve_table).
constexpr int kDissolveSize = 256;

const std::vector<std::uint8_t>& dissolveTable() {
    static const std::vector<std::uint8_t> table = [] {
        std::vector<std::uint8_t> t(kDissolveSize * kDissolveSize);
        std::uint32_t state = 0x12345678u;
        for (auto& v : t) {
            state = state * 1664525u + 1013904223u;
            v = static_cast<std::uint8_t>(state >> 24);
        }
        return t;
    }();
    return table;
}

int wrap(double v) {
    const long long i = static_cast<long long>(std::floor(v));
    return static_cast<int>(((i % kDissolveSize) + kDissolveSize) % kDissolveSize);
}

// --- Layer image registry -----------------------------------------------------------------

std::mutex gImagesMutex;
std::unordered_map<std::string, std::shared_ptr<const ImageF32>> gPreviewImages;
std::unordered_map<std::string, std::shared_ptr<const ImageF32>> gFullImages;

// Smart objects: the source with its ops applied, cached (most recent first).
struct SmartEntry {
    const ImageF32* source;
    std::string ops;
    std::shared_ptr<const ImageF32> result;
};
std::mutex gSmartMutex;
std::list<SmartEntry> gSmart;
constexpr std::size_t kSmartCache = 6;

std::shared_ptr<const ImageF32> smartImage(const std::shared_ptr<const ImageF32>& source, const Layer& layer, std::string* skipped) {
    if (layer.ops.empty()) return source;
    Graph one;
    Layer holder;
    holder.ops = layer.ops;
    one.layers.push_back(holder);
    const std::string key = serializeGraph(one);
    {
        std::lock_guard<std::mutex> lock(gSmartMutex);
        for (auto it = gSmart.begin(); it != gSmart.end(); ++it) {
            if (it->source == source.get() && it->ops == key) {
                gSmart.splice(gSmart.begin(), gSmart, it);
                return gSmart.front().result;
            }
        }
    }
    auto ops = buildPointOps(layer.ops, skipped);
    auto out = std::make_shared<ImageF32>(*source);
    ops->cpuRef(out->rgba.data(), out->rgba.data(), static_cast<std::size_t>(out->width) * out->height);
    std::lock_guard<std::mutex> lock(gSmartMutex);
    gSmart.push_front({source.get(), key, out});
    if (gSmart.size() > kSmartCache) gSmart.pop_back();
    return out;
}

void report(std::string* skipped, const std::string& what) {
    if (skipped == nullptr) return;
    if (!skipped->empty()) skipped->push_back(',');
    skipped->append(what);
}

// --- Rendering ------------------------------------------------------------------------------

struct Context {
    const Region* region;
    bool gamma;
    bool full;
    std::string* skipped;
    double docX(int i) const { return (region->originX + i + 0.5) / region->scale; }
    double docY(int j) const { return (region->originY + j + 0.5) / region->scale; }
    std::size_t pixels() const { return static_cast<std::size_t>(region->width) * region->height; }
};

BlendMode modeOf(const Layer& layer) {
    BlendMode m = BlendMode::Normal;
    blendModeFromName(layer.blend, &m);
    return m;
}

void compositeBuffer(float* back, const float* src, BlendMode mode, float amount, const Context& ctx) {
    const int w = ctx.region->width;
    for (int j = 0; j < ctx.region->height; ++j) {
        const double y = ctx.docY(j);
        for (int i = 0; i < w; ++i) {
            float* b = back + (static_cast<std::size_t>(j) * w + i) * 4;
            compositePixel(mode, amount, ctx.gamma, b, src + (static_cast<std::size_t>(j) * w + i) * 4, ctx.docX(i), y, b);
        }
    }
}

// The placed layer image, bilinear on premultiplied values, transparent outside the source.
void sampleLayer(const ImageF32& image, const Layer& layer, const Context& ctx, float* out) {
    double t[6] = {1, 0, 0, 1, 0, 0};
    if (layer.transform.size() == 6) std::copy(layer.transform.begin(), layer.transform.end(), t);
    const double sw = layer.sourceSize.size() == 2 ? layer.sourceSize[0] : image.width;
    const double sh = layer.sourceSize.size() == 2 ? layer.sourceSize[1] : image.height;
    const double det = t[0] * t[3] - t[1] * t[2];
    const int w = image.width, h = image.height;
    for (int j = 0; j < ctx.region->height; ++j) {
        for (int i = 0; i < ctx.region->width; ++i) {
            float* o = out + (static_cast<std::size_t>(j) * ctx.region->width + i) * 4;
            const double x = ctx.docX(i) - t[4], y = ctx.docY(j) - t[5];
            const double sx = (t[3] * x - t[2] * y) / det, sy = (-t[1] * x + t[0] * y) / det;
            const double u = sx / sw, v = sy / sh;
            if (det == 0.0 || u < 0.0 || u >= 1.0 || v < 0.0 || v >= 1.0) {
                o[0] = o[1] = o[2] = o[3] = 0.f;
                continue;
            }
            const double px = std::clamp(u * w - 0.5, 0.0, static_cast<double>(w - 1));
            const double py = std::clamp(v * h - 0.5, 0.0, static_cast<double>(h - 1));
            const int x0 = static_cast<int>(std::floor(px)), y0 = static_cast<int>(std::floor(py));
            const int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
            const double fx = px - x0, fy = py - y0;
            double acc[4] = {0, 0, 0, 0};
            auto add = [&](int xx, int yy, double wgt) {
                const float* p = image.rgba.data() + (static_cast<std::size_t>(yy) * w + xx) * 4;
                for (int c = 0; c < 3; ++c) acc[c] += wgt * p[c] * p[3];
                acc[3] += wgt * p[3];
            };
            add(x0, y0, (1 - fx) * (1 - fy));
            add(x1, y0, fx * (1 - fy));
            add(x0, y1, (1 - fx) * fy);
            add(x1, y1, fx * fy);
            for (int c = 0; c < 3; ++c) o[c] = acc[3] > 0.0 ? static_cast<float>(acc[c] / acc[3]) : 0.f;
            o[3] = static_cast<float>(acc[3]);
        }
    }
}

void lerpSpace(const float* before, float* after, float k, const Context& ctx) {
    if (k >= 1.f) return;
    for (std::size_t p = 0; p < ctx.pixels(); ++p) {
        const float* b = before + 4 * p;
        float* a = after + 4 * p;
        for (int c = 0; c < 3; ++c) {
            if (ctx.gamma) {
                const float eb = color::srgbOetf(b[c]);
                a[c] = color::srgbEotf(eb + k * (color::srgbOetf(a[c]) - eb));
            } else {
                a[c] = b[c] + k * (a[c] - b[c]);
            }
        }
        a[3] = b[3] + k * (a[3] - b[3]);
    }
}

bool draws(const Layer& layer) {
    if (!layer.visible) return false;
    if (layer.type == "group") return std::any_of(layer.children.begin(), layer.children.end(), draws);
    return layer.type == "raster" || layer.type == "smart" || layer.type == "adjustment";
}

void renderLayers(const std::vector<Layer>& layers, float* buffer, const Context& ctx) {
    const std::size_t floats = ctx.pixels() * 4;
    for (const Layer& layer : layers) {
        if (layer.type.empty() || layer.type == "background" || !layer.visible) continue;
        const float amount = static_cast<float>(layer.opacity / 100.0 * layer.fill / 100.0);
        if (layer.type == "group") {
            if (layer.isolated) {
                std::vector<float> group(floats, 0.f);
                renderLayers(layer.children, group.data(), ctx);
                compositeBuffer(buffer, group.data(), modeOf(layer), amount, ctx);
            } else {
                std::vector<float> before(buffer, buffer + floats);
                renderLayers(layer.children, buffer, ctx);
                lerpSpace(before.data(), buffer, amount, ctx);
            }
        } else if (layer.type == "raster" || layer.type == "smart") {
            auto image = findLayerImage(layer.source, ctx.full);
            if (!image || image->width <= 0) {
                report(ctx.skipped, "layer:" + layer.source);
                continue;
            }
            if (layer.type == "smart") image = smartImage(image, layer, ctx.skipped);
            std::vector<float> src(floats);
            sampleLayer(*image, layer, ctx, src.data());
            compositeBuffer(buffer, src.data(), modeOf(layer), amount, ctx);
        } else if (layer.type == "adjustment") {
            std::vector<float> src(buffer, buffer + floats);
            buildPointOps(layer.ops, ctx.skipped)->cpuRef(src.data(), src.data(), ctx.pixels());
            for (std::size_t p = 0; p < ctx.pixels(); ++p) src[4 * p + 3] = 1.f;
            compositeBuffer(buffer, src.data(), modeOf(layer), amount, ctx);
        }
        // "text" / "shape": Stages 23 / 24.
    }
}

}  // namespace

bool blendModeFromName(const std::string& name, BlendMode* out) {
    for (int i = 0; i < kBlendModeCount; ++i) {
        if (name == kNames[i]) {
            *out = static_cast<BlendMode>(i);
            return true;
        }
    }
    return false;
}

const char* blendModeName(BlendMode mode) { return kNames[static_cast<int>(mode)]; }

void blendColor(BlendMode mode, const float cb[3], const float cs[3], float out[3]) {
    if (mode == BlendMode::Normal || mode == BlendMode::Dissolve) {
        std::copy(cs, cs + 3, out);
        return;
    }
    float b[3], s[3];
    for (int k = 0; k < 3; ++k) {
        b[k] = std::clamp(cb[k], 0.f, 1.f);
        s[k] = std::clamp(cs[k], 0.f, 1.f);
    }
    float tmp[3];
    switch (mode) {
        case BlendMode::DarkerColor: std::copy(lum(s) < lum(b) ? s : b, (lum(s) < lum(b) ? s : b) + 3, out); return;
        case BlendMode::LighterColor: std::copy(lum(s) > lum(b) ? s : b, (lum(s) > lum(b) ? s : b) + 3, out); return;
        case BlendMode::Hue: setSat(s, sat(b), tmp); setLum(tmp, lum(b), out); return;
        case BlendMode::Saturation: setSat(b, sat(s), tmp); setLum(tmp, lum(b), out); return;
        case BlendMode::Color: setLum(s, lum(b), out); return;
        case BlendMode::Luminosity: setLum(b, lum(s), out); return;
        default:
            for (int k = 0; k < 3; ++k) out[k] = separable(mode, b[k], s[k]);
            return;
    }
}

void compositePixel(BlendMode mode, float amount, bool gamma, const float backdrop[4], const float source[4], double docX,
                    double docY, float out[4]) {
    const float ab = backdrop[3];
    float as = source[3] * amount;
    if (mode == BlendMode::Dissolve) {
        const float t = (dissolveTable()[static_cast<std::size_t>(wrap(docY)) * kDissolveSize + static_cast<std::size_t>(wrap(docX))] + 0.5f) / 256.f;
        as = t < as ? 1.f : 0.f;
    }
    float cb[3], cs[3], mixed[3];
    for (int k = 0; k < 3; ++k) {
        cb[k] = gamma ? color::srgbOetf(backdrop[k]) : backdrop[k];
        cs[k] = gamma ? color::srgbOetf(source[k]) : source[k];
    }
    blendColor(mode, cb, cs, mixed);
    const float ao = as + ab * (1.f - as);
    for (int k = 0; k < 3; ++k) {
        float co = 0.f;
        if (ao > 0.f) co = (as * (1.f - ab) * cs[k] + as * ab * mixed[k] + (1.f - as) * ab * cb[k]) / ao;
        out[k] = gamma ? color::srgbEotf(co) : co;
    }
    out[3] = ao;
}

void registerLayerImage(const std::string& id, std::shared_ptr<const ImageF32> image, bool full) {
    std::lock_guard<std::mutex> lock(gImagesMutex);
    (full ? gFullImages : gPreviewImages)[id] = std::move(image);
}

void unregisterLayerImage(const std::string& id) {
    std::lock_guard<std::mutex> lock(gImagesMutex);
    gPreviewImages.erase(id);
    gFullImages.erase(id);
}

void releaseFullLayerImages() {
    std::lock_guard<std::mutex> lock(gImagesMutex);
    gFullImages.clear();
}

std::shared_ptr<const ImageF32> findLayerImage(const std::string& id, bool full) {
    std::lock_guard<std::mutex> lock(gImagesMutex);
    // Export prefers the full image and falls back to the preview one (and the other way round).
    auto& first = full ? gFullImages : gPreviewImages;
    auto& second = full ? gPreviewImages : gFullImages;
    if (auto it = first.find(id); it != first.end()) return it->second;
    if (auto it = second.find(id); it != second.end()) return it->second;
    return nullptr;
}

bool hasLayers(const Graph& graph) {
    return std::any_of(graph.layers.begin(), graph.layers.end(), [](const Layer& l) {
        return !(l.type.empty() || l.type == "background") && draws(l);
    });
}

void compositeLayers(const Graph& graph, float* rgba, const Region& region, bool full, std::string* skipped) {
    if (region.width <= 0 || region.height <= 0 || !hasLayers(graph)) return;
    Context ctx{&region, graph.blendSpace != "linear", full, skipped};
    renderLayers(graph.layers, rgba, ctx);
}

}  // namespace lo::layers
