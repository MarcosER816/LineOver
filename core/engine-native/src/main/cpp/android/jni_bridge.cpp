// JNI entry points for dev.mark.lineover.core.engine.LoEngine.
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <string>
#include <vector>

#include "gl_smoke.h"
#include "lineover/engine.h"
#include "lo_api.h"

namespace {

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) {
        env->ThrowNew(exception, message.c_str());
    }
}

JavaVM* gVm = nullptr;

lo_engine* handle(jlong value) { return reinterpret_cast<lo_engine*>(value); }

// Loading callbacks reach the Kotlin NativeEngine on the render thread.
struct Callbacks {
    jobject target = nullptr;  // global ref
    jmethodID onLoading = nullptr;
    jmethodID onHistogram = nullptr;
    bool attached = false;
};

JNIEnv* renderThreadEnv(Callbacks* callbacks) {
    JNIEnv* env = nullptr;
    if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args{JNI_VERSION_1_6, "LineOverRender", nullptr};
    if (gVm->AttachCurrentThread(&env, &args) != JNI_OK) return nullptr;
    callbacks->attached = true;
    return env;
}

void onLoading(void* user, int loading) {
    auto* callbacks = static_cast<Callbacks*>(user);
    JNIEnv* env = renderThreadEnv(callbacks);
    if (env == nullptr) return;
    env->CallVoidMethod(callbacks->target, callbacks->onLoading, static_cast<jboolean>(loading != 0));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void onHistogram(void* user, const uint32_t* bins) {
    auto* callbacks = static_cast<Callbacks*>(user);
    JNIEnv* env = renderThreadEnv(callbacks);
    if (env == nullptr) return;
    jintArray array = env->NewIntArray(LO_HISTOGRAM_SIZE);
    if (array == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->SetIntArrayRegion(array, 0, LO_HISTOGRAM_SIZE, reinterpret_cast<const jint*>(bins));
    env->CallVoidMethod(callbacks->target, callbacks->onHistogram, array);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(array);
}

void onThreadExit(void* user) {
    auto* callbacks = static_cast<Callbacks*>(user);
    JNIEnv* env = renderThreadEnv(callbacks);
    if (env != nullptr) env->DeleteGlobalRef(callbacks->target);
    if (callbacks->attached) gVm->DetachCurrentThread();
    delete callbacks;
}

int premultiplied(const AndroidBitmapInfo& info) {
    return (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL ? 1 : 0;
}

jstring errorOrNull(JNIEnv* env, int ok, const char* error) { return ok ? nullptr : env->NewStringUTF(error); }

// Locks a Bitmap's pixels for the duration of `use`; false if it is not RGBA_8888 / RGBA_F16.
template <typename Use>
bool withPixels(JNIEnv* env, jobject bitmap, jint format, Use use) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    const bool f16 = info.format == ANDROID_BITMAP_FORMAT_RGBA_F16;
    if (!(info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 || f16)) return false;
    if (f16 != (format == LO_FORMAT_RGBAF16_LINEAR_EXTENDED_SRGB)) return false;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    const bool ok = use(pixels, info);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

#define LO_NATIVE(name) JNICALL Java_dev_mark_lineover_core_engine_NativeEngine_##name

JNIEXPORT jlong LO_NATIVE(nativeCreate)(JNIEnv* env, jobject self, jstring cacheDir, jlong cpuBudget, jlong gpuBudget) {
    const char* dir = env->GetStringUTFChars(cacheDir, nullptr);
    char error[256] = {0};
    lo_engine* engine = lo_engine_create(dir, static_cast<size_t>(cpuBudget), static_cast<size_t>(gpuBudget), error, sizeof(error));
    env->ReleaseStringUTFChars(cacheDir, dir);
    if (engine == nullptr) {
        throwIllegalState(env, error);
        return 0;
    }
    // The render thread calls these back; a release build that lost them (R8) must fail here,
    // as an exception, not later on the render thread with a null method id.
    jclass type = env->GetObjectClass(self);
    const jmethodID onLoadingId = env->GetMethodID(type, "onLoadingChanged", "(Z)V");
    const jmethodID onHistogramId = onLoadingId != nullptr ? env->GetMethodID(type, "onHistogram", "([I)V") : nullptr;
    env->DeleteLocalRef(type);
    if (onLoadingId == nullptr || onHistogramId == nullptr) {
        env->ExceptionClear();
        lo_engine_destroy(engine);
        throwIllegalState(env, "the engine's Kotlin callbacks are missing from this build (onLoadingChanged / onHistogram)");
        return 0;
    }
    auto* callbacks = new Callbacks();
    callbacks->target = env->NewGlobalRef(self);
    callbacks->onLoading = onLoadingId;
    callbacks->onHistogram = onHistogramId;
    lo_engine_set_callbacks(engine, onLoading, onThreadExit, callbacks);
    lo_engine_set_histogram_callback(engine, onHistogram, callbacks);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void LO_NATIVE(nativeDestroy)(JNIEnv*, jobject, jlong engine) { lo_engine_destroy(handle(engine)); }

JNIEXPORT void LO_NATIVE(nativeOpenImage)(JNIEnv*, jobject, jlong engine, jint width, jint height) {
    lo_engine_open_image(handle(engine), width, height);
}

JNIEXPORT jboolean LO_NATIVE(nativeSetPreview)(JNIEnv* env, jobject, jlong engine, jobject bitmap, jint format) {
    return withPixels(env, bitmap, format, [&](void* pixels, const AndroidBitmapInfo& info) {
        return lo_engine_set_preview(handle(engine), pixels, info.stride, static_cast<int>(info.width),
                                     static_cast<int>(info.height), format, premultiplied(info)) == 1;
    });
}

JNIEXPORT jboolean LO_NATIVE(nativeSubmitRegion)(JNIEnv* env, jobject, jlong engine, jint level, jint tx, jint ty,
                                                 jobject bitmap, jint format, jint regionX, jint regionY, jint rawLevelW,
                                                 jint rawLevelH, jint orientation) {
    const bool ok = withPixels(env, bitmap, format, [&](void* pixels, const AndroidBitmapInfo& info) {
        return lo_engine_submit_region(handle(engine), level, tx, ty, pixels, info.stride, format, regionX, regionY,
                                       static_cast<int>(info.width), static_cast<int>(info.height), rawLevelW, rawLevelH,
                                       orientation, premultiplied(info)) == 1;
    });
    if (!ok) lo_engine_fail_tile(handle(engine), level, tx, ty);
    return ok;
}

JNIEXPORT void LO_NATIVE(nativeFailTile)(JNIEnv*, jobject, jlong engine, jint level, jint tx, jint ty) {
    lo_engine_fail_tile(handle(engine), level, tx, ty);
}

JNIEXPORT jboolean LO_NATIVE(nativeTakeRequest)(JNIEnv* env, jobject, jlong engine, jint timeoutMs, jintArray out) {
    int level, tx, ty;
    if (lo_engine_take_request(handle(engine), timeoutMs, &level, &tx, &ty) == 0) return JNI_FALSE;
    const jint values[3] = {level, tx, ty};
    env->SetIntArrayRegion(out, 0, 3, values);
    return JNI_TRUE;
}

JNIEXPORT jstring LO_NATIVE(nativeSetGraph)(JNIEnv* env, jobject, jlong engine, jstring json) {
    const char* text = env->GetStringUTFChars(json, nullptr);
    char error[256] = {0};
    const int ok = lo_engine_set_graph(handle(engine), text, error, sizeof(error));
    env->ReleaseStringUTFChars(json, text);
    return errorOrNull(env, ok, error);
}

JNIEXPORT jstring LO_NATIVE(nativeAttachSurface)(JNIEnv* env, jobject, jlong engine, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return env->NewStringUTF("no native window for the surface");
    char error[256] = {0};
    const int ok = lo_engine_attach_window(handle(engine), window, error, sizeof(error));
    ANativeWindow_release(window);  // EGL holds its own reference
    return errorOrNull(env, ok, error);
}

JNIEXPORT void LO_NATIVE(nativeDetachSurface)(JNIEnv*, jobject, jlong engine) { lo_engine_detach_window(handle(engine)); }

JNIEXPORT void LO_NATIVE(nativeSetViewport)(JNIEnv*, jobject, jlong engine, jint width, jint height) {
    lo_engine_set_viewport(handle(engine), width, height);
}

JNIEXPORT void LO_NATIVE(nativeSetTransform)(JNIEnv*, jobject, jlong engine, jfloat a, jfloat b, jfloat c, jfloat d, jfloat e,
                                             jfloat f) {
    lo_engine_set_transform(handle(engine), a, b, c, d, e, f);
}

JNIEXPORT void LO_NATIVE(nativeSetSurround)(JNIEnv*, jobject, jlong engine, jfloat r, jfloat g, jfloat b) {
    lo_engine_set_surround(handle(engine), r, g, b);
}

JNIEXPORT void LO_NATIVE(nativeStats)(JNIEnv* env, jobject, jlong engine, jlongArray out) {
    lo_engine_stats s;
    lo_engine_get_stats(handle(engine), &s);
    const jlong values[18] = {static_cast<jlong>(s.cpu_tile_bytes), static_cast<jlong>(s.cpu_tiles),
                              static_cast<jlong>(s.spilled_tiles),  static_cast<jlong>(s.spill_bytes),
                              static_cast<jlong>(s.gpu_bytes),      static_cast<jlong>(s.gpu_tiles),
                              static_cast<jlong>(s.pending_tiles),  static_cast<jlong>(s.frames),
                              static_cast<jlong>(s.tiles_decoded),  s.half_float,
                              s.display_p3,                         s.level,
                              static_cast<jlong>(s.p95_frame_interval_us), static_cast<jlong>(s.p95_draw_us),
                              static_cast<jlong>(s.p95_update_us),  static_cast<jlong>(s.last_update_us),
                              static_cast<jlong>(s.updates),        s.histogram};
    env->SetLongArrayRegion(out, 0, 18, values);
}

JNIEXPORT jfloatArray LO_NATIVE(nativeSample)(JNIEnv* env, jobject, jlong engine, jfloat u, jfloat v, jint before) {
    float rgb[3];
    if (lo_engine_sample(handle(engine), u, v, before, rgb) == 0) return nullptr;
    jfloatArray result = env->NewFloatArray(3);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 3, rgb);
    return result;
}

JNIEXPORT jfloatArray LO_NATIVE(nativeSampleGrid)(JNIEnv* env, jobject, jlong engine, jfloat u, jfloat v, jint n) {
    if (n <= 0 || n > 15) return nullptr;
    std::vector<float> rgba(static_cast<std::size_t>(n) * n * 4);
    if (lo_engine_sample_grid(handle(engine), u, v, n, rgba.data()) == 0) return nullptr;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(rgba.size()));
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(rgba.size()), rgba.data());
    return result;
}

JNIEXPORT jfloatArray JNICALL Java_dev_mark_lineover_core_engine_ColorMath_nativeNeutral(JNIEnv* env, jclass, jfloat r, jfloat g,
                                                                                        jfloat b) {
    const float rgb[3] = {r, g, b};
    float out[2];
    if (lo_white_balance_for_neutral(rgb, &out[0], &out[1]) == 0) return nullptr;
    jfloatArray result = env->NewFloatArray(2);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 2, out);
    return result;
}

