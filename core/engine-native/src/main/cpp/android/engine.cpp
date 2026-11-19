#include "engine.h"

#include <EGL/eglext.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
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
            createFieldTextures(nullptr, &fieldsTexture_, &hazeTexture_);
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
                if (fieldsTexture_ != 0) glDeleteTextures(1, &fieldsTexture_);
                if (hazeTexture_ != 0) glDeleteTextures(1, &hazeTexture_);
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

namespace {
// Long edge of the thumbnail source: sharp enough for the Home grid on a 1440 px phone.
constexpr int kThumbnailEdge = 512;
// Long edge of the analysis copy (segments, horizon, chromatic aberration).
constexpr int kAnalysisEdge = 1024;
// Most source tiles kept in view through a geometry (strong perspective magnifies far parts).
constexpr std::size_t kMaxGeometryTiles = 48;
// Export renders an output region in parts while its source footprint is larger than this.
constexpr long long kMaxFootprintPixels = 2048LL * 2048LL;

// The geometry refers to sourceWidth x sourceHeight; scale its matrix to `width` x `height`.
Geometry fitted(Geometry g, int width, int height) {
    if (g.sourceWidth > 0 && g.sourceHeight > 0 && (g.sourceWidth != width || g.sourceHeight != height)) {
        const double sx = static_cast<double>(width) / g.sourceWidth, sy = static_cast<double>(height) / g.sourceHeight;
        for (int c = 0; c < 3; ++c) {
            g.matrix[c] *= sx;
            g.matrix[3 + c] *= sy;
        }
    }
    g.sourceWidth = width;
    g.sourceHeight = height;
    return g;
}

// The identity geometry of a width x height image: bicubic at pixel centres reads each
// pixel exactly (the export and histogram path for finishing and spatial ops).
// Grows a footprint (x, y, w, h) by `apron` pixels on each side, clipped to the image.
void expandFootprint(int fp[4], int apron, int imageW, int imageH) {
    if (apron <= 0) return;
    const int x0 = std::max(0, fp[0] - apron), y0 = std::max(0, fp[1] - apron);
    const int x1 = std::min(imageW, fp[0] + fp[2] + apron), y1 = std::min(imageH, fp[1] + fp[3] + apron);
    fp[0] = x0;
    fp[1] = y0;
    fp[2] = x1 - x0;
    fp[3] = y1 - y0;
}

Geometry identityGeometry(int width, int height) {
    Geometry g;
    g.matrix[0] = width;
    g.matrix[4] = height;
    g.outputWidth = g.sourceWidth = width;
    g.outputHeight = g.sourceHeight = height;
    return g;
}

GeometryDraw drawOf(const Geometry& g) {
    GeometryDraw d;
    for (int i = 0; i < 9; ++i) d.matrix[i] = static_cast<float>(g.matrix[i]);
    d.outSize[0] = static_cast<float>(g.outputWidth);
    d.outSize[1] = static_cast<float>(g.outputHeight);
    d.srcSize[0] = static_cast<float>(g.sourceWidth);
    d.srcSize[1] = static_cast<float>(g.sourceHeight);
    d.lens[0] = static_cast<float>(g.k1);
    d.lens[1] = static_cast<float>(g.k2);
    d.lens[2] = static_cast<float>(g.caRed);
    d.lens[3] = static_cast<float>(g.caBlue);
    for (int i = 0; i < 4; ++i) d.dist[i] = static_cast<float>(g.dist[i]);
    for (int i = 0; i < 3; ++i) {
        d.tcaRed[i] = static_cast<float>(g.tcaRed[i]);
        d.tcaBlue[i] = static_cast<float>(g.tcaBlue[i]);
    }
    d.drawRect[2] = d.outSize[0];
    d.drawRect[3] = d.outSize[1];
    return d;
}
}  // namespace

