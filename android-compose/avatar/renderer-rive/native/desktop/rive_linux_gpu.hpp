// Private Linux binding for the pinned Rive GPU renderer. All ownership stays on rive-render.
#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include "rive/renderer/gl/render_context_gl_impl.hpp"
#include "rive/renderer/gl/render_target_gl.hpp"
#include <algorithm>
#include <cstring>
#include <memory>
#include <string>

struct SharedGpu
{
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    std::unique_ptr<rive::gpu::RenderContext> renderContext;
    std::string adapterName;

    ~SharedGpu()
    {
        // Rive deletes GL resources in its destructor, before we release our private EGL context.
        renderContext.reset();
        if (display != EGL_NO_DISPLAY)
        {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            eglTerminate(display);
        }
        // EGL also owns thread-local dispatch state, including on failed initialization. Release
        // it before JNA can unload this library or the JVM render thread exits.
        eglReleaseThread();
    }

    static std::unique_ptr<SharedGpu> Make(std::string& error)
    {
        auto gpu = std::make_unique<SharedGpu>();
        auto platformDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(
            eglGetProcAddress("eglGetPlatformDisplayEXT"));
        auto queryDevices = reinterpret_cast<PFNEGLQUERYDEVICESEXTPROC>(
            eglGetProcAddress("eglQueryDevicesEXT"));
        auto deviceString = reinterpret_cast<PFNEGLQUERYDEVICESTRINGEXTPROC>(
            eglGetProcAddress("eglQueryDeviceStringEXT"));
        // Device displays avoid Xlib entirely, including its process-wide error handlers.
        EGLDeviceEXT devices[16];
        EGLint count = 0;
        if (platformDisplay && queryDevices && queryDevices(16, devices, &count))
        {
            // Mesa may enumerate a software device before a real GPU. Prefer hardware explicitly.
            for (int pass = 0; pass < 2 && gpu->display == EGL_NO_DISPLAY; ++pass)
                for (EGLint i = 0; i < count; ++i)
                {
                    const char* extensions = deviceString ? deviceString(devices[i], EGL_EXTENSIONS) : nullptr;
                    const bool software = extensions && std::strstr(extensions, "EGL_MESA_device_software");
                    if (software != (pass == 1)) continue;
                    EGLDisplay candidate = platformDisplay(EGL_PLATFORM_DEVICE_EXT, devices[i], nullptr);
                    if (candidate != EGL_NO_DISPLAY && eglInitialize(candidate, nullptr, nullptr))
                    {
                        gpu->display = candidate;
                        break;
                    }
                }
        }
        // Mesa also supports an independent surfaceless display (including explicit CI software GL).
        if (gpu->display == EGL_NO_DISPLAY && platformDisplay)
        {
            EGLDisplay candidate = platformDisplay(EGL_PLATFORM_SURFACELESS_MESA, nullptr, nullptr);
            if (candidate != EGL_NO_DISPLAY && eglInitialize(candidate, nullptr, nullptr))
                gpu->display = candidate;
        }
        if (gpu->display == EGL_NO_DISPLAY)
        {
            error = "Linux Rive: no EGL device/surfaceless display; GPU setup unavailable";
            return nullptr;
        }
        const EGLint configAttrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLConfig config;
        if (!eglBindAPI(EGL_OPENGL_API) ||
            !eglChooseConfig(gpu->display, configAttrs, &config, 1, &count) || count == 0)
        {
            error = "Linux Rive: EGL OpenGL RGBA8 configuration unavailable";
            return nullptr;
        }
        const EGLint contextAttrs[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 2,
            EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE};
        gpu->context = eglCreateContext(gpu->display, config, EGL_NO_CONTEXT, contextAttrs);
        const EGLint surfaceAttrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        gpu->surface = eglCreatePbufferSurface(gpu->display, config, surfaceAttrs);
        if (gpu->context == EGL_NO_CONTEXT || gpu->surface == EGL_NO_SURFACE ||
            !eglMakeCurrent(gpu->display, gpu->surface, gpu->surface, gpu->context))
        {
            error = "Linux Rive: private EGL OpenGL 4.2 context unavailable";
            return nullptr;
        }
        if (!gladLoadCustomLoader(reinterpret_cast<GLADloadfunc>(eglGetProcAddress)))
        {
            error = "Linux Rive: OpenGL entry points unavailable";
            return nullptr;
        }
        gpu->adapterName = std::string("EGL/OpenGL: ") +
            reinterpret_cast<const char*>(glGetString(GL_RENDERER));
        gpu->renderContext = rive::gpu::RenderContextGLImpl::MakeContext();
        if (!gpu->renderContext)
        {
            error = "Linux Rive: pinned Rive GPU renderer rejected " + gpu->adapterName;
            return nullptr;
        }
        error.clear();
        return gpu;
    }
};

struct LinuxTarget
{
    rive::rcp<rive::gpu::TextureRenderTargetGL> renderTarget;
    GLuint texture = 0;
    uint32_t width = 0, height = 0;

    ~LinuxTarget() { reset(); }

    void reset()
    {
        renderTarget = nullptr;
        if (texture) glDeleteTextures(1, &texture);
        texture = 0;
    }

    bool resize(uint32_t w, uint32_t h)
    {
        if (renderTarget && width == w && height == h) return true;
        reset();
        GLint maxSize = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
        if (w > static_cast<uint32_t>(maxSize) || h > static_cast<uint32_t>(maxSize)) return false;
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
        if (glGetError() != GL_NO_ERROR) { reset(); return false; }
        renderTarget = rive::make_rcp<rive::gpu::TextureRenderTargetGL>(w, h);
        renderTarget->setTargetTexture(texture);
        width = w;
        height = h;
        return true;
    }

    bool read(uint8_t* out)
    {
        renderTarget->bindDestinationFramebuffer(GL_READ_FRAMEBUFFER);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, out);
        if (glGetError() != GL_NO_ERROR) return false;
        // Rive's GL target is bottom-up; the existing JNA/Skia ABI is top-down premultiplied RGBA.
        const size_t stride = static_cast<size_t>(width) * 4;
        for (uint32_t y = 0; y < height / 2; ++y)
            std::swap_ranges(out + y * stride, out + (y + 1) * stride, out + (height - 1 - y) * stride);
        return true;
    }
};