JNIEXPORT jfloatArray JNICALL Java_dev_mark_lineover_core_engine_ColorMath_nativeHslWeights(JNIEnv* env, jclass, jfloat r, jfloat g,
                                                                                           jfloat b) {
    const float rgb[3] = {r, g, b};
    float out[8];
    lo_hsl_targeted_weights(rgb, out);
    jfloatArray result = env->NewFloatArray(8);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 8, out);
    return result;
}

JNIEXPORT jfloatArray LO_NATIVE(nativeAutoLevels)(JNIEnv* env, jobject, jlong engine) {
    float out[6];
    if (lo_engine_auto_levels(handle(engine), out) == 0) return nullptr;
    jfloatArray result = env->NewFloatArray(6);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 6, out);
    return result;
}

// [width, height] in the first 8 bytes (little endian), then RGBA8; null before a preview.
JNIEXPORT jbyteArray LO_NATIVE(nativeRenderThumbnail)(JNIEnv* env, jobject, jlong engine, jstring graph, jint maxEdge) {
    constexpr int kMaxEdge = 512;
    std::vector<std::uint8_t> rgba(static_cast<std::size_t>(kMaxEdge) * kMaxEdge * 4);
    int w = 0, h = 0;
    const char* text = env->GetStringUTFChars(graph, nullptr);
    const int ok =
        lo_engine_render_thumbnail_sized(handle(engine), text, maxEdge, rgba.data(), static_cast<int>(rgba.size()), &w, &h);
    env->ReleaseStringUTFChars(graph, text);
    if (ok == 0) return nullptr;
    const jsize size = static_cast<jsize>(w) * h * 4;
    jbyteArray result = env->NewByteArray(size + 8);
    if (result == nullptr) return nullptr;
    const jint dims[2] = {w, h};
    env->SetByteArrayRegion(result, 0, 8, reinterpret_cast<const jbyte*>(dims));
    env->SetByteArrayRegion(result, 8, size, reinterpret_cast<const jbyte*>(rgba.data()));
    return result;
}

