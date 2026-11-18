#include "engine.h"

#include <EGL/eglext.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <future>

#include "gl_util.h"
#include "lineover/graph.h"

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#endif
#ifndef EGL_GL_COLORSPACE_KHR
#define EGL_GL_COLORSPACE_KHR 0x309D
#endif
#ifndef EGL_GL_COLORSPACE_DISPLAY_P3_EXT
#define EGL_GL_COLORSPACE_DISPLAY_P3_EXT 0x3363
#endif
#ifndef EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT
#define EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT 0x3490
#endif

#define LOG_TAG "LineOverEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace lo::android {
namespace {

// Per frame, so a burst of new tiles never blocks a frame for long (rule R6 spirit:
// the canvas keeps moving while tiles stream in).
constexpr int kMaxUploadsPerFrame = 3;
constexpr int kMaxOpPassesPerFrame = 6;
// The histogram follows the edit at most 30 times a second.
constexpr auto kHistogramInterval = std::chrono::milliseconds(33);
// While a histogram readback is in flight the loop polls its fence this often.
constexpr auto kFencePoll = std::chrono::milliseconds(2);
constexpr std::size_t kUpdateCapacity = 120;
constexpr std::size_t kGraphTimes = 64;

std::uint32_t p95Of(std::vector<std::uint32_t> v) {
    v.erase(std::remove(v.begin(), v.end(), 0u), v.end());
    if (v.empty()) return 0;
    std::sort(v.begin(), v.end());
    return v[(v.size() - 1) * 95 / 100];
}

}  // namespace

Engine::Engine(EngineConfig config) : config_(std::move(config)), store_(config_.cpuBudget, config_.cacheDir) {}

Engine::~Engine() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        quit_ = true;
    }
    wake_.notify_all();
    requestReady_.notify_all();
    if (thread_.joinable()) thread_.join();
}

bool Engine::start(std::string* error) {
    std::promise<std::string> started;
    std::future<std::string> result = started.get_future();
    thread_ = std::thread([this, &started] {
        std::string failure;
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) failure = "eglInitialize failed";
        if (failure.empty()) {
            const EGLint attribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                                      EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
            EGLint count = 0;
            if (!eglChooseConfig(display_, attribs, &eglConfig_, 1, &count) || count == 0) failure = "no GLES 3 EGL config";
        }
        if (failure.empty()) {
            const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
            context_ = eglCreateContext(display_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);
            const EGLint pbufferAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            pbuffer_ = eglCreatePbufferSurface(display_, eglConfig_, pbufferAttribs);
            if (context_ == EGL_NO_CONTEXT || pbuffer_ == EGL_NO_SURFACE || !eglMakeCurrent(display_, pbuffer_, pbuffer_, context_)) {
                failure = "cannot create the GLES 3 context";
            }
        }
        if (failure.empty() && !gpu_.init(&failure)) failure = "GPU pipeline: " + failure;
        if (failure.empty()) {
            halfFloatSnapshot_ = gpu_.halfFloat();
            computeSnapshot_ = gpu_.computeSupported();
            if (!gpu_.computeSupported()) {
                LOGW("no GLES 3.1 compute: histogram %s", gpu_.halfFloat() ? "by float readback + CPU" : "off");
            }
            guidedTexture_ = createGuidedTexture(nullptr);
            if (!gpu_.halfFloat()) LOGW("RGBA16F is not colour-renderable: using the SRGB8_ALPHA8 fallback");
            LOGI("render thread up: %s", reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
        }
        started.set_value(failure);
        if (failure.empty()) loop();
        // Shut down GL on the thread that owns it.
        if (display_ != EGL_NO_DISPLAY) {
            if (context_ != EGL_NO_CONTEXT && pbuffer_ != EGL_NO_SURFACE) {
                eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
                dropGpuTiles();
                if (previewSource_ != 0) glDeleteTextures(1, &previewSource_);
                if (previewResult_ != 0) glDeleteTextures(1, &previewResult_);
                if (guidedTexture_ != 0) glDeleteTextures(1, &guidedTexture_);
                if (histogramFence_ != nullptr) glDeleteSync(histogramFence_);
                gpu_.release();
            }
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (window_ != EGL_NO_SURFACE) eglDestroySurface(display_, window_);
            if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
            if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
            eglReleaseThread();
        }
        if (threadExitHook_) threadExitHook_();
    });
    *error = result.get();
    return error->empty();
}

void Engine::loop() {
    while (true) {
        std::deque<std::function<void()>> tasks;
        bool redraw = false;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            auto ready = [this] { return quit_ || redraw_ || !tasks_.empty(); };
            if (histogramFence_ != nullptr) {
                wake_.wait_for(lock, kFencePoll, ready);
            } else if (histogramDirty_ && histogramEnabled_ && previewSource_ != 0 && histogramAvailable()) {
                wake_.wait_until(lock, lastHistogram_ + kHistogramInterval, ready);
            } else {
                wake_.wait(lock, ready);
            }
            if (quit_) return;
            tasks.swap(tasks_);
            redraw = redraw_;
            redraw_ = false;
        }
        for (auto& task : tasks) task();
        if (redraw) frame();
        serviceHistogram();
    }
}

