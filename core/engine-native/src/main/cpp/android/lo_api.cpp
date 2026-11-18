#include "lo_api.h"

#include <cstring>
#include <memory>
#include <string>

#include "engine.h"
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

int lo_engine_set_preview(lo_engine* engine, const void* pixels, size_t stride, int width, int height, int format) {
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
    std::vector<float> working(static_cast<std::size_t>(width) * height * 4);
    lo::regionToTile(source, 0, 0, width, height, working.data());
    engine->engine->setPreview(std::move(working), width, height);
    return 1;
}

int lo_engine_submit_region(lo_engine* engine, int level, int tx, int ty, const void* pixels, size_t stride, int format,
                            int region_x, int region_y, int region_w, int region_h, int raw_level_w, int raw_level_h,
                            int orientation) {
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
}

void lo_engine_set_callbacks(lo_engine* engine, void (*on_loading)(void*, int), void (*on_thread_exit)(void*), void* user) {
    if (on_loading != nullptr) {
        engine->engine->setLoadingListener([on_loading, user](bool loading) { on_loading(user, loading ? 1 : 0); });
    }
    if (on_thread_exit != nullptr) engine->engine->setThreadExitHook([on_thread_exit, user] { on_thread_exit(user); });
}

int lo_engine_gpu_self_test(lo_engine* engine, const float* in, int width, int height, const char* graph, uint8_t* out,
                            char* error, size_t error_len) {
    std::string failure;
    if (!engine->engine->gpuSelfTest(in, width, height, graph, out, &failure)) {
        copyError(failure, error, error_len);
        return 0;
    }
    return 1;
}

}  // extern "C"