// Auto (Stage 11): [temperature, tint, exposure, contrast, whites, blacks, vibrance], or null.
JNIEXPORT jfloatArray LO_NATIVE(nativeAutoSettings)(JNIEnv* env, jobject, jlong engine) {
    float out[7];
    if (lo_engine_auto_settings(handle(engine), out) == 0) return nullptr;
    jfloatArray result = env->NewFloatArray(7);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 7, out);
    return result;
}

// 3D LUTs are process-wide (the engine handle is not used).
JNIEXPORT jboolean LO_NATIVE(nativeRegisterLut)(JNIEnv* env, jobject, jstring id, jint size, jfloatArray domain, jfloatArray rgb) {
    if (env->GetArrayLength(domain) != 6) return JNI_FALSE;
    const auto expected = static_cast<jsize>(size) * size * size * 3;
    if (size < 2 || size > 65 || env->GetArrayLength(rgb) != expected) return JNI_FALSE;
    float d[6];
    env->GetFloatArrayRegion(domain, 0, 6, d);
    std::vector<float> table(static_cast<std::size_t>(expected));
    env->GetFloatArrayRegion(rgb, 0, expected, table.data());
    const char* text = env->GetStringUTFChars(id, nullptr);
    const int ok = lo_register_lut(text, size, d, d + 3, table.data());
    env->ReleaseStringUTFChars(id, text);
    return ok != 0 ? JNI_TRUE : JNI_FALSE;
}

