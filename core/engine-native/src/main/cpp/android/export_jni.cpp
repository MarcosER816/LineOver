// JNI for the export (Stage 12): dev.mark.lineover.core.engine.NativeExport. One job holds the
// full-resolution render in a mapped float image; Kotlin fills it tile by tile, then asks for
// resize / sharpen / watermark and either a native PNG / TIFF file or an RGBA_8888 Bitmap for
// the platform encoders (JPEG, WebP, HEIF, AVIF, Ultra HDR).
#include <android/bitmap.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "lineover/output.h"

using lo::output::Image;
using lo::output::Space;

namespace {

struct ExportJob {
    std::string dir;
    std::unique_ptr<Image> image;
};

ExportJob* job(jlong value) { return reinterpret_cast<ExportJob*>(value); }

Space spaceOf(jint value) { return static_cast<Space>(std::clamp(value, 0, 2)); }

jstring error(JNIEnv* env, const std::string& message) { return env->NewStringUTF(message.c_str()); }

std::FILE* openFd(jint fd) {
    const int copy = dup(fd);
    if (copy < 0) return nullptr;
    std::FILE* f = fdopen(copy, "wb");
    if (f == nullptr) close(copy);
    return f;
}

std::vector<std::uint8_t> bytesOf(JNIEnv* env, jbyteArray array) {
    std::vector<std::uint8_t> out;
    if (array == nullptr) return out;
    out.resize(static_cast<std::size_t>(env->GetArrayLength(array)));
    if (!out.empty()) env->GetByteArrayRegion(array, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte*>(out.data()));
    return out;
}

lo::output::RowSource rows(const Image& image, Space space, int bits) {
    return [&image, space, bits](int y, void* out) {
        if (bits == 8)
            lo::output::convertRow8(image.row(y), image.width(), space, 3, static_cast<std::uint8_t*>(out));
        else
            lo::output::convertRow16(image.row(y), image.width(), space, static_cast<std::uint16_t*>(out));
    };
}

}  // namespace

extern "C" {

#define LO_EXPORT(name) JNICALL Java_dev_mark_lineover_core_engine_NativeExport_##name

JNIEXPORT jlong LO_EXPORT(nativeCreate)(JNIEnv* env, jobject, jstring dir, jint width, jint height) {
    const char* d = env->GetStringUTFChars(dir, nullptr);
    auto j = std::make_unique<ExportJob>();
    j->dir = d;
    env->ReleaseStringUTFChars(dir, d);
    std::string failure;
    j->image = Image::create(j->dir, width, height, &failure);
    if (!j->image) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type != nullptr) env->ThrowNew(type, failure.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(j.release());
}

JNIEXPORT void LO_EXPORT(nativeDestroy)(JNIEnv*, jobject, jlong handle) { delete job(handle); }

JNIEXPORT jintArray LO_EXPORT(nativeSize)(JNIEnv* env, jobject, jlong handle) {
    const jint size[2] = {job(handle)->image->width(), job(handle)->image->height()};
    jintArray out = env->NewIntArray(2);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 2, size);
    return out;
}

// A rendered tile (RGBA float, `width` x `height`, row-major) at (x, y) of the image.
JNIEXPORT void LO_EXPORT(nativePutTile)(JNIEnv* env, jobject, jlong handle, jint x, jint y, jint width, jint height,
                                        jfloatArray pixels) {
    Image& image = *job(handle)->image;
    const int w = std::min<int>(width, image.width() - x), h = std::min<int>(height, image.height() - y);
    if (w <= 0 || h <= 0) return;
    for (int row = 0; row < h; ++row)
        env->GetFloatArrayRegion(pixels, row * width * 4, w * 4, image.row(y + row) + static_cast<std::size_t>(x) * 4);
}

// Resize (when the size changes), sharpen (amount > 0) and watermark (mark != null), in place.
JNIEXPORT jstring LO_EXPORT(nativeProcess)(JNIEnv* env, jobject, jlong handle, jint outWidth, jint outHeight, jfloat sharpen,
                                           jbyteArray mark, jint markWidth, jint markHeight, jint markX, jint markY, jfloat opacity) {
    ExportJob& j = *job(handle);
    std::string failure;
    if (outWidth != j.image->width() || outHeight != j.image->height()) {
        auto tmp = Image::create(j.dir, outWidth, j.image->height(), &failure);
        auto out = tmp ? Image::create(j.dir, outWidth, outHeight, &failure) : nullptr;
        if (!out) return error(env, failure);
        lo::output::resize(j.image->data(), j.image->width(), j.image->height(), tmp->data(), out->data(), outWidth, outHeight);
        j.image = std::move(out);
    }
    if (sharpen > 0.f) lo::output::sharpen(j.image->data(), j.image->width(), j.image->height(), sharpen);
    if (mark != nullptr) {
        const std::vector<std::uint8_t> bytes = bytesOf(env, mark);
        if (bytes.size() != static_cast<std::size_t>(markWidth) * markHeight * 4) return error(env, "bad watermark size");
        lo::output::watermark(j.image->data(), j.image->width(), j.image->height(), bytes.data(), markWidth, markHeight, markX, markY,
                              opacity);
    }
    return nullptr;
}