void Engine::post(std::function<void()> task) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        tasks_.push_back(std::move(task));
    }
    wake_.notify_all();
}

bool Engine::runSync(std::function<bool()> task) {
    auto done = std::make_shared<std::promise<bool>>();
    std::future<bool> result = done->get_future();
    post([task = std::move(task), done] { done->set_value(task()); });
    return result.get();
}

void Engine::requestRedraw() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        redraw_ = true;
    }
    wake_.notify_all();
}

void Engine::openImage(int width, int height) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        imageWidth_ = width;
        imageHeight_ = height;
        ++imageGeneration_;
        pendingPreview_.clear();
        previewChanged_ = false;
    }
    {
        std::lock_guard<std::mutex> lock(requestMutex_);
        requests_.clear();
        inFlight_.clear();
        failed_.clear();
    }
    store_.clear();
    requestRedraw();
}

void Engine::setPreview(std::vector<float> working, int width, int height) {
    // Image analysis for the Light tools, here on the caller's (background) thread.
    auto guided = std::make_shared<const GuidedCoefficients>(guidedCoefficients(working.data(), width, height));
    float low = 0.f, high = 0.f;
    const std::size_t pixels = static_cast<std::size_t>(width) * height;
    // About a million samples are plenty for 0.5 % / 99.5 %.
    luminancePercentiles(working.data(), pixels, 0.005f, 0.995f, &low, &high, std::max<std::size_t>(1, pixels / 1000000));
    float channels[6];
    grade::channelPercentiles(working.data(), pixels, 0.001f, 0.999f, channels, std::max<std::size_t>(1, pixels / 1000000));
    {
        std::lock_guard<std::mutex> lock(mutex_);
        guided_ = std::move(guided);
        guidedChanged_ = true;
        havePercentiles_ = true;
        lowPercentile_ = low;
        highPercentile_ = high;
        std::copy(channels, channels + 6, channelPercentiles_);
        pendingPreview_ = std::move(working);
        pendingPreviewW_ = width;
        pendingPreviewH_ = height;
        previewChanged_ = true;
    }
    requestRedraw();
}

void Engine::submitTile(const TileKey& key, std::shared_ptr<const Tile> tile) {
    if (tile) {
        store_.put(key, std::move(tile));
        ++decoded_;
    }
    {
        std::lock_guard<std::mutex> lock(requestMutex_);
        inFlight_.erase(key);
    }
    requestRedraw();
}

void Engine::failTile(const TileKey& key) {
    {
        std::lock_guard<std::mutex> lock(requestMutex_);
        inFlight_.erase(key);
        failed_.insert(key);
    }
    requestRedraw();
}

bool Engine::takeRequest(int timeoutMs, TileKey* out) {
    std::unique_lock<std::mutex> lock(requestMutex_);
    requestReady_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this] { return !requests_.empty() || quit_; });
    if (requests_.empty() || quit_) return false;
    *out = requests_.front();
    requests_.pop_front();
    inFlight_.insert(*out);
    return true;
}

bool Engine::autoLight(float* exposure, float* blacks) {
    float low, high;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!havePercentiles_) return false;
        low = lowPercentile_;
        high = highPercentile_;
    }
    const AutoLight result = lo::autoLight(low, high);
    *exposure = result.exposure;
    *blacks = result.blacks;
    return true;
}

bool Engine::samplePatch(float u, float v, OpType before, float rgb[3]) {
    constexpr int kPatch = 5;
    float pixels[kPatch * kPatch * 4];
    int count = 0;
    const bool ok = runSync([&] {
        if (previewSource_ == 0 || previewW_ <= 0 || previewH_ <= 0 || !gpu_.halfFloat()) return false;
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        const int cx = std::clamp(static_cast<int>(u * previewW_), 0, previewW_ - 1);
        const int cy = std::clamp(static_cast<int>(v * previewH_), 0, previewH_ - 1);
        const int x0 = std::clamp(cx - kPatch / 2, 0, std::max(0, previewW_ - kPatch));
        const int y0 = std::clamp(cy - kPatch / 2, 0, std::max(0, previewH_ - kPatch));
        const int w = std::min(kPatch, previewW_), h = std::min(kPatch, previewH_);
        const bool read = gpu_.readRegion(previewSource_, x0, y0, w, h, pixels);
        if (window_ != EGL_NO_SURFACE) eglMakeCurrent(display_, window_, window_, context_);
        count = w * h;
        return read;
    });
    if (!ok || count == 0) return false;
    float mean[4] = {0.f, 0.f, 0.f, 1.f};
    for (int i = 0; i < count; ++i)
        for (int c = 0; c < 3; ++c) mean[c] += pixels[4 * i + c] / static_cast<float>(count);
    std::shared_ptr<const OpList> ops;
    std::shared_ptr<const GuidedCoefficients> guided;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ops = ops_;
        guided = guided_;
    }
    OpRegion region;
    region.width = region.height = 1;
    region.rect[0] = u;
    region.rect[1] = v;
    region.rect[2] = region.rect[3] = 1e-6f;
    region.coefficients = guided.get();
    for (const auto& op : ops->ops()) {
        if (op->type() == before) break;
        op->cpuRefRegion(mean, mean, region);
    }
    std::copy(mean, mean + 3, rgb);
    return true;
}