// Stage 13: a layer image from a decoded Bitmap (process-wide; the engine handle is not used).
JNIEXPORT jboolean LO_NATIVE(nativeRegisterLayerImage)(JNIEnv* env, jobject, jstring id, jobject bitmap, jint format,
                                                       jboolean full) {
    const char* text = env->GetStringUTFChars(id, nullptr);
    const bool ok = withPixels(env, bitmap, format, [&](void* pixels, const AndroidBitmapInfo& info) {
        return lo_register_layer_image(text, pixels, info.stride, static_cast<int>(info.width), static_cast<int>(info.height),
                                       format, premultiplied(info), full ? 1 : 0) == 1;
    });
    env->ReleaseStringUTFChars(id, text);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void LO_NATIVE(nativeUnregisterLayerImage)(JNIEnv* env, jobject, jstring id) {
    const char* text = env->GetStringUTFChars(id, nullptr);
    lo_unregister_layer_image(text);
    env->ReleaseStringUTFChars(id, text);
}

JNIEXPORT void LO_NATIVE(nativeRenderComposite)(JNIEnv*, jobject, jlong engine) { lo_engine_render_composite(handle(engine)); }

JNIEXPORT void LO_NATIVE(nativeUnregisterLut)(JNIEnv* env, jobject, jstring id) {
    const char* text = env->GetStringUTFChars(id, nullptr);
    lo_unregister_lut(text);
    env->ReleaseStringUTFChars(id, text);
}

JNIEXPORT jfloatArray LO_NATIVE(nativeAutoLight)(JNIEnv* env, jobject, jlong engine) {
    float exposure = 0.f, blacks = 0.f;
    if (lo_engine_auto_light(handle(engine), &exposure, &blacks) == 0) return nullptr;
    const jfloat values[2] = {exposure, blacks};
    jfloatArray result = env->NewFloatArray(2);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 2, values);
    return result;
}