void Engine::openImage(int width, int height) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        imageWidth_ = width;
        imageHeight_ = height;
        hasGeometry_ = hasRawGeometry_ && width > 0 && height > 0;
        if (hasGeometry_) geometry_ = fitted(rawGeometry_, width, height);
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
    // Stage 10: Clarity, Texture and Dehaze fields, once per preview (not per edit).
    auto fields = std::make_shared<const DetailFields>(computeDetailFields(working.data(), width, height));
    float low = 0.f, high = 0.f;
    const std::size_t pixels = static_cast<std::size_t>(width) * height;
    // About a million samples are plenty for 0.5 % / 99.5 %.
    luminancePercentiles(working.data(), pixels, 0.005f, 0.995f, &low, &high, std::max<std::size_t>(1, pixels / 1000000));
    float channels[6];
    grade::channelPercentiles(working.data(), pixels, 0.001f, 0.999f, channels, std::max<std::size_t>(1, pixels / 1000000));
    auto thumbSource = std::make_shared<const ImageF32>(boxDownsample(working.data(), width, height, kThumbnailEdge));
    auto analysis = std::make_shared<const ImageF32>(boxDownsample(working.data(), width, height, kAnalysisEdge));
    {
        std::lock_guard<std::mutex> lock(mutex_);
        guided_ = std::move(guided);
        fields_ = std::move(fields);
        thumbSource_ = std::move(thumbSource);
        analysisSource_ = std::move(analysis);
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

bool Engine::renderThumbnail(const std::string& graphJson, std::vector<std::uint8_t>* rgba, int* width, int* height) {
    std::shared_ptr<const ImageF32> source;
    std::shared_ptr<const GuidedCoefficients> guided;
    std::shared_ptr<const DetailFields> fields;
    int imageW;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        source = thumbSource_;
        guided = guided_;
        fields = fields_;
        imageW = imageWidth_;
    }
    if (!source || source->width == 0) return false;
    std::string error;
    return lo::renderThumbnail(*source, guided.get(), graphJson, rgba, &error, width, height, fields.get(), imageW);
}

void Engine::canvasToSource(float* u, float* v) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!hasGeometry_) return;
    double x, y;
    mapToSource(geometry_, geometry_.sourceWidth, geometry_.sourceHeight, *u, *v, 1, &x, &y);
    *u = std::clamp(static_cast<float>(x / geometry_.sourceWidth), 0.f, 1.f);
    *v = std::clamp(static_cast<float>(y / geometry_.sourceHeight), 0.f, 1.f);
}

bool Engine::samplePatch(float u, float v, OpType before, float rgb[3]) {
    canvasToSource(&u, &v);
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
    std::shared_ptr<const DetailFields> fields;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ops = ops_;
        guided = guided_;
        fields = fields_;
    }
    OpRegion region;
    region.fields = fields.get();
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
    canvasToSource(&u, &v);
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
    Geometry geometry;
    const bool hasGeometry = geometryOf(graph, &geometry);
    Finishing finishing;
    finishingOf(graph, &finishing);
    const int apron = ops->apron();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        finishing_ = finishing;
        apron_ = apron;
        hasRawGeometry_ = hasGeometry;
        rawGeometry_ = geometry;
        hasGeometry_ = hasGeometry && imageWidth_ > 0 && imageHeight_ > 0;
        if (hasGeometry_) geometry_ = fitted(geometry, imageWidth_, imageHeight_);
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

int Engine::apronAt(int level) const {
    // Render thread; apron_ changes with the graph (setGraph) under mutex_.
    int apron;
    {
        std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(mutex_));
        apron = apron_;
    }
    if (apron <= 0) return 0;
    return (apron + (1 << level) - 1) >> level;
}

std::vector<float> Engine::paddedTile(const TileKey& key, const Tile& tile, int imageW, int imageH, int apron, GpuTile* layout,
                                      std::vector<TileKey>* missing) {
    const LevelInfo info = levelInfo(imageW, imageH, key.level);
    const Rect r = tileRect(info, key.tx, key.ty);
    const int x0 = std::max(0, r.x - apron), y0 = std::max(0, r.y - apron);
    const int x1 = std::min(info.width, r.x + tile.width + apron), y1 = std::min(info.height, r.y + tile.height + apron);
    const int w = x1 - x0, h = y1 - y0;
    layout->width = w;
    layout->height = h;
    layout->padLeft = r.x - x0;
    layout->padTop = r.y - y0;
    layout->ownWidth = tile.width;
    layout->ownHeight = tile.height;
    if (apron <= 0 || (w == tile.width && h == tile.height)) return tile.pixels;
    std::vector<float> out(static_cast<std::size_t>(w) * h * 4);
    // First the tile's own edge, clamped (what a missing neighbour leaves), then the neighbours.
    for (int y = 0; y < h; ++y) {
        const int sy = std::clamp(y0 + y - r.y, 0, tile.height - 1);
        for (int x = 0; x < w; ++x) {
            const int sx = std::clamp(x0 + x - r.x, 0, tile.width - 1);
            std::copy_n(&tile.pixels[4 * (static_cast<std::size_t>(sy) * tile.width + sx)], 4, &out[4 * (static_cast<std::size_t>(y) * w + x)]);
        }
    }
    for (int ty = y0 / kTileSize; ty <= (y1 - 1) / kTileSize && ty < info.tilesY; ++ty) {
        for (int tx = x0 / kTileSize; tx <= (x1 - 1) / kTileSize && tx < info.tilesX; ++tx) {
            const TileKey n{key.level, tx, ty};
            std::shared_ptr<const Tile> other;
            const Tile* t = &tile;
            if (!(n == key)) {
                other = store_.contains(n) ? store_.get(n) : nullptr;
                if (!other) {
                    missing->push_back(n);
                    continue;
                }
                t = other.get();
            }
            const Rect nr = tileRect(info, tx, ty);
            const int ax = std::max(nr.x, x0), ay = std::max(nr.y, y0);
            const int bx = std::min(nr.x + t->width, x1), by = std::min(nr.y + t->height, y1);
            for (int y = ay; y < by; ++y) {
                std::copy_n(&t->pixels[4 * (static_cast<std::size_t>(y - nr.y) * t->width + (ax - nr.x))], 4 * (bx - ax),
                            &out[4 * (static_cast<std::size_t>(y - y0) * w + (ax - x0))]);
            }
        }
    }
    return out;
}

