// The LineOver engine on Android: one per open image.
//
// A dedicated render thread owns an EGL context (GLES 3). Everything GL happens there:
// the preview proxy and the visible tiles live as RGBA16F textures (GPU LRU under a
// budget), ops run as shader passes, and the display transform draws them to the
// canvas window in Display P3 or sRGB. Decoded tiles arrive from Kotlin (BitmapRegionDecoder
// threads) as CPU float mirrors in the TileStore, which spills to disk under its budget.
#pragma once

#include <EGL/egl.h>
#include <android/native_window.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "gpu_pipeline.h"
#include "lineover/light.h"
#include "lineover/color.h"
#include "lineover/ops.h"
#include "lineover/tile_store.h"
#include "lineover/tiling.h"

namespace lo::android {

struct EngineConfig {
    std::string cacheDir;        // private spill directory
    std::size_t cpuBudget = 0;   // bytes of float tiles kept in memory
    std::size_t gpuBudget = 0;   // bytes of tile textures kept on the GPU
};

struct EngineStats {
    std::size_t cpuTileBytes = 0;
    std::size_t cpuTiles = 0;
    std::size_t spilledTiles = 0;
    std::size_t spillBytes = 0;
    std::size_t gpuBytes = 0;
    std::size_t gpuTiles = 0;
    std::size_t pendingTiles = 0;  // visible but not yet on screen sharp
    std::uint64_t frames = 0;
    std::uint64_t tilesDecoded = 0;
    bool halfFloat = true;
    bool displayP3 = false;
    int level = -1;  // level of the sharp tiles in view, -1 = preview only
    std::uint32_t p95FrameIntervalUs = 0;  // over the last frames drawn to the window
    std::uint32_t p95DrawUs = 0;
    // Preview update latency: setGraph() -> the frame showing the new preview presented.
    std::uint32_t p95UpdateUs = 0;
    std::uint32_t lastUpdateUs = 0;
    std::uint64_t updates = 0;
    bool histogram = false;  // GLES 3.1 compute (else float readback + CPU binning)
};

// How the canvas compares the edit with the original.
enum class CompareMode : int { Off = 0, Split = 1, SideBySide = 2 };

class Engine {
public:
    explicit Engine(EngineConfig config);
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool start(std::string* error);

    // Image ----------------------------------------------------------------------
    // A new image of width x height (oriented, original pixels). Drops all tiles.
    void openImage(int width, int height);
    // The preview proxy (long edge <= 2048), already converted to working space. Also
    // computes, on the calling thread, what the Light tools need from the whole image:
    // the guided-filter coefficients and the luminance percentiles for Auto.
    void setPreview(std::vector<float> working, int width, int height);
    // Auto (percentile stretch) of the preview; false before a preview was set.
    bool autoLight(float* exposure, float* blacks);
    // A decoded tile of `key` (working space).
    void submitTile(const TileKey& key, std::shared_ptr<const Tile> tile);
    // The tile of `key` cannot be decoded: it is not requested again for this image.
    void failTile(const TileKey& key);
    // Blocks up to timeoutMs for the next tile to decode. False on timeout or shutdown.
    bool takeRequest(int timeoutMs, TileKey* out);
    // The edit graph (EditGraphJson). False with a message when it cannot be parsed.
    bool setGraph(const std::string& json, std::string* error);

    // Display --------------------------------------------------------------------
    bool attachWindow(ANativeWindow* window, std::string* error);
    void detachWindow();
    void setViewport(int width, int height);
    void setTransform(const Affine& imageToView);
    void setSurround(float r, float g, float b);  // sRGB-encoded 0..1
    // Hold to compare: draws the original (no ops) while true.
    void setShowOriginal(bool original);
    // Split: original left of `split` (fraction of the view width); side by side: original
    // in the left half, edit in the right, both views centred like the full view.
    void setCompare(CompareMode mode, float split);
    void setClipping(bool clipping);
    // The histogram of the edited preview (channel-major R, G, B, L x 256 bins), at most
    // 30 times a second while enabled, on the render thread.
    void setHistogramListener(std::function<void(const std::uint32_t*)> listener);
    void setHistogramEnabled(bool enabled);

    EngineStats stats();
    void setLoadingListener(std::function<void(bool)> listener);
    void setThreadExitHook(std::function<void()> hook);

    // Full-resolution path (export): `key`'s stored tile through the ops' cpuRef. False
    // when the tile has not been decoded into the store.
    bool renderTileCpu(const TileKey& key, std::vector<float>* out, int* width, int* height);

    // Test hook: runs `graph` on the GPU over `in` (w x h working RGBA) and renders the
    // sRGB display transform into `out` (RGBA8). Same shaders as the canvas.
    bool gpuSelfTest(const float* in, int width, int height, const std::string& graph, std::uint8_t* out,
                     std::string* error);

    // Test hook: the histogram compute pass over `in` (w x h working RGBA, uploaded as
    // RGBA16F like the preview) into `out` (GpuPipeline::kHistogramSize counts).
    bool gpuHistogramSelfTest(const float* in, int width, int height, std::uint32_t* out, std::string* error);

private:
    struct GpuTile {
        GLuint source = 0;
        GLuint result = 0;  // 0 when the ops are identity (draw source)
        int width = 0;
        int height = 0;
        std::uint64_t graphVersion = 0;
        std::list<TileKey>::iterator lru;
    };

