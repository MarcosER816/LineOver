#include "gl_smoke.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <cstdio>

namespace lo::android {
namespace {

constexpr EGLint kSurfaceSize = 16;
constexpr EGLint kOpenGlEs3Bit = 0x0040;  // EGL_OPENGL_ES3_BIT (EGL 1.5 / KHR_create_context)

std::string eglFailure(const char* step) {
    return std::string(step) + " failed, EGL error 0x" + [] {
        char buffer[16];
        std::snprintf(buffer, sizeof(buffer), "%04x", static_cast<unsigned>(eglGetError()));
        return std::string(buffer);
    }();
}

// Releases the EGL objects in reverse order of creation.
struct EglScope {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;

    ~EglScope() {
        if (display == EGL_NO_DISPLAY) return;
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        eglReleaseThread();
    }
};

}  // namespace

bool glClearAndReadPixel(float r, float g, float b, float a,
                         std::array<std::uint8_t, 4>* rgba, std::string* error) {
    EglScope egl;
    egl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl.display == EGL_NO_DISPLAY || !eglInitialize(egl.display, nullptr, nullptr)) {
        *error = eglFailure("eglInitialize");
        egl.display = EGL_NO_DISPLAY;
        return false;
    }

    const EGLint configAttributes[] = {
        EGL_RENDERABLE_TYPE, kOpenGlEs3Bit,
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (!eglChooseConfig(egl.display, configAttributes, &config, 1, &configCount) || configCount < 1) {
        *error = eglFailure("eglChooseConfig");
        return false;
    }

    const EGLint surfaceAttributes[] = {EGL_WIDTH, kSurfaceSize, EGL_HEIGHT, kSurfaceSize, EGL_NONE};
    egl.surface = eglCreatePbufferSurface(egl.display, config, surfaceAttributes);
    if (egl.surface == EGL_NO_SURFACE) {
        *error = eglFailure("eglCreatePbufferSurface");
        return false;
    }

    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    egl.context = eglCreateContext(egl.display, config, EGL_NO_CONTEXT, contextAttributes);
    if (egl.context == EGL_NO_CONTEXT) {
        *error = eglFailure("eglCreateContext");
        return false;
    }

    if (!eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context)) {
        *error = eglFailure("eglMakeCurrent");
        return false;
    }

    glClearColor(r, g, b, a);
    glClear(GL_COLOR_BUFFER_BIT);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(kSurfaceSize / 2, kSurfaceSize / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba->data());

    const GLenum glError = glGetError();
    if (glError != GL_NO_ERROR) {
        *error = "GL error 0x" + std::to_string(glError) + " after clear/readback";
        return false;
    }
    return true;
}

}  // namespace lo::android