bool Engine::exportGeometry(Geometry* out, int* imageW, int* imageH) {
    std::lock_guard<std::mutex> lock(mutex_);
    *imageW = imageWidth_;
    *imageH = imageHeight_;
    if (hasGeometry_) {
        *out = geometry_;
        return true;
    }
    if (imageWidth_ <= 0 || imageHeight_ <= 0 || (apron_ == 0 && !finishing_.active())) return false;
    *out = identityGeometry(imageWidth_, imageHeight_);
    return true;
}

bool Engine::ensureResult(GLuint source, GLuint* result, int width, int height, std::uint64_t* version, const float rect[4],
                          float scale) {
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
    inputs.scale = scale;
    inputs.fields = fieldsTexture_;
    inputs.fieldsWidth = fieldsW_;
    inputs.fieldsHeight = fieldsH_;
    inputs.haze = hazeTexture_;
    inputs.hazeWidth = hazeW_;
    inputs.hazeHeight = hazeH_;
    std::copy(airlight_, airlight_ + 3, inputs.air);
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
    std::shared_ptr<const DetailFields> fields;
    bool showOriginal, clipping;
    CompareMode compare;
    float split;
    bool hasGeometry;
    Geometry geometry;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        hasGeometry = hasGeometry_;
        geometry = geometry_;
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
            fields = fields_;
            guidedChanged_ = false;
        }
        frameFinishing_ = finishing_;
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
        if (fieldsTexture_ != 0) glDeleteTextures(1, &fieldsTexture_);
        if (hazeTexture_ != 0) glDeleteTextures(1, &hazeTexture_);
        createFieldTextures(fields.get(), &fieldsTexture_, &hazeTexture_);
        const bool hasFields = fields && !fields->clarity.ab.empty();
        fieldsW_ = hasFields ? fields->clarity.width : 1;
        fieldsH_ = hasFields ? fields->clarity.height : 1;
        hazeW_ = hasFields ? fields->haze.width : 1;
        hazeH_ = hasFields ? fields->haze.height : 1;
        for (int c = 0; c < 3; ++c) airlight_[c] = fields ? fields->airlight[c] : 1.f;
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
        const float previewScale = imageW > 0 ? static_cast<float>(previewW_) / imageW : 1.f;
        if (!ensureResult(previewSource_, &previewResult_, previewW_, previewH_, &previewVersion_, kWhole, previewScale)) more = true;
        if (previewVersion_ != before) histogramDirty_ = true;
    }

    // Sharp tiles: only when the preview is magnified on screen.
    std::vector<TileKey> visible;
    int level = -1;
    if (imageW > 0 && imageH > 0 && viewW > 0 && viewH > 0 && !hasGeometry) {
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
    } else if (imageW > 0 && imageH > 0 && viewW > 0 && viewH > 0) {
        // The canvas shows the output: the visible output rect -> the source pixels it reads
        // (its footprint) -> the source tiles of the level that matches the screen there.
        Affine toCanvas;
        if (transform.invert(&toCanvas)) {
            float minX = 1e30f, minY = 1e30f, maxX = -1e30f, maxY = -1e30f;
            const float corners[4][2] = {{0.f, 0.f}, {static_cast<float>(viewW), 0.f}, {0.f, static_cast<float>(viewH)},
                                         {static_cast<float>(viewW), static_cast<float>(viewH)}};
            for (const auto& c : corners) {
                float x, y;
                toCanvas.apply(c[0], c[1], &x, &y);
                minX = std::min(minX, x);
                minY = std::min(minY, y);
                maxX = std::max(maxX, x);
                maxY = std::max(maxY, y);
            }
            const int x0 = std::max(0, static_cast<int>(std::floor(minX)));
            const int y0 = std::max(0, static_cast<int>(std::floor(minY)));
            const int x1 = std::min(geometry.outputWidth, static_cast<int>(std::ceil(maxX)));
            const int y1 = std::min(geometry.outputHeight, static_cast<int>(std::ceil(maxY)));
            if (x1 > x0 && y1 > y0) {
                const double cu = 0.5 * (x0 + x1) / geometry.outputWidth, cv = 0.5 * (y0 + y1) / geometry.outputHeight;
                // View pixels per source pixel at the middle of the view.
                const float scale = transform.scale() * static_cast<float>(outputScaleAt(geometry, imageW, imageH, cu, cv));
                const float previewScale = previewW_ > 0 ? static_cast<float>(previewW_) / imageW : 0.f;
                if (scale > previewScale * 1.01f) {
                    level = levelForScale(scale, levelCount(imageW, imageH));
                    const float levelScale = 1.0f / static_cast<float>(1 << level);
                    if (levelScale > previewScale * 1.01f) {
                        int fp[4];
                        footprint(geometry, imageW, imageH, x0, y0, x1 - x0, y1 - y0, fp);
                        if (fp[2] > 0 && fp[3] > 0) {
                            Affine select;
                            select.e = static_cast<float>(-fp[0]);
                            select.f = static_cast<float>(-fp[1]);
                            visible = visibleTiles(select, fp[2], fp[3], imageW, imageH, level);
                            if (visible.size() > kMaxGeometryTiles) visible.resize(kMaxGeometryTiles);
                        }
                    } else {
                        level = -1;
                    }
                }
            }
        }
    }
    level_ = level;

    std::unordered_set<TileKey, TileKeyHash> keep;
    std::vector<TileKey> drawable;
    std::vector<TileKey> missing;
    int uploads = 0;
    int passes = 0;
    std::vector<TileKey> neighbours;  // missing neighbours of the visible tiles' aprons
    for (const TileKey& key : visible) {
        keep.insert(key);
        const int apron = apronAt(key.level);
        auto it = gpuTiles_.find(key);
        if (it != gpuTiles_.end()) {
            // An apron of another width, or neighbours that have arrived: rebuild the textures.
            GpuTile& t = it->second;
            bool rebuild = t.apron != apron;
            if (!rebuild && !t.complete) {
                rebuild = std::all_of(t.waiting.begin(), t.waiting.end(), [&](const TileKey& n) { return store_.contains(n); });
                if (!rebuild) {
                    for (const TileKey& n : t.waiting)
                        if (!store_.contains(n)) neighbours.push_back(n);
                }
            }
            if (rebuild && uploads >= kMaxUploadsPerFrame) {
                rebuild = false;
                more = true;
            }
            if (rebuild) {
                gpuBytes_ -= textureBytes(t.width, t.height) * (t.result != 0 ? 2 : 1);
                if (t.source != 0) glDeleteTextures(1, &t.source);
                if (t.result != 0) glDeleteTextures(1, &t.result);
                gpuLru_.erase(t.lru);
                gpuTiles_.erase(it);
                it = gpuTiles_.end();
            }
        }
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
            const std::vector<float> padded = paddedTile(key, *tile, imageW, imageH, apron, &gpuTile, &gpuTile.waiting);
            gpuTile.apron = apron;
            gpuTile.complete = gpuTile.waiting.empty();
            for (const TileKey& n : gpuTile.waiting) neighbours.push_back(n);
            gpuTile.source = createTexture(gpuTile.width, gpuTile.height, gpu_.halfFloat(), padded.data());
            gpuLru_.push_front(key);
            gpuTile.lru = gpuLru_.begin();
            gpuBytes_ += textureBytes(gpuTile.width, gpuTile.height);
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
                // The texture's rect (apron included) in normalised image coordinates.
                const LevelInfo info = levelInfo(imageW, imageH, key.level);
                const Rect r = tileRect(info, key.tx, key.ty);
                rect[0] = static_cast<float>(r.x - tile.padLeft) / info.width;
                rect[1] = static_cast<float>(r.y - tile.padTop) / info.height;
                rect[2] = static_cast<float>(tile.width) / info.width;
                rect[3] = static_cast<float>(tile.height) / info.height;
            }
            ensureResult(tile.source, &tile.result, tile.width, tile.height, &tile.graphVersion, rect,
                         1.f / static_cast<float>(1 << key.level));
            if (tile.graphVersion != before) ++passes;
            if (!hadResult && tile.result != 0) gpuBytes_ += textureBytes(tile.width, tile.height);
            if (hadResult && tile.result == 0) gpuBytes_ -= textureBytes(tile.width, tile.height);
        } else {
            more = true;
        }
        drawable.push_back(key);
    }
    // Neighbours of the aprons are requested after the visible tiles (not counted as pending).
    std::vector<TileKey> requests = missing;
    for (const TileKey& n : neighbours)
        if (std::find(requests.begin(), requests.end(), n) == requests.end() && keep.count(n) == 0) requests.push_back(n);
    updateRequests(requests);
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
        // The original is shown through the same geometry: compare is about the edit's
        // colours, and the split / side-by-side halves stay aligned.
        const Geometry* geom = hasGeometry ? &geometry : nullptr;
        if (showOriginal || compare == CompareMode::Off) {
            drawScene(showOriginal, ndc, imageW, imageH, level, drawable, clipping && !showOriginal, geom);
        } else if (compare == CompareMode::Split) {
            drawScene(false, ndc, imageW, imageH, level, drawable, clipping, geom);
            const int splitPx = static_cast<int>(split * static_cast<float>(viewW) + 0.5f);
            if (splitPx > 0) {
                glEnable(GL_SCISSOR_TEST);
                glScissor(0, 0, splitPx, viewH);
                glClear(GL_COLOR_BUFFER_BIT);
                drawScene(true, ndc, imageW, imageH, level, drawable, false, geom);
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
            drawScene(true, halfNdc, imageW, imageH, level, drawable, false, geom);
            glViewport(half, 0, viewW - half, viewH);
            glScissor(half, 0, viewW - half, viewH);
            drawScene(false, halfNdc, imageW, imageH, level, drawable, clipping, geom);
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
                       const std::vector<TileKey>& drawable, bool clipping, const Geometry* geometry) {
    DisplayOptions options;
    options.clipping = clipping;
    // The original is shown without the edit's finishing.
    if (!original) options.finishing = frameFinishing_;
    options.outSize[0] = static_cast<float>(imageW);
    options.outSize[1] = static_cast<float>(imageH);
    if (geometry != nullptr) {
        // Canvas = output pixels; every draw samples its source once through the geometry.
        const GeometryDraw base = drawOf(*geometry);
        if (previewSource_ != 0 && imageW > 0) {
            const GLuint texture = !original && previewResult_ != 0 ? previewResult_ : previewSource_;
            gpu_.drawImage(texture, base.drawRect, ndc, output_, shaderEncodes_, options, &base);
        }
        if (level < 0) return;
        const LevelInfo info = levelInfo(imageW, imageH, level);
        const double span = static_cast<double>(1 << level);
        for (const TileKey& key : drawable) {
            const GpuTile& tile = gpuTiles_[key];
            const Rect r = tileRect(info, key.tx, key.ty);
            const double source[4] = {r.x * span, r.y * span, std::min(r.w * span, imageW - r.x * span),
                                      std::min(r.h * span, imageH - r.y * span)};
            double out[4];
            if (!outputRectOf(*geometry, imageW, imageH, source, out)) continue;
            GeometryDraw d = base;
            for (int i = 0; i < 4; ++i) d.drawRect[i] = static_cast<float>(out[i]);
            // The texture carries an apron; the tile draws only its own rect.
            d.srcRect[0] = static_cast<float>(r.x - tile.padLeft) / info.width;
            d.srcRect[1] = static_cast<float>(r.y - tile.padTop) / info.height;
            d.srcRect[2] = static_cast<float>(tile.width) / info.width;
            d.srcRect[3] = static_cast<float>(tile.height) / info.height;
            d.ownRect[0] = static_cast<float>(r.x) / info.width;
            d.ownRect[1] = static_cast<float>(r.y) / info.height;
            d.ownRect[2] = static_cast<float>(r.w) / info.width;
            d.ownRect[3] = static_cast<float>(r.h) / info.height;
            d.clampTile = true;
            const GLuint texture = !original && tile.result != 0 ? tile.result : tile.source;
            gpu_.drawImage(texture, d.drawRect, ndc, output_, shaderEncodes_, options, &d);
        }
        return;
    }
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
        DisplayOptions tileOptions = options;
        const float tw = static_cast<float>(tile.width), th = static_cast<float>(tile.height);
        tileOptions.texRect[0] = tile.padLeft / tw;
        tileOptions.texRect[1] = tile.padTop / th;
        tileOptions.texRect[2] = tile.ownWidth / tw;
        tileOptions.texRect[3] = tile.ownHeight / th;
        gpu_.drawImage(texture, rect, ndc, output_, shaderEncodes_, tileOptions);
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
    GLuint texture = previewResult_ != 0 ? previewResult_ : previewSource_;
    int histogramW = previewW_, histogramH = previewH_;
    bool hasGeometry;
    Geometry geometry;
    int imageW, imageH;
    Finishing finishing;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        hasGeometry = hasGeometry_;
        geometry = geometry_;
        imageW = imageWidth_;
        imageH = imageHeight_;
        finishing = finishing_;
    }
    if (!hasGeometry && finishing.active() && imageW > 0 && imageH > 0) {
        // The finishing is output-space: the histogram reads it through the identity warp.
        geometry = identityGeometry(imageW, imageH);
        hasGeometry = true;
    }
    if (hasGeometry && imageW > 0) {
        // The histogram of the output: the preview warped at the preview's scale.
        const double scale = static_cast<double>(previewW_) / imageW;
        histogramW = std::max(1, static_cast<int>(std::lround(geometry.outputWidth * scale)));
        histogramH = std::max(1, static_cast<int>(std::lround(geometry.outputHeight * scale)));
        if (histogramWarp_ == 0 || histogramWarpW_ != histogramW || histogramWarpH_ != histogramH) {
            if (histogramWarp_ != 0) glDeleteTextures(1, &histogramWarp_);
            histogramWarp_ = createTexture(histogramW, histogramH, gpu_.halfFloat(), nullptr);
            histogramWarpW_ = histogramW;
            histogramWarpH_ = histogramH;
        }
        std::string error;
        if (!gpu_.warp(texture, drawOf(geometry), histogramWarp_, histogramW, histogramH, &error, finishing)) {
            LOGW("histogram warp failed: %s", error.c_str());
            return;
        }
        texture = histogramWarp_;
    }
    const bool started = gpu_.computeSupported() ? gpu_.dispatchHistogram(texture, histogramW, histogramH)
                                                 : gpu_.startReadback(texture, histogramW, histogramH);
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
    std::shared_ptr<const DetailFields> fields;
    OpRegion region;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ops = ops_;
        guided = guided_;
        fields = fields_;
        tileRectNormalised(key, region.rect);
    }
    region.width = tile->width;
    region.height = tile->height;
    region.coefficients = guided.get();
    region.fields = fields.get();
    region.scale = 1.f / static_cast<float>(1 << key.level);
    out->resize(tile->pixels.size());
    ops->cpuRefRegion(tile->pixels.data(), out->data(), region);
    *width = tile->width;
    *height = tile->height;
    return true;
}

