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
};

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
    // The preview proxy (long edge <= 2048), already converted to working space.
    void setPreview(std::vector<float> working, int width, int height);
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

    EngineStats stats();
    void setLoadingListener(std::function<void(bool)> listener);
    void setThreadExitHook(std::function<void()> hook);

    // Test hook: runs `graph` on the GPU over `in` (w x h working RGBA) and renders the
    // sRGB display transform into `out` (RGBA8). Same shaders as the canvas.
    bool gpuSelfTest(const float* in, int width, int height, const std::string& graph, std::uint8_t* out,
                     std::string* error);

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
    bool ensureResult(GLuint source, GLuint* result, int width, int height, std::uint64_t* version);
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
    std::function<void(bool)> loadingListener_;
};

}  // namespace lo::android