bool Engine::sampleGrid(float u, float v, int n, float* rgba) {
    if (n <= 0 || n > 15) return false;
    return runSync([&] {
        if (previewSource_ == 0 || previewW_ < n || previewH_ < n || !gpu_.halfFloat()) return false;
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        const int cx = std::clamp(static_cast<int>(u * previewW_), 0, previewW_ - 1);
        const int cy = std::clamp(static_cast<int>(v * previewH_), 0, previewH_ - 1);
        const int x0 = std::clamp(cx - n / 2, 0, previewW_ - n);
        const int y0 = std::clamp(cy - n / 2, 0, previewH_ - n);
        const bool read = gpu_.readRegion(previewSource_, x0, y0, n, n, rgba);
        if (window_ != EGL_NO_SURFACE) eglMakeCurrent(display_, window_, window_, context_);
        return read;
    });
}

bool Engine::autoLevels(float out[6]) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!havePercentiles_) return false;
    std::copy(channelPercentiles_, channelPercentiles_ + 6, out);
    return true;
}

void Engine::setShowOriginal(bool original) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        showOriginal_ = original;
    }
    requestRedraw();
}

void Engine::setCompare(CompareMode mode, float split) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        compareMode_ = mode;
        split_ = std::clamp(split, 0.f, 1.f);
    }
    requestRedraw();
}

void Engine::setClipping(bool clipping) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        clipping_ = clipping;
    }
    requestRedraw();
}

void Engine::setHistogramListener(std::function<void(const std::uint32_t*)> listener) {
    std::lock_guard<std::mutex> lock(mutex_);
    histogramListener_ = std::move(listener);
}

void Engine::setHistogramEnabled(bool enabled) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        histogramEnabled_ = enabled;
        // Turning it on shows the current preview's histogram straight away.
        if (enabled) histogramDirty_ = true;
    }
    wake_.notify_all();
}

bool Engine::setGraph(const std::string& json, std::string* error) {
    Graph graph;
    if (!parseGraph(json, &graph, error)) return false;
    std::string skipped;
    std::shared_ptr<const OpList> ops = buildOps(graph, &skipped);
    if (!skipped.empty()) LOGW("unknown ops skipped: %s", skipped.c_str());
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ops_ = std::move(ops);
        ++graphVersion_;
        graphTimes_.emplace_back(graphVersion_, std::chrono::steady_clock::now());
        if (graphTimes_.size() > kGraphTimes) graphTimes_.pop_front();
    }
    requestRedraw();
    return true;
}

bool Engine::attachWindow(ANativeWindow* window, std::string* error) {
    std::string failure;
    const bool ok = runSync([this, window, &failure] {
        if (window_ != EGL_NO_SURFACE) {
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
            eglDestroySurface(display_, window_);
            window_ = EGL_NO_SURFACE;
        }
        const char* extensions = eglQueryString(display_, EGL_EXTENSIONS);
        output_ = color::Display::Srgb;
        shaderEncodes_ = true;
        if (hasExtension(extensions, "EGL_EXT_gl_colorspace_display_p3_passthrough")) {
            // Values are written as they are: the shader encodes P3.
            const EGLint attribs[] = {EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT, EGL_NONE};
            window_ = eglCreateWindowSurface(display_, eglConfig_, window, attribs);
            if (window_ != EGL_NO_SURFACE) output_ = color::Display::DisplayP3;
        }
        if (window_ == EGL_NO_SURFACE && hasExtension(extensions, "EGL_EXT_gl_colorspace_display_p3")) {
            // GL applies the transfer function on write: the shader outputs linear P3.
            const EGLint attribs[] = {EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_DISPLAY_P3_EXT, EGL_NONE};
            window_ = eglCreateWindowSurface(display_, eglConfig_, window, attribs);
            if (window_ != EGL_NO_SURFACE) {
                output_ = color::Display::DisplayP3;
                shaderEncodes_ = false;
            }
        }
        if (window_ == EGL_NO_SURFACE) {
            output_ = color::Display::Srgb;
            shaderEncodes_ = true;
            window_ = eglCreateWindowSurface(display_, eglConfig_, window, nullptr);
        }
        if (window_ == EGL_NO_SURFACE || !eglMakeCurrent(display_, window_, window_, context_)) {
            failure = "cannot create the canvas surface";
            if (window_ != EGL_NO_SURFACE) eglDestroySurface(display_, window_);
            window_ = EGL_NO_SURFACE;
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
            return false;
        }
        displayP3Snapshot_ = output_ == color::Display::DisplayP3;
        LOGI("canvas surface: %s", output_ == color::Display::DisplayP3 ? "Display P3" : "sRGB");
        return true;
    });
    if (!ok) *error = failure;
    requestRedraw();
    return ok;
}