void Engine::outputSize(int* width, int* height) {
    std::lock_guard<std::mutex> lock(mutex_);
    *width = hasGeometry_ ? geometry_.outputWidth : imageWidth_;
    *height = hasGeometry_ ? geometry_.outputHeight : imageHeight_;
}

std::vector<TileKey> Engine::outputTileSources(int tx, int ty) {
    Geometry g;
    int imageW, imageH, apron;
    const bool hasGeometry = exportGeometry(&g, &imageW, &imageH);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        apron = apron_;
    }
    std::vector<TileKey> missing;
    if (!hasGeometry) {
        if (!store_.contains({0, tx, ty})) missing.push_back({0, tx, ty});
        return missing;
    }
    const int x0 = tx * kTileSize, y0 = ty * kTileSize;
    const int w = std::min(kTileSize, g.outputWidth - x0), h = std::min(kTileSize, g.outputHeight - y0);
    if (w <= 0 || h <= 0) return missing;
    int fp[4];
    footprint(g, imageW, imageH, x0, y0, w, h, fp);
    if (fp[2] <= 0 || fp[3] <= 0) return missing;
    expandFootprint(fp, apron, imageW, imageH);
    const LevelInfo info = levelInfo(imageW, imageH, 0);
    for (int y = fp[1] / kTileSize; y <= (fp[1] + fp[3] - 1) / kTileSize && y < info.tilesY; ++y)
        for (int x = fp[0] / kTileSize; x <= (fp[0] + fp[2] - 1) / kTileSize && x < info.tilesX; ++x)
            if (!store_.contains({0, x, y})) missing.push_back({0, x, y});
    return missing;
}

