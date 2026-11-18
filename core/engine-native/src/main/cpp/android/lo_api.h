/* The engine's thin C API on Android. The JNI bridge only marshals Java values to these
 * calls; nothing here throws. Every function is safe to call from any thread unless noted.
 * Return values: 1 = success, 0 = failure (with a message in `error` when given). */
#ifndef LINEOVER_LO_API_H
#define LINEOVER_LO_API_H

#include <android/native_window.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct lo_engine lo_engine;

/* Pixel formats of decoded bitmaps (lo::PixelFormat). */
enum {
    LO_FORMAT_RGBA8_SRGB = 0,
    LO_FORMAT_RGBA8_DISPLAY_P3 = 1,
    LO_FORMAT_RGBAF16_LINEAR_EXTENDED_SRGB = 2,
};

typedef struct {
    size_t cpu_tile_bytes;
    size_t cpu_tiles;
    size_t spilled_tiles;
    size_t spill_bytes;
    size_t gpu_bytes;
    size_t gpu_tiles;
    size_t pending_tiles;
    uint64_t frames;
    uint64_t tiles_decoded;
    int half_float;
    int display_p3;
    int level;
} lo_engine_stats;

lo_engine* lo_engine_create(const char* cache_dir, size_t cpu_budget, size_t gpu_budget, char* error, size_t error_len);
void lo_engine_destroy(lo_engine* engine);

void lo_engine_open_image(lo_engine* engine, int width, int height);
/* The preview proxy, already oriented (ImageDecoder applies EXIF orientation). */
int lo_engine_set_preview(lo_engine* engine, const void* pixels, size_t stride, int width, int height, int format);
/* A decoded raw region for tile (level, tx, ty): see lo::RegionSource. */
int lo_engine_submit_region(lo_engine* engine, int level, int tx, int ty, const void* pixels, size_t stride, int format,
                            int region_x, int region_y, int region_w, int region_h, int raw_level_w, int raw_level_h,
                            int orientation);
void lo_engine_fail_tile(lo_engine* engine, int level, int tx, int ty);
int lo_engine_take_request(lo_engine* engine, int timeout_ms, int* level, int* tx, int* ty);
int lo_engine_set_graph(lo_engine* engine, const char* json, char* error, size_t error_len);

int lo_engine_attach_window(lo_engine* engine, ANativeWindow* window, char* error, size_t error_len);
void lo_engine_detach_window(lo_engine* engine);
void lo_engine_set_viewport(lo_engine* engine, int width, int height);
void lo_engine_set_transform(lo_engine* engine, float a, float b, float c, float d, float e, float f);
void lo_engine_set_surround(lo_engine* engine, float r, float g, float b);
void lo_engine_get_stats(lo_engine* engine, lo_engine_stats* out);

/* `on_loading` runs on the render thread when sharp tiles start / stop loading;
 * `on_thread_exit` runs once when the render thread ends. Set before any drawing. */
void lo_engine_set_callbacks(lo_engine* engine, void (*on_loading)(void* user, int loading), void (*on_thread_exit)(void* user),
                             void* user);

/* Test hook: graph on the GPU over `in` (width x height working RGBA float), sRGB display
 * transform into `out` (RGBA8, premultiplied). */
int lo_engine_gpu_self_test(lo_engine* engine, const float* in, int width, int height, const char* graph, uint8_t* out,
                            char* error, size_t error_len);

#ifdef __cplusplus
}
#endif

#endif /* LINEOVER_LO_API_H */
