// JNI entry points for dev.mark.lineover.core.engine.LoEngine.
#include <jni.h>

#include <array>
#include <string>

#include "gl_smoke.h"
#include "lineover/engine.h"

namespace {

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) {
        env->ThrowNew(exception, message.c_str());
    }
}

}  // namespace

extern "C" {

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