bool Engine::renderOutputRegion(const Geometry& g, int imageW, int imageH, int x0, int y0, int w, int h, float* out,
                                int stride) {
    int fp[4];
    footprint(g, imageW, imageH, x0, y0, w, h, fp);
    if (static_cast<long long>(fp[2]) * fp[3] > kMaxFootprintPixels && (w > 16 || h > 16)) {
        // A far part of a strong perspective reads a large source area: render in halves.
        if (w >= h) {
            const int half = w / 2;
            return renderOutputRegion(g, imageW, imageH, x0, y0, half, h, out, stride) &&
                   renderOutputRegion(g, imageW, imageH, x0 + half, y0, w - half, h, out + 4 * half, stride);
        }
        const int half = h / 2;
        return renderOutputRegion(g, imageW, imageH, x0, y0, w, half, out, stride) &&
               renderOutputRegion(g, imageW, imageH, x0, y0 + half, w, h - half, out + static_cast<std::size_t>(half) * stride * 4,
                                  stride);
    }
    std::shared_ptr<const OpList> ops;
    std::shared_ptr<const GuidedCoefficients> guided;
    std::shared_ptr<const DetailFields> fields;
    Finishing finishing;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ops = ops_;
        guided = guided_;
        fields = fields_;
        finishing = finishing_;
    }
    // Spatial ops (Stage 10) read around each pixel: the footprint carries their apron.
    if (fp[2] > 0 && fp[3] > 0) expandFootprint(fp, ops->apron(), imageW, imageH);
    std::vector<float> region(static_cast<std::size_t>(w) * h * 4, 0.f);
    if (fp[2] > 0 && fp[3] > 0) {
        // The footprint assembled from the level-0 tiles, then the ops on it (source space).
        std::vector<float> source(static_cast<std::size_t>(fp[2]) * fp[3] * 4, 0.f);
        const LevelInfo info = levelInfo(imageW, imageH, 0);
        for (int ty = fp[1] / kTileSize; ty <= (fp[1] + fp[3] - 1) / kTileSize && ty < info.tilesY; ++ty) {
            for (int tx = fp[0] / kTileSize; tx <= (fp[0] + fp[2] - 1) / kTileSize && tx < info.tilesX; ++tx) {
                const std::shared_ptr<const Tile> tile = store_.get({0, tx, ty});
                if (!tile) return false;
                const Rect r = tileRect(info, tx, ty);
                const int ax = std::max(r.x, fp[0]), ay = std::max(r.y, fp[1]);
                const int bx = std::min(r.x + tile->width, fp[0] + fp[2]), by = std::min(r.y + tile->height, fp[1] + fp[3]);
                for (int y = ay; y < by; ++y) {
                    const float* src = tile->pixels.data() + (static_cast<std::size_t>(y - r.y) * tile->width + (ax - r.x)) * 4;
                    float* dst = source.data() + (static_cast<std::size_t>(y - fp[1]) * fp[2] + (ax - fp[0])) * 4;
                    std::copy(src, src + (bx - ax) * 4, dst);
                }
            }
        }
        OpRegion opRegion;
        opRegion.rect[0] = static_cast<float>(fp[0]) / imageW;
        opRegion.rect[1] = static_cast<float>(fp[1]) / imageH;
        opRegion.rect[2] = static_cast<float>(fp[2]) / imageW;
        opRegion.rect[3] = static_cast<float>(fp[3]) / imageH;
        opRegion.width = fp[2];
        opRegion.height = fp[3];
        opRegion.coefficients = guided.get();
        opRegion.fields = fields.get();
        opRegion.scale = 1.f;
        ops->cpuRefRegion(source.data(), source.data(), opRegion);
        WarpSource ws;
        ws.rgba = source.data();
        ws.width = fp[2];
        ws.height = fp[3];
        ws.x0 = fp[0];
        ws.y0 = fp[1];
        ws.scale = 1.0;
        warp(g, imageW, imageH, ws, 1.0, x0, y0, w, h, region.data());
    }
    applyFinishing(finishing, region.data(), w, h, g.outputWidth, g.outputHeight, x0, y0, 1.0);
    for (int y = 0; y < h; ++y)
        std::copy(region.data() + static_cast<std::size_t>(y) * w * 4, region.data() + static_cast<std::size_t>(y + 1) * w * 4,
                  out + static_cast<std::size_t>(y) * stride * 4);
    return true;
}