// The image as 8-bit codes of `space` into an RGBA_8888 Bitmap of the same size (opaque).
JNIEXPORT jboolean LO_EXPORT(nativeFillBitmap)(JNIEnv* env, jobject, jlong handle, jobject bitmap, jint space) {
    const Image& image = *job(handle)->image;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || static_cast<int>(info.width) != image.width() ||
        static_cast<int>(info.height) != image.height())
        return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    for (int y = 0; y < image.height(); ++y)
        lo::output::convertRow8(image.row(y), image.width(), spaceOf(space), 4,
                                static_cast<std::uint8_t*>(pixels) + static_cast<std::size_t>(y) * info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

// Ultra HDR gain map rebuilt from the render: [width, height (little-endian int32),
// ratioMax (float32 bits)] then width x height codes; null when the image has no HDR.
JNIEXPORT jbyteArray LO_EXPORT(nativeRebuildGainmap)(JNIEnv* env, jobject, jlong handle, jint space, jint factor) {
    const Image& image = *job(handle)->image;
    std::vector<std::uint8_t> codes;
    int w = 0, h = 0;
    const float ratio = lo::output::rebuildGainmap(image.data(), image.width(), image.height(), spaceOf(space), std::max(1, factor), &codes, &w, &h);
    if (ratio <= 0.f) return nullptr;
    std::vector<std::uint8_t> out(12 + codes.size());
    std::memcpy(out.data(), &w, 4);
    std::memcpy(out.data() + 4, &h, 4);
    std::memcpy(out.data() + 8, &ratio, 4);
    std::memcpy(out.data() + 12, codes.data(), codes.size());
    jbyteArray array = env->NewByteArray(static_cast<jsize>(out.size()));
    if (array != nullptr) env->SetByteArrayRegion(array, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return array;
}

JNIEXPORT jbyteArray LO_EXPORT(nativeIcc)(JNIEnv* env, jobject, jint space) {
    const std::vector<std::uint8_t> icc = lo::output::iccProfile(spaceOf(space));
    jbyteArray array = env->NewByteArray(static_cast<jsize>(icc.size()));
    if (array != nullptr) env->SetByteArrayRegion(array, 0, static_cast<jsize>(icc.size()), reinterpret_cast<const jbyte*>(icc.data()));
    return array;
}

// PNG 8 / 16-bit into the file descriptor; null on success, else why it failed.
JNIEXPORT jstring LO_EXPORT(nativeWritePng)(JNIEnv* env, jobject, jlong handle, jint fd, jint bits, jint space, jbyteArray exif) {
    const Image& image = *job(handle)->image;
    std::FILE* f = openFd(fd);
    if (f == nullptr) return error(env, "cannot open the destination");
    std::string failure;
    const bool ok = lo::output::writePng(f, image.width(), image.height(), bits, lo::output::iccProfile(spaceOf(space)), bytesOf(env, exif),
                                         rows(image, spaceOf(space), bits), &failure);
    if (std::fclose(f) != 0 && ok) return error(env, "cannot finish the file");
    return ok ? nullptr : error(env, failure);
}

// TIFF 8 / 16-bit; text = [make, model, software, dateTime, artist, copyright].
JNIEXPORT jstring LO_EXPORT(nativeWriteTiff)(JNIEnv* env, jobject, jlong handle, jint fd, jint bits, jint space, jobjectArray text) {
    const Image& image = *job(handle)->image;
    lo::output::TiffText t;
    std::string* fields[6] = {&t.make, &t.model, &t.software, &t.dateTime, &t.artist, &t.copyright};
    for (jsize i = 0; i < std::min<jsize>(6, env->GetArrayLength(text)); ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(text, i));
        if (s == nullptr) continue;
        const char* c = env->GetStringUTFChars(s, nullptr);
        *fields[i] = c;
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    std::FILE* f = openFd(fd);
    if (f == nullptr) return error(env, "cannot open the destination");
    std::string failure;
    const bool ok = lo::output::writeTiff(f, image.width(), image.height(), bits, lo::output::iccProfile(spaceOf(space)), t,
                                          rows(image, spaceOf(space), bits), &failure);
    if (std::fclose(f) != 0 && ok) return error(env, "cannot finish the file");
    return ok ? nullptr : error(env, failure);
}

}  // extern "C"
