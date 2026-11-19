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
/* Levels Auto: per-channel encoded 0.1 / 99.9 % percentiles (lowR, highR, lowG, highG, lowB, highB). */
int lo_engine_auto_levels(lo_engine* engine, float* out6);

// `graph_json` rendered on a <= 512 px copy of the preview as sRGB RGBA8 into `out`
// (`capacity` bytes); sets the size. 0 before a preview, on a bad graph or a short buffer.
int lo_engine_render_thumbnail(lo_engine* engine, const char* graph_json, uint8_t* out, int capacity, int* width, int* height);
/* The same from the <= 256 px proxy when max_edge <= 256 (preset thumbnails). */
int lo_engine_render_thumbnail_sized(lo_engine* engine, const char* graph_json, int max_edge, uint8_t* out, int capacity,
                                     int* width, int* height);
/* Auto (Stage 11): temperature, tint, exposure, contrast, whites, blacks, vibrance into out7;
 * 0 before a preview or on an image with nothing usable. */
int lo_engine_auto_settings(lo_engine* engine, float* out7);
/* 3D LUTs (process-wide, lut.h): `rgb` holds size^3 RGB triples, red fastest. 0 when the
 * size (2..65), domain or table is invalid. */
int lo_register_lut(const char* id, int size, const float* domain_min3, const float* domain_max3, const float* rgb);
void lo_unregister_lut(const char* id);
void lo_engine_set_show_original(lo_engine* engine, int original);
void lo_engine_set_compare(lo_engine* engine, int mode, float split);
void lo_engine_set_clipping(lo_engine* engine, int clipping);
/* `on_histogram` runs on the render thread with LO_HISTOGRAM_SIZE counts, at most 30 Hz
 * while enabled. */
void lo_engine_set_histogram_callback(lo_engine* engine, void (*on_histogram)(void* user, const uint32_t* bins), void* user);
void lo_engine_set_histogram_enabled(lo_engine* engine, int enabled);

/* Eyedropper / targeted adjustment: the average working RGB of the 5 x 5 preview pixels at
 * image point (u, v), through the ops before the first op of type `before_op_type`
 * (lo::OpType). 0 without a preview. */
int lo_engine_sample(lo_engine* engine, float u, float v, int before_op_type, float* rgb);
/* The n x n source preview pixels around (u, v), RGBA working space (n <= 15). */
int lo_engine_sample_grid(lo_engine* engine, float u, float v, int n, float* rgba);
/* (temperature K, tint) making `rgb` neutral; 0 for colours without a chromaticity. */
int lo_white_balance_for_neutral(const float* rgb, float* kelvin, float* tint);
/* The 8 HSL band weights of `rgb`, faded by chroma (all 0 for grey). */
void lo_hsl_targeted_weights(const float* rgb, float* weights);

/* Full-resolution CPU render of a decoded tile (export path). Returns the pixel count
 * written to `out` (RGBA float, capacity `capacity` floats) or 0 when the tile is not decoded. */
int lo_engine_render_tile_cpu(lo_engine* engine, int level, int tx, int ty, float* out, size_t capacity, int* width,
                              int* height);

/* Geometry (Stage 9). The edited image's size (the geometry's output, else the image). */
void lo_engine_output_size(lo_engine* engine, int* width, int* height);
/* Export: the level-0 source tiles output tile (tx, ty) still needs decoded, as (tx, ty)
 * pairs into `out` (capacity `capacity` ints). Returns the number of tiles (may exceed
 * capacity / 2: then only the first ones were written). */
int lo_engine_output_tile_sources(lo_engine* engine, int tx, int ty, int* out, size_t capacity);
/* Export: output tile (tx, ty) rendered on the CPU (ops, then one resample through the
 * geometry). Returns the pixel count or 0 when a source tile is missing. */
int lo_engine_render_output_tile(lo_engine* engine, int tx, int ty, float* out, size_t capacity, int* width, int* height);
/* Line segments of the preview (x1, y1, x2, y2 normalised, width px) into `out`; returns
 * the segment count (may exceed capacity / 5), -1 before a preview. */
int lo_engine_line_segments(lo_engine* engine, float* out, size_t capacity);
/* Tilt of the dominant near-horizontal line (degrees, clockwise positive); 0 when none. */
int lo_engine_horizon(lo_engine* engine, float* degrees);
/* Red / blue radial scale - 1 undoing lateral chromatic aberration; 0 when undetermined. */
int lo_engine_chromatic_aberration(lo_engine* engine, float* ca_red, float* ca_blue);
/* The geometry op of `graph_json` applied to an RGBA8 image (the gain map) of a photo of
 * source_width x source_height: out_width x out_height RGBA8 into `out`. 1 on success; 0
 * when the graph has no geometry or cannot be parsed. */
int lo_warp_rgba8(const char* graph_json, int source_width, int source_height, const uint8_t* rgba, int width, int height,
                  int out_width, int out_height, uint8_t* out);

/* Test hook: graph on the GPU over `in` (width x height working RGBA float), sRGB display
 * transform into `out` (RGBA8, premultiplied, `capacity` bytes). With a geometry op the
 * result is the geometry's output size. */
int lo_engine_gpu_self_test(lo_engine* engine, const float* in, int width, int height, const char* graph, uint8_t* out,
                            size_t capacity, char* error, size_t error_len);

/* Test hook: the histogram compute pass over `in` (width x height working RGBA float)
 * into `out` (LO_HISTOGRAM_SIZE counts). */
int lo_engine_gpu_histogram_self_test(lo_engine* engine, const float* in, int width, int height, uint32_t* out, char* error,
                                      size_t error_len);

#ifdef __cplusplus
}
#endif

#endif /* LINEOVER_LO_API_H */