JNIEXPORT void LO_NATIVE(nativeSetShowOriginal)(JNIEnv*, jobject, jlong engine, jboolean original) {
    lo_engine_set_show_original(handle(engine), original ? 1 : 0);
}

JNIEXPORT void LO_NATIVE(nativeSetCompare)(JNIEnv*, jobject, jlong engine, jint mode, jfloat split) {
    lo_engine_set_compare(handle(engine), mode, split);
}

JNIEXPORT void LO_NATIVE(nativeSetClipping)(JNIEnv*, jobject, jlong engine, jboolean clipping) {
    lo_engine_set_clipping(handle(engine), clipping ? 1 : 0);
}

JNIEXPORT void LO_NATIVE(nativeSetHistogramEnabled)(JNIEnv*, jobject, jlong engine, jboolean enabled) {
    lo_engine_set_histogram_enabled(handle(engine), enabled ? 1 : 0);
}

JNIEXPORT jstring LO_NATIVE(nativeGpuHistogramSelfTest)(JNIEnv* env, jobject, jlong engine, jfloatArray input, jint width,
                                                        jint height, jintArray out) {
    std::vector<float> in(static_cast<std::size_t>(env->GetArrayLength(input)));
    env->GetFloatArrayRegion(input, 0, static_cast<jsize>(in.size()), in.data());
    std::vector<std::uint32_t> bins(LO_HISTOGRAM_SIZE);
    char error[256] = {0};
    const int ok = lo_engine_gpu_histogram_self_test(handle(engine), in.data(), width, height, bins.data(), error, sizeof(error));
    if (ok) env->SetIntArrayRegion(out, 0, LO_HISTOGRAM_SIZE, reinterpret_cast<const jint*>(bins.data()));
    return errorOrNull(env, ok, error);
}

JNIEXPORT jintArray LO_NATIVE(nativeRenderTileCpu)(JNIEnv* env, jobject, jlong engine, jint level, jint tx, jint ty,
                                                   jfloatArray out) {
    const jsize capacity = env->GetArrayLength(out);
    std::vector<float> pixels(static_cast<std::size_t>(capacity));
    int width = 0, height = 0;
    const int count = lo_engine_render_tile_cpu(handle(engine), level, tx, ty, pixels.data(), pixels.size(), &width, &height);
    if (count == 0) return nullptr;
    env->SetFloatArrayRegion(out, 0, count * 4, pixels.data());
    const jint size[2] = {width, height};
    jintArray result = env->NewIntArray(2);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 2, size);
    return result;
}

