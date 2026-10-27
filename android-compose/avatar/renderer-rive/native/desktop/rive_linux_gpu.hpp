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
#include <string_view>
#include <utility>

// EGL discovery stays separate from context ownership: extension queries are lazy so a
// successful hardware candidate never probes or initializes the remaining displays.
struct EglDisplays
{
    PFNEGLGETPLATFORMDISPLAYEXTPROC platformDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(
        eglGetProcAddress("eglGetPlatformDisplayEXT"));
    PFNEGLQUERYDEVICESEXTPROC queryDevices = reinterpret_cast<PFNEGLQUERYDEVICESEXTPROC>(
        eglGetProcAddress("eglQueryDevicesEXT"));
    PFNEGLQUERYDEVICESTRINGEXTPROC deviceString = reinterpret_cast<PFNEGLQUERYDEVICESTRINGEXTPROC>(
        eglGetProcAddress("eglQueryDeviceStringEXT"));
    EGLDeviceEXT devices[16];
    EGLint count = 0;

    bool enumerate()
    {
        if (!platformDisplay || !queryDevices) return false;
        return queryDevices(16, devices, &count);
    }

    bool isSoftware(EGLDeviceEXT device) const
    {
        const char* extensions = deviceString ? deviceString(device, EGL_EXTENSIONS) : nullptr;
        return extensions && std::strstr(extensions, "EGL_MESA_device_software");
    }
};

struct EglCandidate
{
    EGLenum platform;
    void* device;
    std::string label;
};

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

    static std::string joinShaderSource(GLsizei count, const GLchar* const* strings, const GLint* lengths)
    {
        std::string source;
        for (GLsizei i = 0; i < count; ++i)
        {
            const size_t size = lengths && lengths[i] >= 0 ? lengths[i] : std::strlen(strings[i]);
            source.append(strings[i], size);
        }
        return source;
    }

    static std::string separateShaderTokens(const std::string& source)
    {
        // ASCII spelling is intentional: GLSL identifiers must not depend on the process locale.
        constexpr std::string_view identifierChars =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_";
        std::string separated;
        char previous = '\0';
        for (char ch : source)
        {
            if (previous == ')' && identifierChars.find(ch) != std::string_view::npos) separated += ' ';
            separated += ch;
            previous = ch;
        }
        return separated;
    }

    static void shaderSource(GLuint shader, GLsizei count, const GLchar* const* strings, const GLint* lengths)
    {
        // The pinned minifier emits OUT(vec2)value. Mesa rejects the resulting joined tokens;
        // a separator after ')' preserves the intended "out vec2 value" on every GL driver.
        // Join first so a source-string boundary cannot hide the same spelling.
        const auto source = separateShaderTokens(joinShaderSource(count, strings, lengths));
        const char* text = source.c_str();
        auto rawShaderSource = reinterpret_cast<PFNGLSHADERSOURCEPROC>(eglGetProcAddress("glShaderSource"));
        rawShaderSource(shader, 1, &text, nullptr);
    }

    const char* createContext(EGLConfig config)
    {
        const EGLint contextAttrs[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 2,
            EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttrs);
        if (context == EGL_NO_CONTEXT) return "GL 4.2 core context unavailable";
        const EGLint surfaceAttrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface = eglCreatePbufferSurface(display, config, surfaceAttrs);
        if (surface == EGL_NO_SURFACE) return "1x1 pbuffer unavailable";
        return eglMakeCurrent(display, surface, surface, context) ? nullptr : "makeCurrent failed";
    }

    const char* initializeEgl()
    {
        if (display == EGL_NO_DISPLAY) return "display unavailable";
        if (!eglInitialize(display, nullptr, nullptr)) return "initialize failed";
        if (!eglBindAPI(EGL_OPENGL_API)) return "bind OpenGL failed";
        const EGLint configAttrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLConfig config;
        EGLint count = 0;
        if (!eglChooseConfig(display, configAttrs, &config, 1, &count) || count == 0)
            return "RGBA8 config unavailable";
        return createContext(config);
    }

    // A candidate owns the entire sequence. Returning any reason destroys it before Make
    // tries another display, including failures after eglInitialize or after GL becomes current.
    const char* initialize()
    {
        if (const char* reason = initializeEgl()) return reason;
        if (!gladLoadCustomLoader(reinterpret_cast<GLADloadfunc>(eglGetProcAddress)))
            return "GL entry points unavailable";
        glad_glShaderSource = shaderSource;
        const auto* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
        if (!renderer) return "GL_RENDERER unavailable";
        adapterName = std::string("EGL/OpenGL: ") + renderer;
        renderContext = rive::gpu::RenderContextGLImpl::MakeContext();
        if (!renderContext) return "Rive GL context rejected";
        return glGetError() == GL_NO_ERROR ? nullptr : "Rive GL setup error";
    }

    static std::unique_ptr<SharedGpu> fromDisplay(PFNEGLGETPLATFORMDISPLAYEXTPROC platformDisplay,
                                                 const EglCandidate& candidate, std::string& error)
    {
        auto gpu = std::make_unique<SharedGpu>();
        gpu->display = platformDisplay(candidate.platform, candidate.device, nullptr);
        if (const char* reason = gpu->initialize())
        {
            error += "; " + candidate.label + ": " + reason;
            return nullptr; // RAII cleans up before the next candidate.
        }
        gpu->adapterName += " [" + candidate.label + "]";
        error.clear();
        return gpu;
    }

    static std::unique_ptr<SharedGpu> fromDevices(EglDisplays& displays, std::string& error)
    {
        if (!displays.enumerate()) return nullptr;
        // Device displays avoid Xlib entirely, including its process-wide error handlers.
        // Mesa may enumerate a software device before a real GPU. Prefer hardware explicitly.
        for (int pass = 0; pass < 2; ++pass)
            for (EGLint i = 0; i < displays.count; ++i)
            {
                const bool software = displays.isSoftware(displays.devices[i]);
                if (software != (pass == 1)) continue;
                const auto label = "device" + std::to_string(i) + (software ? " software" : " hardware");
                if (auto gpu = fromDisplay(displays.platformDisplay,
                                           {EGL_PLATFORM_DEVICE_EXT, displays.devices[i], label}, error)) return gpu;
            }
        return nullptr;
    }

    static std::unique_ptr<SharedGpu> Make(std::string& error)
    {
        error = "Linux Rive: GPU setup unavailable";
        EglDisplays displays;
        if (auto gpu = fromDevices(displays, error)) return gpu;
        // Mesa surfaceless is tried last, even if a device initialized but failed a later step.
        if (displays.platformDisplay)
        {
            if (auto gpu = fromDisplay(displays.platformDisplay,
                                       {EGL_PLATFORM_SURFACELESS_MESA, nullptr, "surfaceless Mesa"}, error)) return gpu;
        }
        else error += "; EGL platform entry point unavailable";
        // Also release EGL thread state when discovery itself found no usable candidates.
        eglReleaseThread();
        return nullptr;
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
        if (renderTarget && std::make_pair(width, height) == std::make_pair(w, h)) return true;
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