void Engine::detachWindow() {
    runSync([this] {
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        if (window_ != EGL_NO_SURFACE) eglDestroySurface(display_, window_);
        window_ = EGL_NO_SURFACE;
        return true;
    });
}

void Engine::setViewport(int width, int height) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        viewWidth_ = width;
        viewHeight_ = height;
    }
    requestRedraw();
}

void Engine::setTransform(const Affine& imageToView) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        transform_ = imageToView;
    }
    requestRedraw();
}

void Engine::setSurround(float r, float g, float b) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        surround_[0] = r;
        surround_[1] = g;
        surround_[2] = b;
    }
    requestRedraw();
}

EngineStats Engine::stats() {
    const TileStoreStats cpu = store_.stats();
    EngineStats s;
    s.cpuTileBytes = cpu.bytesInMemory;
    s.cpuTiles = cpu.tilesInMemory;
    s.spilledTiles = cpu.tilesSpilled;
    s.spillBytes = cpu.spillBytesOnDisk;
    s.gpuBytes = gpuBytesSnapshot_;
    s.gpuTiles = gpuTilesSnapshot_;
    s.pendingTiles = pending_;
    s.frames = frames_;
    s.tilesDecoded = decoded_;
    s.halfFloat = halfFloatSnapshot_;
    s.displayP3 = displayP3Snapshot_;
    s.level = level_;
    s.histogram = computeSnapshot_;
    {
        std::lock_guard<std::mutex> lock(timingMutex_);
        s.p95FrameIntervalUs = p95Of(intervalsUs_);
        s.p95DrawUs = p95Of(drawsUs_);
        s.p95UpdateUs = p95Of(updatesUs_);
        s.lastUpdateUs = lastUpdateUs_;
        s.updates = updateCount_;
    }
    return s;
}

void Engine::setLoadingListener(std::function<void(bool)> listener) {
    std::lock_guard<std::mutex> lock(mutex_);
    loadingListener_ = std::move(listener);
}

void Engine::setThreadExitHook(std::function<void()> hook) { threadExitHook_ = std::move(hook); }

std::size_t Engine::textureBytes(int w, int h) const {
    return static_cast<std::size_t>(w) * h * (gpu_.halfFloat() ? 8 : 4);
}

void Engine::imageToNdc(float out[9]) const {
    const float w = static_cast<float>(std::max(1, viewWidth_));
    const float h = static_cast<float>(std::max(1, viewHeight_));
    const Affine& t = transform_;
    out[0] = 2.f * t.a / w;
    out[1] = 2.f * t.c / w;
    out[2] = 2.f * t.e / w - 1.f;
    out[3] = -2.f * t.b / h;
    out[4] = -2.f * t.d / h;
    out[5] = 1.f - 2.f * t.f / h;
    out[6] = 0.f;
    out[7] = 0.f;
    out[8] = 1.f;
}

void Engine::tileRectNormalised(const TileKey& key, float rect[4]) const {
    const LevelInfo info = levelInfo(imageWidth_, imageHeight_, key.level);
    const Rect r = tileRect(info, key.tx, key.ty);
    rect[0] = static_cast<float>(r.x) / info.width;
    rect[1] = static_cast<float>(r.y) / info.height;
    rect[2] = static_cast<float>(r.w) / info.width;
    rect[3] = static_cast<float>(r.h) / info.height;
}

bool Engine::ensureResult(GLuint source, GLuint* result, int width, int height, std::uint64_t* version, const float rect[4]) {
    std::shared_ptr<const OpList> ops;
    std::uint64_t graphVersion;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ops = ops_;
        graphVersion = graphVersion_;
    }
    if (*version == graphVersion) return true;
    if (ops->isIdentity()) {
        if (*result != 0) glDeleteTextures(1, result);
        *result = 0;
        *version = graphVersion;
        return true;
    }
    if (*result == 0) *result = createTexture(width, height, gpu_.halfFloat(), nullptr);
    std::string error;
    OpPassInputs inputs;
    std::copy(rect, rect + 4, inputs.rect);
    inputs.guided = guidedTexture_;
    inputs.guidedWidth = guidedW_;
    inputs.guidedHeight = guidedH_;
    if (!gpu_.runOps(*ops, source, *result, width, height, inputs, &error)) {
        LOGW("op pass failed: %s", error.c_str());
        return false;
    }
    *version = graphVersion;
    return true;
}

void Engine::dropGpuTiles() {
    for (auto& [key, tile] : gpuTiles_) {
        if (tile.source != 0) glDeleteTextures(1, &tile.source);
        if (tile.result != 0) glDeleteTextures(1, &tile.result);
    }
    gpuTiles_.clear();
    gpuLru_.clear();
    gpuBytes_ = 0;
}

