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
#include "lineover/detail.h"
#include "lineover/light.h"
#include "lineover/masks.h"

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
    const float* guide = nullptr;  // Stage 16: the background of an export region (guided mask rasters)
    // Stage 17: the background (the photo, edited, in document space) that local mask components
    // read, and the image-wide fields the adjustment layers' Highlights / Shadows, Clarity,
    // Texture and Dehaze read (computed from it, document uv).
    const float* image = nullptr;
    std::shared_ptr<const LayerFields> fields = nullptr;
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

// A pass-through group's opacity [k], times [weights] per pixel when the group is masked.
void lerpSpace(const float* before, float* after, float amount, const std::vector<float>& weights, const Context& ctx) {
    if (amount >= 1.f && weights.empty()) return;
    for (std::size_t p = 0; p < ctx.pixels(); ++p) {
        const float k = weights.empty() ? amount : amount * weights[p];
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

// --- Adjustment layer ops (Stage 17) ----------------------------------------------------------

bool readsFields(const std::string& op) {
    return op == "highlights_shadows" || op == "clarity" || op == "texture" || op == "dehaze";
}

bool needsFields(const OpList& ops) {
    return std::any_of(ops.ops().begin(), ops.ops().end(), [](const std::unique_ptr<Op>& op) {
        if (op->isIdentity()) return false;
        const OpType t = op->type();
        return t == OpType::HighlightsShadows || t == OpType::Clarity || t == OpType::Texture || t == OpType::Dehaze;
    });
}

// Where the region lies in the document (normalised), for ops that read image-wide fields.
OpRegion opRegion(const Context& ctx) {
    const Region& r = *ctx.region;
    const double docW = r.docWidth > 0 ? r.docWidth : r.originX + r.width;
    const double docH = r.docHeight > 0 ? r.docHeight : r.originY + r.height;
    OpRegion o;
    o.width = r.width;
    o.height = r.height;
    o.rect[0] = static_cast<float>(r.originX / docW);
    o.rect[1] = static_cast<float>(r.originY / docH);
    o.rect[2] = static_cast<float>(r.width / docW);
    o.rect[3] = static_cast<float>(r.height / docH);
    o.scale = static_cast<float>(r.scale);
    if (ctx.fields) {
        o.coefficients = &ctx.fields->coefficients;
        o.fields = &ctx.fields->detail;
    }
    return o;
}

bool layersNeedFields(const std::vector<Layer>& layers) {
    for (const Layer& l : layers) {
        if (!l.visible) continue;
        if (l.type == "adjustment" && std::any_of(l.ops.begin(), l.ops.end(), [](const OpParams& p) { return readsFields(p.op); })) {
            if (needsFields(*buildLayerOps(l.ops, nullptr))) return true;
        }
        if (l.type == "group" && layersNeedFields(l.children)) return true;
    }
    return false;
}

bool layersReadImage(const std::vector<Layer>& layers) {
    return std::any_of(layers.begin(), layers.end(), [](const Layer& l) {
        return (l.hasMask && l.mask.enabled && masks::readsImage(l.mask)) || layersReadImage(l.children);
    });
}

// The fields of whole-document backgrounds by graph (most recent first); an export region
// (part of the document) uses the largest one made for its background.
struct CachedFields {
    std::string key;
    std::shared_ptr<const LayerFields> fields = nullptr;
};
std::mutex gFieldsMutex;
std::list<CachedFields> gFields;
constexpr std::size_t kFieldsCache = 4;

std::string backgroundKey(const Graph& graph) {
    Graph g = graph;
    g.layers.clear();
    if (const Layer* bg = backgroundLayer(graph)) g.layers.push_back(*bg);
    return serializeGraph(g);
}

std::shared_ptr<const LayerFields> documentFields(const Graph& graph, const float* rgba, const Region& region) {
    const std::string key = backgroundKey(graph);
    const bool whole = region.originX == 0.0 && region.originY == 0.0 &&
                       (region.docWidth <= 0 || region.docWidth == region.width) &&
                       (region.docHeight <= 0 || region.docHeight == region.height);
    {
        std::lock_guard<std::mutex> lock(gFieldsMutex);
        std::shared_ptr<const LayerFields> best;
        for (auto it = gFields.begin(); it != gFields.end(); ++it) {
            if (it->key != key) continue;
            const LayerFields& f = *it->fields;
            if (whole && f.width == region.width && f.height == region.height) {
                gFields.splice(gFields.begin(), gFields, it);
                return gFields.front().fields;
            }
            if (!whole && (!best || f.width * f.height > best->width * best->height)) best = it->fields;
        }
        if (!whole) return best;  // none: the export runs those ops without fields (reported)
    }
    auto made = std::make_shared<LayerFields>();
    made->width = region.width;
    made->height = region.height;
    made->coefficients = guidedCoefficients(rgba, region.width, region.height);
    made->detail = computeDetailFields(rgba, region.width, region.height);
    std::lock_guard<std::mutex> lock(gFieldsMutex);
    gFields.push_front({key, made});
    if (gFields.size() > kFieldsCache) gFields.pop_back();
    return made;
}

// --- Masks (Stage 14) ------------------------------------------------------------------------

struct CachedMask {
    std::string key;
    std::shared_ptr<const masks::MaskTiles> tiles;
};
std::mutex gMaskMutex;
std::list<CachedMask> gMasks;  // most recent first
constexpr std::size_t kMaskCache = 16;

masks::MaskRegion maskRegion(const Context& ctx) {
    const Region& r = *ctx.region;
    masks::MaskRegion m;
    m.originX = static_cast<int>(std::lround(r.originX));
    m.originY = static_cast<int>(std::lround(r.originY));
    m.width = r.width;
    m.height = r.height;
    m.docWidth = r.docWidth > 0 ? r.docWidth : m.originX + r.width;
    m.docHeight = r.docHeight > 0 ? r.docHeight : m.originY + r.height;
    m.scale = r.scale;
    return m;
}

// The layer's final mask over the region (1 = shown), from R16F tiles; empty when it has
// none or it is disabled. Rendered masks are cached (a slider that is not the mask's does not
// re-render it).
std::vector<float> maskWeights(const Layer& layer, const Context& ctx) {
    if (!layer.hasMask || !layer.mask.enabled) return {};
    const masks::MaskRegion region = maskRegion(ctx);
    const std::string key = serializeMaskJson(layer.mask) + "@" + std::to_string(region.originX) + "," +
                            std::to_string(region.originY) + "," + std::to_string(region.width) + "x" +
                            std::to_string(region.height) + "/" + std::to_string(region.docWidth) + "x" +
                            std::to_string(region.docHeight) + "*" + std::to_string(region.scale);
    std::shared_ptr<const masks::MaskTiles> tiles;
    if (masks::readsImage(layer.mask)) {  // Stage 17: reads the photo, which the key does not hold
        auto rendered = std::make_shared<masks::MaskTiles>();
        masks::render(layer.mask, region, rendered.get(), ctx.image);
        tiles = rendered;
    } else if (ctx.guide != nullptr) {  // an export region: guided by its own pixels, never cached
        auto rendered = std::make_shared<masks::MaskTiles>();
        masks::render(layer.mask, region, rendered.get(), ctx.guide);
        tiles = rendered;
    }
    if (!tiles) {
        std::lock_guard<std::mutex> lock(gMaskMutex);
        for (auto it = gMasks.begin(); it != gMasks.end(); ++it) {
            if (it->key == key) {
                tiles = it->tiles;
                gMasks.splice(gMasks.begin(), gMasks, it);
                break;
            }
        }
    }
    if (!tiles) {
        auto rendered = std::make_shared<masks::MaskTiles>();
        masks::render(layer.mask, region, rendered.get());
        tiles = rendered;
        std::lock_guard<std::mutex> lock(gMaskMutex);
        gMasks.push_front({key, tiles});
        if (gMasks.size() > kMaskCache) gMasks.pop_back();
    }
    std::vector<float> out(ctx.pixels());
    for (int j = 0; j < region.height; ++j)
        for (int i = 0; i < region.width; ++i) out[static_cast<std::size_t>(j) * region.width + i] = tiles->at(i, j);
    return out;
}

void multiply(std::vector<float>* weights, const std::vector<float>& by) {
    if (by.empty()) return;
    if (weights->empty()) {
        *weights = by;
        return;
    }
    for (std::size_t p = 0; p < weights->size(); ++p) (*weights)[p] *= by[p];
}

const Layer* findLayer(const std::vector<Layer>& layers, const std::string& id) {
    for (const Layer& l : layers) {
        if (l.id == id) return &l;
        if (const Layer* c = findLayer(l.children, id)) return c;
    }
    return nullptr;
}

// Layers bottom to top. A clipped layer shows only where its base (the nearest unclipped layer
// below it among its siblings) covers: the base's shape alpha x its mask x its opacity; a
// hidden base hides it. Adjustment layers and pass-through groups cover everywhere.
void renderLayers(const std::vector<Layer>& layers, float* buffer, const Context& ctx) {
    const std::size_t floats = ctx.pixels() * 4;
    bool hasBase = false;
    std::vector<float> base;  // coverage of the clipping base (empty = everywhere)
    for (const Layer& layer : layers) {
        if (layer.type.empty() || layer.type == "background") continue;
        const bool clipped = layer.clip && hasBase;
        if (!layer.visible) {
            if (!clipped) {
                base.assign(ctx.pixels(), 0.f);
                hasBase = true;
            }
            continue;
        }
        const float amount = static_cast<float>(layer.opacity / 100.0 * layer.fill / 100.0);
        const std::vector<float> mask = maskWeights(layer, ctx);
        std::vector<float> weight = mask;
        if (clipped) {
            if (base.empty()) {
                if (weight.empty()) weight.assign(ctx.pixels(), 1.f);
            } else {
                multiply(&weight, base);
            }
        }
        std::vector<float> shape;  // the layer's own alpha (empty = everywhere)
        if (layer.type == "group") {
            if (layer.isolated) {
                std::vector<float> group(floats, 0.f);
                renderLayers(layer.children, group.data(), ctx);
                shape.resize(ctx.pixels());
                for (std::size_t p = 0; p < ctx.pixels(); ++p) {
                    shape[p] = group[4 * p + 3];
                    if (!weight.empty()) group[4 * p + 3] *= weight[p];
                }
                compositeBuffer(buffer, group.data(), modeOf(layer), amount, ctx);
            } else {
                std::vector<float> before(buffer, buffer + floats);
                renderLayers(layer.children, buffer, ctx);
                lerpSpace(before.data(), buffer, amount, weight, ctx);
            }
        } else if (layer.type == "raster" || layer.type == "smart") {
            auto image = findLayerImage(layer.source, ctx.full);
            if (!image || image->width <= 0) {
                report(ctx.skipped, "layer:" + layer.source);
                shape.assign(ctx.pixels(), 0.f);
            } else {
                if (layer.type == "smart") image = smartImage(image, layer, ctx.skipped);
                std::vector<float> src(floats);
                sampleLayer(*image, layer, ctx, src.data());
                shape.resize(ctx.pixels());
                for (std::size_t p = 0; p < ctx.pixels(); ++p) {
                    shape[p] = src[4 * p + 3];
                    if (!weight.empty()) src[4 * p + 3] *= weight[p];
                }
                compositeBuffer(buffer, src.data(), modeOf(layer), amount, ctx);
            }
        } else if (layer.type == "adjustment") {
            std::vector<float> src(buffer, buffer + floats);
            const auto ops = buildLayerOps(layer.ops, ctx.skipped);
            if (needsFields(*ops) && !ctx.fields) report(ctx.skipped, "layer-fields");
            ops->cpuRefRegion(src.data(), src.data(), opRegion(ctx));
            for (std::size_t p = 0; p < ctx.pixels(); ++p) src[4 * p + 3] = weight.empty() ? 1.f : weight[p];
            compositeBuffer(buffer, src.data(), modeOf(layer), amount, ctx);
        } else {
            shape.assign(ctx.pixels(), 0.f);  // "text" / "shape": Stages 23 / 24
        }
        if (!clipped) {
            const float opacity = static_cast<float>(layer.opacity / 100.0);
            base = shape;
            multiply(&base, mask);
            if (base.empty()) base.assign(ctx.pixels(), 1.f);
            for (float& v : base) v *= opacity;
            hasBase = true;
        }
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

namespace {

int layersApron(const std::vector<Layer>& layers) {
    int apron = 0;
    for (const Layer& l : layers) {
        if (!l.visible) continue;
        int a = 0;
        if (l.type == "adjustment") a += buildLayerOps(l.ops, nullptr)->apron();
        if (l.hasMask && l.mask.enabled && masks::readsImage(l.mask)) {
            // Image-reading components see only the region: the feather's and the auto-mask's reach.
            a += static_cast<int>(masks::gaussianKernel(l.mask.feather).size() / 2);
            for (const MaskComponent& c : l.mask.components) {
                if (c.kind != "brush" || !c.autoMask) continue;
                double radius = 0.0;
                for (const MaskPaint& p : c.strokes) radius = std::max(radius, p.radius);
                a += 2 * std::max(1, static_cast<int>(std::nearbyint(radius / 4.0))) + 1;
            }
        }
        apron = std::max(apron, a + layersApron(l.children));
    }
    return apron;
}

}  // namespace

int layerApron(const Graph& graph) { return layersApron(graph.layers); }

bool hasLayers(const Graph& graph) {
    return std::any_of(graph.layers.begin(), graph.layers.end(), [](const Layer& l) {
        return !(l.type.empty() || l.type == "background") && draws(l);
    });
}

void compositeLayers(const Graph& graph, float* rgba, const Region& region, bool full, std::string* skipped) {
    if (region.width <= 0 || region.height <= 0 || !hasLayers(graph)) return;
    Context ctx{&region, graph.blendSpace != "linear", full, skipped};
    // Exports guide mask rasters by the full-resolution background (Stage 16); local mask
    // components read it everywhere (Stage 17).
    std::vector<float> background;
    const bool readsImage = layersReadImage(graph.layers);
    if (full || readsImage) background.assign(rgba, rgba + ctx.pixels() * 4);
    if (full) ctx.guide = background.data();
    if (readsImage) ctx.image = background.data();
    if (layersNeedFields(graph.layers)) ctx.fields = documentFields(graph, rgba, region);
    renderLayers(graph.layers, rgba, ctx);
}

void showMask(const Graph& graph, const std::string& layerId, MaskView view, float* rgba, const Region& region,
              const float* background) {
    if (view == MaskView::None || region.width <= 0 || region.height <= 0) return;
    const Layer* layer = findLayer(graph.layers, layerId);
    if (layer == nullptr) return;
    Context ctx{&region, true, false, nullptr};
    ctx.image = background;
    const std::vector<float> m = maskWeights(*layer, ctx);
    if (m.empty()) return;
    // Rubylith: Photoshop's 50 % red where the mask hides.
    constexpr float kRed[3] = {1.f, 0.f, 0.f};
    for (std::size_t p = 0; p < ctx.pixels(); ++p) {
        float* o = rgba + 4 * p;
        if (view == MaskView::MaskOnly) {
            const float g = color::srgbEotf(std::clamp(m[p], 0.f, 1.f));
            o[0] = o[1] = o[2] = g;
        } else {
            const float k = 0.5f * (1.f - std::clamp(m[p], 0.f, 1.f));
            for (int c = 0; c < 3; ++c) o[c] = color::srgbEotf(color::srgbOetf(o[c]) + k * (kRed[c] - color::srgbOetf(o[c])));
        }
        o[3] = 1.f;
    }
}

}  // namespace lo::layers