    void loop();
    void post(std::function<void()> task);
    bool runSync(std::function<bool()> task);
    void requestRedraw();
    void frame();
    bool ensureResult(GLuint source, GLuint* result, int width, int height, std::uint64_t* version, const float rect[4]);
    void tileRectNormalised(const TileKey& key, float rect[4]) const;
    void serviceHistogram();
    bool histogramAvailable() const;
    void drawScene(bool original, const float ndc[9], int imageW, int imageH, int level, const std::vector<TileKey>& drawable,
                   bool clipping);
    void evictGpu(const std::unordered_set<TileKey, TileKeyHash>& keep);
    void dropGpuTiles();
    void imageToNdc(float out[9]) const;
    void updateRequests(const std::vector<TileKey>& missing);
    std::size_t textureBytes(int w, int h) const;

    const EngineConfig config_;
    TileStore store_;

    // Render thread
    std::thread thread_;
    std::mutex mutex_;
    std::condition_variable wake_;
    std::deque<std::function<void()>> tasks_;
    bool redraw_ = false;
    bool quit_ = false;
    std::function<void()> threadExitHook_;

    // EGL (render thread)
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig eglConfig_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLSurface window_ = EGL_NO_SURFACE;
    color::Display output_ = color::Display::Srgb;
    bool shaderEncodes_ = true;
    GpuPipeline gpu_;

    // State shared with other threads (guarded by mutex_)
    int imageWidth_ = 0, imageHeight_ = 0;
    int viewWidth_ = 0, viewHeight_ = 0;
    Affine transform_;
    float surround_[3] = {0.0706f, 0.0706f, 0.0706f};
    std::vector<float> pendingPreview_;
    std::shared_ptr<const GuidedCoefficients> guided_;  // of the current preview
    bool guidedChanged_ = false;
    bool havePercentiles_ = false;
    float lowPercentile_ = 0.f, highPercentile_ = 0.f;
    bool showOriginal_ = false;
    CompareMode compareMode_ = CompareMode::Off;
    float split_ = 0.5f;
    bool clipping_ = false;
    std::function<void(const std::uint32_t*)> histogramListener_;
    // When each recent graph version arrived (setGraph), for the preview update latency.
    std::deque<std::pair<std::uint64_t, std::chrono::steady_clock::time_point>> graphTimes_;
    int pendingPreviewW_ = 0, pendingPreviewH_ = 0;
    bool previewChanged_ = false;
    std::shared_ptr<const OpList> ops_ = std::make_shared<OpList>();
    std::uint64_t graphVersion_ = 1;
    std::uint64_t imageGeneration_ = 0;

    // Tile requests (guarded by requestMutex_)
    std::mutex requestMutex_;
    std::condition_variable requestReady_;
    std::deque<TileKey> requests_;
    std::unordered_set<TileKey, TileKeyHash> inFlight_;
    std::unordered_set<TileKey, TileKeyHash> failed_;

    // GPU state (render thread)
    GLuint previewSource_ = 0, previewResult_ = 0;
    int previewW_ = 0, previewH_ = 0;
    std::uint64_t previewVersion_ = 0;
    GLuint guidedTexture_ = 0;
    int guidedW_ = 1, guidedH_ = 1;
    // Histogram (render thread)
    std::atomic<bool> histogramDirty_{false};
    GLsync histogramFence_ = nullptr;
    std::chrono::steady_clock::time_point lastHistogram_{};
    std::uint64_t measuredVersion_ = 1;  // version 1 = the empty graph of a new engine
    std::uint64_t gpuGeneration_ = 0;
    std::unordered_map<TileKey, GpuTile, TileKeyHash> gpuTiles_;
    std::list<TileKey> gpuLru_;
    std::size_t gpuBytes_ = 0;

    // Stats (atomics so stats() never waits for a frame)
    std::atomic<std::size_t> pending_{0};
    std::atomic<std::uint64_t> frames_{0};
    std::atomic<std::uint64_t> decoded_{0};
    std::atomic<std::size_t> gpuBytesSnapshot_{0};
    std::atomic<std::size_t> gpuTilesSnapshot_{0};
    std::atomic<int> level_{-1};
    std::atomic<bool> halfFloatSnapshot_{true};
    std::atomic<bool> displayP3Snapshot_{false};
    std::atomic<bool> loading_{false};
    std::atomic<bool> histogramEnabled_{false};
    std::atomic<bool> computeSnapshot_{false};
    std::function<void(bool)> loadingListener_;

    // Frame timing ring (render thread writes, stats() reads under timingMutex_)
    static constexpr std::size_t kTimingCapacity = 240;
    std::mutex timingMutex_;
    std::vector<std::uint32_t> intervalsUs_;
    std::vector<std::uint32_t> drawsUs_;
    std::vector<std::uint32_t> updatesUs_;
    std::size_t updateNext_ = 0;
    std::uint64_t updateCount_ = 0;
    std::uint32_t lastUpdateUs_ = 0;
    std::size_t timingNext_ = 0;
    std::chrono::steady_clock::time_point lastFrameStart_{};
};

}  // namespace lo::android