JNIEXPORT jintArray LO_NATIVE(nativeOutputSize)(JNIEnv* env, jobject, jlong engine) {
    jint size[2] = {0, 0};
    lo_engine_output_size(handle(engine), &size[0], &size[1]);
    jintArray result = env->NewIntArray(2);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 2, size);
    return result;
}

JNIEXPORT jintArray LO_NATIVE(nativeOutputTileSources)(JNIEnv* env, jobject, jlong engine, jint tx, jint ty) {
    std::vector<int> pairs(64);
    int count = lo_engine_output_tile_sources(handle(engine), tx, ty, pairs.data(), pairs.size());
    if (2 * static_cast<std::size_t>(count) > pairs.size()) {
        pairs.resize(2 * static_cast<std::size_t>(count));
        count = lo_engine_output_tile_sources(handle(engine), tx, ty, pairs.data(), pairs.size());
        count = std::min(count, static_cast<int>(pairs.size() / 2));
    }
    jintArray result = env->NewIntArray(2 * count);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 2 * count, reinterpret_cast<const jint*>(pairs.data()));
    return result;
}

JNIEXPORT jintArray LO_NATIVE(nativeRenderOutputTile)(JNIEnv* env, jobject, jlong engine, jint tx, jint ty, jfloatArray out) {
    const jsize capacity = env->GetArrayLength(out);
    std::vector<float> pixels(static_cast<std::size_t>(capacity));
    int width = 0, height = 0;
    const int count = lo_engine_render_output_tile(handle(engine), tx, ty, pixels.data(), pixels.size(), &width, &height);
    if (count == 0) return nullptr;
    env->SetFloatArrayRegion(out, 0, count * 4, pixels.data());
    const jint size[2] = {width, height};
    jintArray result = env->NewIntArray(2);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 2, size);
    return result;
}

// 5 floats per segment (x1, y1, x2, y2 normalised, width px); null before a preview.
JNIEXPORT jfloatArray LO_NATIVE(nativeLineSegments)(JNIEnv* env, jobject, jlong engine) {
    std::vector<float> values(5 * 512);
    int count = lo_engine_line_segments(handle(engine), values.data(), values.size());
    if (count < 0) return nullptr;
    if (5 * static_cast<std::size_t>(count) > values.size()) {
        values.resize(5 * static_cast<std::size_t>(count));
        count = std::min(lo_engine_line_segments(handle(engine), values.data(), values.size()), count);
    }
    jfloatArray result = env->NewFloatArray(5 * count);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 5 * count, values.data());
    return result;
}

JNIEXPORT jfloatArray LO_NATIVE(nativeHorizon)(JNIEnv* env, jobject, jlong engine) {
    float degrees = 0.f;
    if (lo_engine_horizon(handle(engine), &degrees) == 0) return nullptr;
    jfloatArray result = env->NewFloatArray(1);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 1, &degrees);
    return result;
}

JNIEXPORT jfloatArray LO_NATIVE(nativeChromaticAberration)(JNIEnv* env, jobject, jlong engine) {
    float ca[2] = {0.f, 0.f};
    if (lo_engine_chromatic_aberration(handle(engine), &ca[0], &ca[1]) == 0) return nullptr;
    jfloatArray result = env->NewFloatArray(2);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 2, ca);
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_dev_mark_lineover_core_engine_GeometryWarp_nativeWarpRgba8(JNIEnv* env, jclass, jstring graph,
                                                                                            jint sourceWidth, jint sourceHeight,
                                                                                            jbyteArray rgba, jint width,
                                                                                            jint height, jint outWidth,
                                                                                            jint outHeight) {
    std::vector<std::uint8_t> in(static_cast<std::size_t>(env->GetArrayLength(rgba)));
    if (in.size() < static_cast<std::size_t>(width) * height * 4) return nullptr;
    env->GetByteArrayRegion(rgba, 0, static_cast<jsize>(in.size()), reinterpret_cast<jbyte*>(in.data()));
    std::vector<std::uint8_t> out(static_cast<std::size_t>(outWidth) * outHeight * 4);
    const char* text = env->GetStringUTFChars(graph, nullptr);
    const int ok = lo_warp_rgba8(text, sourceWidth, sourceHeight, in.data(), width, height, outWidth, outHeight, out.data());
    env->ReleaseStringUTFChars(graph, text);
    if (ok == 0) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return result;
}