bool Engine::renderOutputTile(int tx, int ty, std::vector<float>* out, int* width, int* height) {
    Geometry g;
    int imageW, imageH;
    const bool hasGeometry = exportGeometry(&g, &imageW, &imageH);
    if (!hasGeometry) return renderTileCpu({0, tx, ty}, out, width, height);
    const int x0 = tx * kTileSize, y0 = ty * kTileSize;
    const int w = std::min(kTileSize, g.outputWidth - x0), h = std::min(kTileSize, g.outputHeight - y0);
    if (w <= 0 || h <= 0) return false;
    out->assign(static_cast<std::size_t>(w) * h * 4, 0.f);
    if (!renderOutputRegion(g, imageW, imageH, x0, y0, w, h, out->data(), w)) return false;
    *width = w;
    *height = h;
    return true;
}

bool Engine::lineSegments(std::vector<LineSegment>* out) {
    std::shared_ptr<const ImageF32> source;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        source = analysisSource_;
    }
    if (!source || source->width == 0) return false;
    *out = detectSegments(source->rgba.data(), source->width, source->height);
    return true;
}

bool Engine::horizon(float* degrees) {
    std::shared_ptr<const ImageF32> source;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        source = analysisSource_;
    }
    return source && source->width > 0 && horizonAngle(source->rgba.data(), source->width, source->height, degrees);
}