void Engine::evictGpu(const std::unordered_set<TileKey, TileKeyHash>& keep) {
    auto it = gpuLru_.end();
    while (gpuBytes_ > config_.gpuBudget && it != gpuLru_.begin()) {
        --it;
        if (keep.count(*it) != 0) continue;
        GpuTile& tile = gpuTiles_[*it];
        gpuBytes_ -= textureBytes(tile.width, tile.height) * (tile.result != 0 ? 2 : 1);
        if (tile.source != 0) glDeleteTextures(1, &tile.source);
        if (tile.result != 0) glDeleteTextures(1, &tile.result);
        gpuTiles_.erase(*it);
        it = gpuLru_.erase(it);
    }
}

void Engine::updateRequests(const std::vector<TileKey>& missing) {
    {
        std::lock_guard<std::mutex> lock(requestMutex_);
        requests_.clear();  // stale requests (tiles no longer in view) are dropped
        for (const TileKey& key : missing) {
            if (inFlight_.count(key) == 0 && failed_.count(key) == 0) requests_.push_back(key);
        }
    }
    requestReady_.notify_all();
}

void Engine::frame() {
    const auto frameStart = std::chrono::steady_clock::now();
    int imageW, imageH, viewW, viewH;
    Affine transform;
    float surround[3];
    std::uint64_t generation;
    std::vector<float> preview;
    int previewW = 0, previewH = 0;
    bool previewChanged;
    std::shared_ptr<const GuidedCoefficients> guided;
    bool showOriginal, clipping;
    CompareMode compare;
    float split;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        imageW = imageWidth_;
        imageH = imageHeight_;
        viewW = viewWidth_;
        viewH = viewHeight_;
        transform = transform_;
        std::copy(surround_, surround_ + 3, surround);
        generation = imageGeneration_;
        previewChanged = previewChanged_;
        if (previewChanged) {
            preview.swap(pendingPreview_);
            previewW = pendingPreviewW_;
            previewH = pendingPreviewH_;
            previewChanged_ = false;
        }
        if (guidedChanged_) {
            guided = guided_;
            guidedChanged_ = false;
        }
        showOriginal = showOriginal_;
        clipping = clipping_;
        compare = compareMode_;
        split = split_;
    }
    if (guided) {
        // New coefficients: every result depending on them is recomputed.
        if (guidedTexture_ != 0) glDeleteTextures(1, &guidedTexture_);
        guidedTexture_ = createGuidedTexture(guided.get());
        guidedW_ = guided->width > 0 ? guided->width : 1;
        guidedH_ = guided->height > 0 ? guided->height : 1;
        previewVersion_ = 0;
        for (auto& [key, tile] : gpuTiles_) tile.graphVersion = 0;
    }
    if (gpuGeneration_ != generation) {
        dropGpuTiles();
        if (previewSource_ != 0) glDeleteTextures(1, &previewSource_);
        if (previewResult_ != 0) glDeleteTextures(1, &previewResult_);
        previewSource_ = previewResult_ = 0;
        previewW_ = previewH_ = 0;
        gpuGeneration_ = generation;
    }
    if (previewChanged && !preview.empty()) {
        if (previewSource_ != 0) glDeleteTextures(1, &previewSource_);
        if (previewResult_ != 0) glDeleteTextures(1, &previewResult_);
        previewResult_ = 0;
        previewSource_ = createTexture(previewW, previewH, gpu_.halfFloat(), preview.data());
        previewW_ = previewW;
        previewH_ = previewH;
        previewVersion_ = 0;
    }
    bool more = false;
    if (previewSource_ != 0) {
        static constexpr float kWhole[4] = {0.f, 0.f, 1.f, 1.f};
        const std::uint64_t before = previewVersion_;
        if (!ensureResult(previewSource_, &previewResult_, previewW_, previewH_, &previewVersion_, kWhole)) more = true;
        if (previewVersion_ != before) histogramDirty_ = true;
    }

    // Sharp tiles: only when the preview is magnified on screen.
    std::vector<TileKey> visible;
    int level = -1;
    if (imageW > 0 && imageH > 0 && viewW > 0 && viewH > 0) {
        const float previewScale = previewW_ > 0 ? static_cast<float>(previewW_) / imageW : 0.f;
        const float scale = transform.scale();
        if (scale > previewScale * 1.01f) {
            level = levelForScale(scale, levelCount(imageW, imageH));
            const float levelScale = 1.0f / static_cast<float>(1 << level);
            if (levelScale > previewScale * 1.01f) {
                visible = visibleTiles(transform, viewW, viewH, imageW, imageH, level);
            } else {
                level = -1;
            }
        }
    }
    level_ = level;

    std::unordered_set<TileKey, TileKeyHash> keep;
    std::vector<TileKey> drawable;
    std::vector<TileKey> missing;
    int uploads = 0;
    int passes = 0;
    for (const TileKey& key : visible) {
        keep.insert(key);
        auto it = gpuTiles_.find(key);
        if (it == gpuTiles_.end()) {
            if (!store_.contains(key)) {
                missing.push_back(key);
                continue;
            }
            if (uploads >= kMaxUploadsPerFrame) {
                more = true;
                continue;
            }
            const std::shared_ptr<const Tile> tile = store_.get(key);
            if (!tile) {
                missing.push_back(key);
                continue;
            }
            GpuTile gpuTile;
            gpuTile.width = tile->width;
            gpuTile.height = tile->height;
            gpuTile.source = createTexture(tile->width, tile->height, gpu_.halfFloat(), tile->pixels.data());
            gpuLru_.push_front(key);
            gpuTile.lru = gpuLru_.begin();
            gpuBytes_ += textureBytes(tile->width, tile->height);
            it = gpuTiles_.emplace(key, gpuTile).first;
            ++uploads;
        } else {
            gpuLru_.erase(it->second.lru);
            gpuLru_.push_front(key);
            it->second.lru = gpuLru_.begin();
        }
        GpuTile& tile = it->second;
        const bool hadResult = tile.result != 0;
        if (passes < kMaxOpPassesPerFrame) {
            const std::uint64_t before = tile.graphVersion;
            float rect[4];
            {
                const LevelInfo info = levelInfo(imageW, imageH, key.level);
                const Rect r = tileRect(info, key.tx, key.ty);
                rect[0] = static_cast<float>(r.x) / info.width;
                rect[1] = static_cast<float>(r.y) / info.height;
                rect[2] = static_cast<float>(r.w) / info.width;
                rect[3] = static_cast<float>(r.h) / info.height;
            }
            ensureResult(tile.source, &tile.result, tile.width, tile.height, &tile.graphVersion, rect);
            if (tile.graphVersion != before) ++passes;
            if (!hadResult && tile.result != 0) gpuBytes_ += textureBytes(tile.width, tile.height);
            if (hadResult && tile.result == 0) gpuBytes_ -= textureBytes(tile.width, tile.height);
        } else {
            more = true;
        }
        drawable.push_back(key);
    }
    updateRequests(missing);
    evictGpu(keep);
    gpuBytesSnapshot_ = gpuBytes_;
    gpuTilesSnapshot_ = gpuTiles_.size();

    std::size_t failedVisible = 0;
    {
        std::lock_guard<std::mutex> lock(requestMutex_);
        for (const TileKey& key : missing) failedVisible += failed_.count(key);
    }
    const std::size_t pending = visible.size() - drawable.size() - failedVisible;
    pending_ = pending;
    const bool loading = pending > 0;
    if (loading != loading_.exchange(loading)) {
        std::function<void(bool)> listener;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            listener = loadingListener_;
        }
        if (listener) listener(loading);
    }

    if (window_ != EGL_NO_SURFACE) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, viewW, viewH);
        glClearColor(surround[0], surround[1], surround[2], 1.f);
        if (!shaderEncodes_) {
            // The surface encodes on write: clear with the linear value of the surround.
            glClearColor(color::srgbEotf(surround[0]), color::srgbEotf(surround[1]), color::srgbEotf(surround[2]), 1.f);
        }
        glClear(GL_COLOR_BUFFER_BIT);
        float ndc[9];
        {
            std::lock_guard<std::mutex> lock(mutex_);
            imageToNdc(ndc);
        }
        if (showOriginal || compare == CompareMode::Off) {
            drawScene(showOriginal, ndc, imageW, imageH, level, drawable, clipping && !showOriginal);
        } else if (compare == CompareMode::Split) {
            drawScene(false, ndc, imageW, imageH, level, drawable, clipping);
            const int splitPx = static_cast<int>(split * static_cast<float>(viewW) + 0.5f);
            if (splitPx > 0) {
                glEnable(GL_SCISSOR_TEST);
                glScissor(0, 0, splitPx, viewH);
                glClear(GL_COLOR_BUFFER_BIT);
                drawScene(true, ndc, imageW, imageH, level, drawable, false);
                glDisable(GL_SCISSOR_TEST);
            }
        } else {
            // Side by side: each half shows the view as if it were half as wide, so the
            // image centre of the full view sits in the middle of each half.
            const int half = viewW / 2;
            float halfNdc[9];
            {
                std::lock_guard<std::mutex> lock(mutex_);
                const int savedW = viewWidth_;
                const Affine saved = transform_;
                viewWidth_ = std::max(1, half);
                transform_.e -= static_cast<float>(viewW) / 4.f;
                imageToNdc(halfNdc);
                viewWidth_ = savedW;
                transform_ = saved;
            }
            glEnable(GL_SCISSOR_TEST);
            glViewport(0, 0, half, viewH);
            glScissor(0, 0, half, viewH);
            drawScene(true, halfNdc, imageW, imageH, level, drawable, false);
            glViewport(half, 0, viewW - half, viewH);
            glScissor(half, 0, viewW - half, viewH);
            drawScene(false, halfNdc, imageW, imageH, level, drawable, clipping);
            glDisable(GL_SCISSOR_TEST);
            glViewport(0, 0, viewW, viewH);
        }
        eglSwapBuffers(display_, window_);
        const auto now = std::chrono::steady_clock::now();
        std::lock_guard<std::mutex> lock(timingMutex_);
        auto us = [](auto d) { return static_cast<std::uint32_t>(std::chrono::duration_cast<std::chrono::microseconds>(d).count()); };
        const std::uint32_t interval = lastFrameStart_.time_since_epoch().count() == 0 ? 0 : us(frameStart - lastFrameStart_);
        if (intervalsUs_.size() < kTimingCapacity) {
            intervalsUs_.push_back(interval);
            drawsUs_.push_back(us(now - frameStart));
        } else {
            intervalsUs_[timingNext_] = interval;
            drawsUs_[timingNext_] = us(now - frameStart);
        }
        timingNext_ = (timingNext_ + 1) % kTimingCapacity;
        lastFrameStart_ = frameStart;
        // Preview update latency: the first frame presenting each graph version's preview,
        // from when that version arrived. While a slider moves faster than the renderer,
        // skipped versions are not counted; the ones shown carry their full delay.
        std::chrono::steady_clock::time_point setAt{};
        bool found = false;
        if (previewSource_ != 0 && previewVersion_ > measuredVersion_) {
            measuredVersion_ = previewVersion_;
            std::lock_guard<std::mutex> graphLock(mutex_);
            while (!graphTimes_.empty() && graphTimes_.front().first <= previewVersion_) {
                if (graphTimes_.front().first == previewVersion_) {
                    setAt = graphTimes_.front().second;
                    found = true;
                }
                graphTimes_.pop_front();
            }
        }
        if (found) {
            const std::uint32_t latency = us(now - setAt);
            if (updatesUs_.size() < kUpdateCapacity) {
                updatesUs_.push_back(latency);
            } else {
                updatesUs_[updateNext_] = latency;
            }
            updateNext_ = (updateNext_ + 1) % kUpdateCapacity;
            lastUpdateUs_ = latency;
            ++updateCount_;
            if (updateCount_ % 30 == 0) {
                LOGI("preview update: last %u us, p95 %u us over %zu (target 16000)", latency, p95Of(updatesUs_), updatesUs_.size());
            }
        }
    }
    ++frames_;
    if (more) requestRedraw();
}

