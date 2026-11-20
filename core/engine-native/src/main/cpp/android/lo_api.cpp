#include "lo_api.h"

#include <algorithm>
#include <vector>
#include <cstring>
#include <memory>
#include <string>

#include "engine.h"
#include "lineover/color_ops.h"
#include "lineover/layers.h"
#include "lineover/lut.h"
#include "lineover/tiling.h"

struct lo_engine {
    std::unique_ptr<lo::android::Engine> engine;
    std::mutex sizeMutex;
    int width = 0;
    int height = 0;
};

namespace {

void copyError(const std::string& message, char* error, std::size_t length) {
    if (error == nullptr || length == 0) return;
    std::strncpy(error, message.c_str(), length - 1);
    error[length - 1] = '\0';
}

bool validFormat(int format) { return format >= LO_FORMAT_RGBA8_SRGB && format <= LO_FORMAT_RGBAF16_LINEAR_EXTENDED_SRGB; }

}  // namespace

extern "C" {

lo_engine* lo_engine_create(const char* cache_dir, size_t cpu_budget, size_t gpu_budget, char* error, size_t error_len) {
    lo::android::EngineConfig config;
    config.cacheDir = cache_dir;
    config.cpuBudget = cpu_budget;
    config.gpuBudget = gpu_budget;
    auto handle = std::make_unique<lo_engine>();
    handle->engine = std::make_unique<lo::android::Engine>(config);
    std::string failure;
    if (!handle->engine->start(&failure)) {
        copyError(failure, error, error_len);
        return nullptr;
    }
    return handle.release();
}

void lo_engine_destroy(lo_engine* engine) { delete engine; }

void lo_engine_open_image(lo_engine* engine, int width, int height) {
    {
        std::lock_guard<std::mutex> lock(engine->sizeMutex);
        engine->width = width;
        engine->height = height;
    }
    engine->engine->openImage(width, height);
}

int lo_engine_set_preview(lo_engine* engine, const void* pixels, size_t stride, int width, int height, int format,
                          int premultiplied) {
    if (pixels == nullptr || width <= 0 || height <= 0 || !validFormat(format)) return 0;
    lo::RegionSource source;
    source.pixels = static_cast<const std::uint8_t*>(pixels);
    source.stride = stride;
    source.format = static_cast<lo::PixelFormat>(format);
    source.regionW = width;
    source.regionH = height;
    source.rawLevelW = width;
    source.rawLevelH = height;
    source.orientation = 1;
    source.premultiplied = premultiplied != 0;
    std::vector<float> working(static_cast<std::size_t>(width) * height * 4);
    lo::regionToTile(source, 0, 0, width, height, working.data());
    engine->engine->setPreview(std::move(working), width, height);
    return 1;
}

int lo_engine_submit_region(lo_engine* engine, int level, int tx, int ty, const void* pixels, size_t stride, int format,
                            int region_x, int region_y, int region_w, int region_h, int raw_level_w, int raw_level_h,
                            int orientation, int premultiplied) {
    const lo::TileKey key{level, tx, ty};
    int width, height;
    {
        std::lock_guard<std::mutex> lock(engine->sizeMutex);
        width = engine->width;
        height = engine->height;
    }
    const lo::LevelInfo info = lo::levelInfo(width, height, level);
    if (pixels == nullptr || !validFormat(format) || region_w <= 0 || region_h <= 0 || tx < 0 || ty < 0 || tx >= info.tilesX ||
        ty >= info.tilesY) {
        engine->engine->failTile(key);
        return 0;
    }
    const lo::Rect rect = lo::tileRect(info, tx, ty);
    lo::RegionSource source;
    source.pixels = static_cast<const std::uint8_t*>(pixels);
    source.stride = stride;
    source.format = static_cast<lo::PixelFormat>(format);
    source.regionX = region_x;
    source.regionY = region_y;
    source.regionW = region_w;
    source.regionH = region_h;
    source.rawLevelW = raw_level_w;
    source.rawLevelH = raw_level_h;
    source.orientation = orientation;
    source.premultiplied = premultiplied != 0;
    auto tile = std::make_shared<lo::Tile>();
    tile->width = rect.w;
    tile->height = rect.h;
    tile->pixels.resize(static_cast<std::size_t>(rect.w) * rect.h * 4);
    lo::regionToTile(source, rect.x, rect.y, rect.w, rect.h, tile->pixels.data());
    engine->engine->submitTile(key, std::move(tile));
    return 1;
}

void lo_engine_fail_tile(lo_engine* engine, int level, int tx, int ty) { engine->engine->failTile({level, tx, ty}); }

int lo_engine_take_request(lo_engine* engine, int timeout_ms, int* level, int* tx, int* ty) {
    lo::TileKey key;
    if (!engine->engine->takeRequest(timeout_ms, &key)) return 0;
    *level = key.level;
    *tx = key.tx;
    *ty = key.ty;
    return 1;
}

int lo_engine_set_graph(lo_engine* engine, const char* json, char* error, size_t error_len) {
    std::string failure;
    if (!engine->engine->setGraph(json, &failure)) {
        copyError(failure, error, error_len);
        return 0;
    }
    return 1;
}

int lo_engine_attach_window(lo_engine* engine, ANativeWindow* window, char* error, size_t error_len) {
    std::string failure;
    if (!engine->engine->attachWindow(window, &failure)) {
        copyError(failure, error, error_len);
        return 0;
    }
    return 1;
}

void lo_engine_detach_window(lo_engine* engine) { engine->engine->detachWindow(); }

void lo_engine_set_viewport(lo_engine* engine, int width, int height) { engine->engine->setViewport(width, height); }

void lo_engine_set_transform(lo_engine* engine, float a, float b, float c, float d, float e, float f) {
    lo::Affine t;
    t.a = a;
    t.b = b;
    t.c = c;
    t.d = d;
    t.e = e;
    t.f = f;
    engine->engine->setTransform(t);
}

void lo_engine_set_surround(lo_engine* engine, float r, float g, float b) { engine->engine->setSurround(r, g, b); }

void lo_engine_get_stats(lo_engine* engine, lo_engine_stats* out) {
    const lo::android::EngineStats s = engine->engine->stats();
    out->cpu_tile_bytes = s.cpuTileBytes;
    out->cpu_tiles = s.cpuTiles;
    out->spilled_tiles = s.spilledTiles;
    out->spill_bytes = s.spillBytes;
    out->gpu_bytes = s.gpuBytes;
    out->gpu_tiles = s.gpuTiles;
    out->pending_tiles = s.pendingTiles;
    out->frames = s.frames;
    out->tiles_decoded = s.tilesDecoded;
    out->half_float = s.halfFloat ? 1 : 0;
    out->display_p3 = s.displayP3 ? 1 : 0;
    out->level = s.level;
    out->p95_frame_interval_us = s.p95FrameIntervalUs;
    out->p95_draw_us = s.p95DrawUs;
    out->p95_update_us = s.p95UpdateUs;
    out->last_update_us = s.lastUpdateUs;
    out->updates = s.updates;
    out->histogram = s.histogram ? 1 : 0;
}

int lo_engine_sample(lo_engine* engine, float u, float v, int before_op_type, float* rgb) {
    return engine->engine->samplePatch(u, v, static_cast<lo::OpType>(before_op_type), rgb) ? 1 : 0;
}

int lo_engine_sample_grid(lo_engine* engine, float u, float v, int n, float* rgba) {
    return engine->engine->sampleGrid(u, v, n, rgba) ? 1 : 0;
}

int lo_white_balance_for_neutral(const float* rgb, float* kelvin, float* tint) {
    return lo::colorops::solveNeutral(rgb, kelvin, tint) ? 1 : 0;
}

void lo_hsl_targeted_weights(const float* rgb, float* weights) { lo::colorops::targetedWeights(rgb, weights); }

int lo_engine_auto_light(lo_engine* engine, float* exposure, float* blacks) {
    return engine->engine->autoLight(exposure, blacks) ? 1 : 0;
}

int lo_engine_auto_levels(lo_engine* engine, float* out6) { return engine->engine->autoLevels(out6) ? 1 : 0; }

int lo_engine_render_thumbnail(lo_engine* engine, const char* graph_json, uint8_t* out, int capacity, int* width, int* height) {
    return lo_engine_render_thumbnail_sized(engine, graph_json, 512, out, capacity, width, height);
}

int lo_engine_auto_settings(lo_engine* engine, float* out7) {
    lo::AutoSettings s;
    if (!engine->engine->autoSettings(&s)) return 0;
    const float v[7] = {s.temperature, s.tint, s.exposure, s.contrast, s.whites, s.blacks, s.vibrance};
    std::copy(v, v + 7, out7);
    return 1;
}

int lo_register_lut(const char* id, int size, const float* domain_min3, const float* domain_max3, const float* rgb) {
    if (id == nullptr || rgb == nullptr || size < lo::kLutMinSize || size > lo::kLutMaxSize) return 0;
    auto lut = std::make_shared<lo::Lut3D>();
    lut->size = size;
    for (int k = 0; k < 3; ++k) {
        lut->domainMin[k] = domain_min3[k];
        lut->domainMax[k] = domain_max3[k];
    }
    lut->rgb.assign(rgb, rgb + static_cast<std::size_t>(size) * size * size * 3);
    if (!lut->finish()) return 0;
    lo::registerLut(id, std::move(lut));
    return 1;
}

int lo_register_layer_image(const char* id, const void* pixels, size_t stride, int width, int height, int format,
                            int premultiplied, int full) {
    if (id == nullptr || pixels == nullptr || width <= 0 || height <= 0 || !validFormat(format)) return 0;
    lo::RegionSource source;
    source.pixels = static_cast<const std::uint8_t*>(pixels);
    source.stride = stride;
    source.format = static_cast<lo::PixelFormat>(format);
    source.regionW = width;
    source.regionH = height;
    source.rawLevelW = width;
    source.rawLevelH = height;
    source.orientation = 1;
    source.premultiplied = premultiplied != 0;
    auto image = std::make_shared<lo::ImageF32>();
    image->width = width;
    image->height = height;
    image->rgba.resize(static_cast<std::size_t>(width) * height * 4);
    lo::regionToTile(source, 0, 0, width, height, image->rgba.data());
    lo::layers::registerLayerImage(id, std::move(image), full != 0);
    return 1;
}

void lo_unregister_layer_image(const char* id) {
    if (id != nullptr) lo::layers::unregisterLayerImage(id);
}

void lo_release_full_layer_images(void) { lo::layers::releaseFullLayerImages(); }

void lo_engine_render_composite(lo_engine* engine) { engine->engine->renderComposite(); }

void lo_engine_set_mask_view(lo_engine* engine, const char* layer_id, int view) {
    engine->engine->setMaskView(layer_id != nullptr ? layer_id : "", view);
}

void lo_unregister_lut(const char* id) {
    if (id != nullptr) lo::unregisterLut(id);
}

int lo_engine_render_thumbnail_sized(lo_engine* engine, const char* graph_json, int max_edge, uint8_t* out, int capacity,
                                     int* width, int* height) {
    std::vector<std::uint8_t> rgba;
    int w = 0, h = 0;
    if (!engine->engine->renderThumbnail(graph_json, &rgba, &w, &h, max_edge)) return 0;
    if (static_cast<std::size_t>(capacity) < rgba.size()) return 0;
    std::copy(rgba.begin(), rgba.end(), out);
    *width = w;
    *height = h;
    return 1;
}

void lo_engine_set_show_original(lo_engine* engine, int original) { engine->engine->setShowOriginal(original != 0); }

void lo_engine_set_compare(lo_engine* engine, int mode, float split) {
    const int m = mode < LO_COMPARE_OFF || mode > LO_COMPARE_SIDE_BY_SIDE ? LO_COMPARE_OFF : mode;
    engine->engine->setCompare(static_cast<lo::android::CompareMode>(m), split);
}

void lo_engine_set_clipping(lo_engine* engine, int clipping) { engine->engine->setClipping(clipping != 0); }

void lo_engine_set_histogram_callback(lo_engine* engine, void (*on_histogram)(void*, const uint32_t*), void* user) {
    if (on_histogram == nullptr) {
        engine->engine->setHistogramListener(nullptr);
        return;
    }
    engine->engine->setHistogramListener([on_histogram, user](const std::uint32_t* bins) { on_histogram(user, bins); });
}

void lo_engine_set_histogram_enabled(lo_engine* engine, int enabled) { engine->engine->setHistogramEnabled(enabled != 0); }

int lo_engine_gpu_histogram_self_test(lo_engine* engine, const float* in, int width, int height, uint32_t* out, char* error,
                                      size_t error_len) {
    std::string failure;
    if (!engine->engine->gpuHistogramSelfTest(in, width, height, out, &failure)) {
        copyError(failure, error, error_len);
        return 0;
    }
    return 1;
}

void lo_engine_set_callbacks(lo_engine* engine, void (*on_loading)(void*, int), void (*on_thread_exit)(void*), void* user) {
    if (on_loading != nullptr) {
        engine->engine->setLoadingListener([on_loading, user](bool loading) { on_loading(user, loading ? 1 : 0); });
    }
    if (on_thread_exit != nullptr) engine->engine->setThreadExitHook([on_thread_exit, user] { on_thread_exit(user); });
}

int lo_engine_render_tile_cpu(lo_engine* engine, int level, int tx, int ty, float* out, size_t capacity, int* width,
                              int* height) {
    std::vector<float> pixels;
    if (!engine->engine->renderTileCpu({level, tx, ty}, &pixels, width, height) || pixels.size() > capacity) return 0;
    std::copy(pixels.begin(), pixels.end(), out);
    return static_cast<int>(pixels.size() / 4);
}

void lo_engine_output_size(lo_engine* engine, int* width, int* height) { engine->engine->outputSize(width, height); }

int lo_engine_output_tile_sources(lo_engine* engine, int tx, int ty, int* out, size_t capacity) {
    const std::vector<lo::TileKey> keys = engine->engine->outputTileSources(tx, ty);
    for (std::size_t i = 0; i < keys.size() && 2 * i + 1 < capacity; ++i) {
        out[2 * i] = keys[i].tx;
        out[2 * i + 1] = keys[i].ty;
    }
    return static_cast<int>(keys.size());
}

int lo_engine_render_output_tile(lo_engine* engine, int tx, int ty, float* out, size_t capacity, int* width, int* height) {
    std::vector<float> pixels;
    if (!engine->engine->renderOutputTile(tx, ty, &pixels, width, height) || pixels.size() > capacity) return 0;
    std::copy(pixels.begin(), pixels.end(), out);
    return static_cast<int>(pixels.size() / 4);
}

int lo_engine_line_segments(lo_engine* engine, float* out, size_t capacity) {
    std::vector<lo::LineSegment> segments;
    if (!engine->engine->lineSegments(&segments)) return -1;
    for (std::size_t i = 0; i < segments.size() && 5 * i + 4 < capacity; ++i) {
        const lo::LineSegment& s = segments[i];
        const float values[5] = {s.x1, s.y1, s.x2, s.y2, s.width};
        std::copy(values, values + 5, out + 5 * i);
    }
    return static_cast<int>(segments.size());
}

int lo_engine_horizon(lo_engine* engine, float* degrees) { return engine->engine->horizon(degrees) ? 1 : 0; }

int lo_engine_chromatic_aberration(lo_engine* engine, float* ca_red, float* ca_blue) {
    return engine->engine->chromaticAberration(ca_red, ca_blue) ? 1 : 0;
}

int lo_warp_rgba8(const char* graph_json, int source_width, int source_height, const uint8_t* rgba, int width, int height,
                  int out_width, int out_height, uint8_t* out) {
    lo::Graph graph;
    std::string error;
    if (!lo::parseGraph(graph_json, &graph, &error)) return 0;
    lo::Geometry geometry;
    if (!lo::geometryOf(graph, &geometry)) return 0;
    const std::vector<std::uint8_t> result =
        lo::warpRgba8(geometry, source_width, source_height, rgba, width, height, out_width, out_height);
    if (result.size() != static_cast<std::size_t>(out_width) * out_height * 4) return 0;
    std::copy(result.begin(), result.end(), out);
    return 1;
}

int lo_engine_gpu_self_test(lo_engine* engine, const float* in, int width, int height, const char* graph, uint8_t* out,
                            size_t capacity, char* error, size_t error_len) {
    std::string failure;
    if (!engine->engine->gpuSelfTest(in, width, height, graph, out, capacity, &failure)) {
        copyError(failure, error, error_len);
        return 0;
    }
    return 1;
}

}  // extern "C"