JNIEXPORT jstring LO_NATIVE(nativeGpuSelfTest)(JNIEnv* env, jobject, jlong engine, jfloatArray input, jint width, jint height,
                                               jstring graph, jbyteArray out) {
    std::vector<float> in(static_cast<std::size_t>(env->GetArrayLength(input)));
    env->GetFloatArrayRegion(input, 0, static_cast<jsize>(in.size()), in.data());
    std::vector<std::uint8_t> result(static_cast<std::size_t>(env->GetArrayLength(out)));
    const char* text = env->GetStringUTFChars(graph, nullptr);
    char error[256] = {0};
    const int ok = lo_engine_gpu_self_test(handle(engine), in.data(), width, height, text, result.data(), result.size(), error,
                                           sizeof(error));
    env->ReleaseStringUTFChars(graph, text);
    if (ok) env->SetByteArrayRegion(out, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte*>(result.data()));
    return errorOrNull(env, ok, error);
}

JNIEXPORT jstring JNICALL
Java_dev_mark_lineover_core_engine_LoEngine_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(lo::version());
}

JNIEXPORT jbyteArray JNICALL
Java_dev_mark_lineover_core_engine_LoEngine_nativeIdentityU8(JNIEnv* env, jclass, jbyteArray source) {
    const jsize count = env->GetArrayLength(source);
    jbyteArray result = env->NewByteArray(count);
    if (result == nullptr) return nullptr;  // OutOfMemoryError is already pending.

    jbyte* src = env->GetByteArrayElements(source, nullptr);
    jbyte* dst = env->GetByteArrayElements(result, nullptr);
    if (src != nullptr && dst != nullptr) {
        lo::ops::identity(reinterpret_cast<const std::uint8_t*>(src), reinterpret_cast<std::uint8_t*>(dst),
                          static_cast<std::size_t>(count));
    }
    if (dst != nullptr) env->ReleaseByteArrayElements(result, dst, 0);
    if (src != nullptr) env->ReleaseByteArrayElements(source, src, JNI_ABORT);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_mark_lineover_core_engine_LoEngine_nativeIdentityF32(JNIEnv* env, jclass, jfloatArray source) {
    const jsize count = env->GetArrayLength(source);
    jfloatArray result = env->NewFloatArray(count);
    if (result == nullptr) return nullptr;

    jfloat* src = env->GetFloatArrayElements(source, nullptr);
    jfloat* dst = env->GetFloatArrayElements(result, nullptr);
    if (src != nullptr && dst != nullptr) {
        lo::ops::identity(src, dst, static_cast<std::size_t>(count));
    }
    if (dst != nullptr) env->ReleaseFloatArrayElements(result, dst, 0);
    if (src != nullptr) env->ReleaseFloatArrayElements(source, src, JNI_ABORT);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_dev_mark_lineover_core_engine_LoEngine_nativeGlClearReadback(JNIEnv* env, jclass, jfloat r, jfloat g,
                                                                  jfloat b, jfloat a) {
    std::array<std::uint8_t, 4> rgba{};
    std::string error;
    if (!lo::android::glClearAndReadPixel(r, g, b, a, &rgba, &error)) {
        throwIllegalState(env, error);
        return nullptr;
    }
    const jint values[4] = {rgba[0], rgba[1], rgba[2], rgba[3]};
    jintArray result = env->NewIntArray(4);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

}  // extern "C"