void Engine::drawScene(bool original, const float ndc[9], int imageW, int imageH, int level,
                       const std::vector<TileKey>& drawable, bool clipping) {
    DisplayOptions options;
    options.clipping = clipping;
    if (previewSource_ != 0 && imageW > 0) {
        const float rect[4] = {0.f, 0.f, static_cast<float>(imageW), static_cast<float>(imageH)};
        const GLuint texture = !original && previewResult_ != 0 ? previewResult_ : previewSource_;
        gpu_.drawImage(texture, rect, ndc, output_, shaderEncodes_, options);
    }
    if (level < 0) return;
    const LevelInfo info = levelInfo(imageW, imageH, level);
    const float span = static_cast<float>(1 << level);
    for (const TileKey& key : drawable) {
        const GpuTile& tile = gpuTiles_[key];
        const Rect r = tileRect(info, key.tx, key.ty);
        // The tile's rect in original pixels (edge tiles are clipped to the image).
        const float rect[4] = {r.x * span, r.y * span, std::min(r.w * span, imageW - r.x * span),
                               std::min(r.h * span, imageH - r.y * span)};
        const GLuint texture = !original && tile.result != 0 ? tile.result : tile.source;
        gpu_.drawImage(texture, rect, ndc, output_, shaderEncodes_, options);
    }
}

