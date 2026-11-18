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
    uint32_t p95_frame_interval_us;
    uint32_t p95_draw_us;
    uint32_t p95_update_us; /* setGraph -> new preview presented */
    uint32_t last_update_us;
    uint64_t updates;
    int histogram; /* GLES 3.1 compute available */
} lo_engine_stats;

/* Compare modes (lo::android::CompareMode). */
enum { LO_COMPARE_OFF = 0, LO_COMPARE_SPLIT = 1, LO_COMPARE_SIDE_BY_SIDE = 2 };

/* Histogram size: 4 channels (R, G, B, L) x 256 bins, channel-major. */
enum { LO_HISTOGRAM_SIZE = 1024 };

lo_engine* lo_engine_create(const char* cache_dir, size_t cpu_budget, size_t gpu_budget, char* error, size_t error_len);
void lo_engine_destroy(lo_engine* engine);

void lo_engine_open_image(lo_engine* engine, int width, int height);
/* The preview proxy, already oriented (ImageDecoder applies EXIF orientation). */
int lo_engine_set_preview(lo_engine* engine, const void* pixels, size_t stride, int width, int height, int format,
                          int premultiplied);
/* A decoded raw region for tile (level, tx, ty): see lo::RegionSource. */
int lo_engine_submit_region(lo_engine* engine, int level, int tx, int ty, const void* pixels, size_t stride, int format,
                            int region_x, int region_y, int region_w, int region_h, int raw_level_w, int raw_level_h,
                            int orientation, int premultiplied);
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

/* Light tools: Auto from the preview's luminance percentiles (0 before a preview). */
int lo_engine_auto_light(lo_engine* engine, float* exposure, float* blacks);
void lo_engine_set_show_original(lo_engine* engine, int original);
void lo_engine_set_compare(lo_engine* engine, int mode, float split);
void lo_engine_set_clipping(lo_engine* engine, int clipping);
/* `on_histogram` runs on the render thread with LO_HISTOGRAM_SIZE counts, at most 30 Hz
 * while enabled. */
void lo_engine_set_histogram_callback(lo_engine* engine, void (*on_histogram)(void* user, const uint32_t* bins), void* user);
void lo_engine_set_histogram_enabled(lo_engine* engine, int enabled);

/* Full-resolution CPU render of a decoded tile (export path). Returns the pixel count
 * written to `out` (RGBA float, capacity `capacity` floats) or 0 when the tile is not decoded. */
int lo_engine_render_tile_cpu(lo_engine* engine, int level, int tx, int ty, float* out, size_t capacity, int* width,
                              int* height);

/* Test hook: graph on the GPU over `in` (width x height working RGBA float), sRGB display
 * transform into `out` (RGBA8, premultiplied). */
int lo_engine_gpu_self_test(lo_engine* engine, const float* in, int width, int height, const char* graph, uint8_t* out,
                            char* error, size_t error_len);

/* Test hook: the histogram compute pass over `in` (width x height working RGBA float)
 * into `out` (LO_HISTOGRAM_SIZE counts). */
int lo_engine_gpu_histogram_self_test(lo_engine* engine, const float* in, int width, int height, uint32_t* out, char* error,
                                      size_t error_len);

#ifdef __cplusplus
}
#endif

#endif /* LINEOVER_LO_API_H */