bool Engine::chromaticAberration(float* caRed, float* caBlue) {
    std::shared_ptr<const ImageF32> source;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        source = analysisSource_;
    }
    return source && source->width > 0 &&
           estimateChromaticAberration(source->rgba.data(), source->width, source->height, caRed, caBlue);
}

bool Engine::gpuSelfTest(const float* in, int width, int height, const std::string& graph, std::uint8_t* out,
                         std::size_t capacity, std::string* error) {
    Graph parsed;
    if (!parseGraph(graph, &parsed, error)) return false;
    std::shared_ptr<const OpList> ops = buildOps(parsed, nullptr);
    Geometry rawGeometry;
    const bool hasGeometry = geometryOf(parsed, &rawGeometry);
    const Geometry geometry = hasGeometry ? fitted(rawGeometry, width, height) : Geometry{};
    const int outW = hasGeometry ? geometry.outputWidth : width;
    const int outH = hasGeometry ? geometry.outputHeight : height;
    Finishing finishing;
    finishingOf(parsed, &finishing);
    if (capacity < static_cast<std::size_t>(outW) * outH * 4) {
        *error = "output buffer too small for " + std::to_string(outW) + " x " + std::to_string(outH);
        return false;
    }
    std::string failure;
    const bool ok = runSync([&] {
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        const GLuint source = createTexture(width, height, gpu_.halfFloat(), in);
        const GLuint result = createTexture(width, height, gpu_.halfFloat(), nullptr);
        // The test image is its own preview: coefficients from it, as the canvas does.
        const GuidedCoefficients coefficients = guidedCoefficients(in, width, height);
        const DetailFields fields = computeDetailFields(in, width, height);
        OpPassInputs inputs;
        inputs.guided = createGuidedTexture(&coefficients);
        inputs.guidedWidth = width;
        inputs.guidedHeight = height;
        createFieldTextures(&fields, &inputs.fields, &inputs.haze);
        inputs.fieldsWidth = inputs.hazeWidth = width;
        inputs.fieldsHeight = inputs.hazeHeight = height;
        std::copy(fields.airlight, fields.airlight + 3, inputs.air);
        bool passed = gpu_.runOps(*ops, source, result, width, height, inputs, &failure);
        glDeleteTextures(1, &inputs.guided);
        glDeleteTextures(1, &inputs.fields);
        glDeleteTextures(1, &inputs.haze);
        if (passed) {
            GLuint target = 0, fbo = 0;
            glGenTextures(1, &target);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, outW, outH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            glGenFramebuffers(1, &fbo);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, outW, outH);
            glClearColor(0.f, 0.f, 0.f, 0.f);
            glClear(GL_COLOR_BUFFER_BIT);
            // Image row 0 -> framebuffer row 0 (the first row glReadPixels returns).
            const float ndc[9] = {2.f / outW, 0.f, -1.f, 0.f, 2.f / outH, -1.f, 0.f, 0.f, 1.f};
            const float rect[4] = {0.f, 0.f, static_cast<float>(outW), static_cast<float>(outH)};
            DisplayOptions options;
            options.finishing = finishing;
            options.outSize[0] = static_cast<float>(outW);
            options.outSize[1] = static_cast<float>(outH);
            if (hasGeometry) {
                // The canvas's own geometry draw: one bicubic resample in the display pass.
                const GeometryDraw draw = drawOf(geometry);
                gpu_.drawImage(result, rect, ndc, color::Display::Srgb, true, options, &draw);
            } else {
                gpu_.drawImage(result, rect, ndc, color::Display::Srgb, true, options);
            }
            glReadPixels(0, 0, outW, outH, GL_RGBA, GL_UNSIGNED_BYTE, out);
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