bool Engine::histogramAvailable() const { return gpu_.computeSupported() || gpu_.halfFloat(); }

void Engine::serviceHistogram() {
    if (!histogramAvailable()) return;
    if (!histogramEnabled_ && histogramFence_ == nullptr) {
        gpu_.releaseReadback();  // the fallback's pixel buffer is only kept while shown
        return;
    }
    if (histogramFence_ != nullptr) {
        // Read back only once the GPU is done: the render thread never waits for it.
        const GLenum status = glClientWaitSync(histogramFence_, 0, 0);
        if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) return;
        glDeleteSync(histogramFence_);
        histogramFence_ = nullptr;
        std::uint32_t bins[GpuPipeline::kHistogramSize];
        if (gpu_.computeSupported()) {
            if (!gpu_.readHistogram(bins)) return;
        } else {
            // GLES 3.0: the preview's float pixels, binned on the CPU (histogramSrgb).
            std::vector<float> pixels;
            if (!gpu_.finishReadback(&pixels)) return;
            histogramSrgb(pixels.data(), pixels.size() / 4, bins);
        }
        std::function<void(const std::uint32_t*)> listener;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            listener = histogramListener_;
        }
        if (listener) listener(bins);
        return;
    }
    if (!histogramDirty_ || !histogramEnabled_ || previewSource_ == 0) return;
    const auto now = std::chrono::steady_clock::now();
    if (now < lastHistogram_ + kHistogramInterval) return;
    const GLuint texture = previewResult_ != 0 ? previewResult_ : previewSource_;
    const bool started = gpu_.computeSupported() ? gpu_.dispatchHistogram(texture, previewW_, previewH_)
                                                 : gpu_.startReadback(texture, previewW_, previewH_);
    if (!started) return;
    histogramFence_ = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    glFlush();
    histogramDirty_ = false;
    lastHistogram_ = now;
}

bool Engine::gpuHistogramSelfTest(const float* in, int width, int height, std::uint32_t* out, std::string* error) {
    if (!histogramAvailable()) {
        *error = "no histogram: neither GLES 3.1 compute nor float readback";
        return false;
    }
    std::string failure;
    const bool ok = runSync([&] {
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        const GLuint source = createTexture(width, height, gpu_.halfFloat(), in);
        bool passed;
        if (gpu_.computeSupported()) {
            passed = gpu_.dispatchHistogram(source, width, height);
            glFinish();
            passed = passed && gpu_.readHistogram(out);
        } else {
            std::vector<float> pixels(static_cast<std::size_t>(width) * height * 4);
            passed = gpu_.readFloat(source, width, height, pixels.data());
            if (passed) histogramSrgb(pixels.data(), pixels.size() / 4, out);
        }
        const std::string glError = glErrorString();
        if (!glError.empty()) {
            failure = "GL error " + glError;
            passed = false;
        }
        glDeleteTextures(1, &source);
        if (window_ != EGL_NO_SURFACE) eglMakeCurrent(display_, window_, window_, context_);
        return passed;
    });
    if (!ok) *error = failure.empty() ? "histogram pass failed" : failure;
    return ok;
}

bool Engine::renderTileCpu(const TileKey& key, std::vector<float>* out, int* width, int* height) {
    const std::shared_ptr<const Tile> tile = store_.get(key);
    if (!tile) return false;
    std::shared_ptr<const OpList> ops;
    std::shared_ptr<const GuidedCoefficients> guided;
    OpRegion region;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ops = ops_;
        guided = guided_;
        tileRectNormalised(key, region.rect);
    }
    region.width = tile->width;
    region.height = tile->height;
    region.coefficients = guided.get();
    out->resize(tile->pixels.size());
    ops->cpuRefRegion(tile->pixels.data(), out->data(), region);
    *width = tile->width;
    *height = tile->height;
    return true;
}

bool Engine::gpuSelfTest(const float* in, int width, int height, const std::string& graph, std::uint8_t* out,
                         std::string* error) {
    Graph parsed;
    if (!parseGraph(graph, &parsed, error)) return false;
    std::shared_ptr<const OpList> ops = buildOps(parsed, nullptr);
    std::string failure;
    const bool ok = runSync([&] {
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        const GLuint source = createTexture(width, height, gpu_.halfFloat(), in);
        const GLuint result = createTexture(width, height, gpu_.halfFloat(), nullptr);
        // The test image is its own preview: coefficients from it, as the canvas does.
        const GuidedCoefficients coefficients = guidedCoefficients(in, width, height);
        OpPassInputs inputs;
        inputs.guided = createGuidedTexture(&coefficients);
        inputs.guidedWidth = width;
        inputs.guidedHeight = height;
        bool passed = gpu_.runOps(*ops, source, result, width, height, inputs, &failure);
        glDeleteTextures(1, &inputs.guided);
        if (passed) {
            GLuint target = 0, fbo = 0;
            glGenTextures(1, &target);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            glGenFramebuffers(1, &fbo);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, width, height);
            glClearColor(0.f, 0.f, 0.f, 0.f);
            glClear(GL_COLOR_BUFFER_BIT);
            // Image row 0 -> framebuffer row 0 (the first row glReadPixels returns).
            const float ndc[9] = {2.f / width, 0.f, -1.f, 0.f, 2.f / height, -1.f, 0.f, 0.f, 1.f};
            const float rect[4] = {0.f, 0.f, static_cast<float>(width), static_cast<float>(height)};
            gpu_.drawImage(result, rect, ndc, color::Display::Srgb, true);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, out);
            const std::string glError = glErrorString();
            if (!glError.empty()) {
                failure = "GL error " + glError;
                passed = false;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &fbo);
            glDeleteTextures(1, &target);
        }
        glDeleteTextures(1, &source);
        glDeleteTextures(1, &result);
        if (window_ != EGL_NO_SURFACE) eglMakeCurrent(display_, window_, window_, context_);
        return passed;
    });
    if (!ok) *error = failure;
    return ok;
}

}  // namespace lo::android
