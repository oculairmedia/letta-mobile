// letta-mobile-0s5bi spike: native Rive for Compose Desktop, no webview.
//
// A plain C ABI over rive-runtime + the Rive Renderer's D3D11 backend. Each frame the scene is
// drawn into an offscreen texture and copied back into a caller-owned RGBA buffer, which the JVM
// side wraps as a Skia image. The GPU renderer is the point: feathering (soft glow, blur) only
// exists there - Skia- and Canvas2D-backed Rive renderers silently drop it.
//
// Threading: a D3D11 immediate context is single-threaded, so every call into this DLL must come
// from one thread (the JVM's Rive thread). The device, the render context and each parsed file are
// shared by every scene in the process - a scene is an artboard instance, which Rive makes in
// microseconds, not a device of its own, which took hundreds of milliseconds per mascot.

#include "rive/animation/state_machine_instance.hpp"
#include "rive/artboard.hpp"
#include "rive/file.hpp"
#include "rive/layout.hpp"
#include "rive/math/aabb.hpp"
#include "rive/renderer.hpp"
#include "rive/renderer/texture.hpp" // render_context_d3d_impl.hpp needs it complete, but does not include it
#include "rive/renderer/d3d11/render_context_d3d_impl.hpp"
#include "rive/renderer/render_context.hpp"
#include "rive/renderer/rive_renderer.hpp"
#include "rive/animation/state_machine_input_instance.hpp"
#include "rive/viewmodel/viewmodel_instance.hpp"
#include "rive/viewmodel/viewmodel_instance_boolean.hpp"
#include "rive/viewmodel/viewmodel_instance_color.hpp"
#include "rive/viewmodel/viewmodel_instance_enum.hpp"
#include "rive/viewmodel/viewmodel_instance_number.hpp"
#include "rive/viewmodel/viewmodel_instance_trigger.hpp"

#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <dxgi1_2.h>

using namespace rive;
using namespace rive::gpu;

// The process's one GPU: device, immediate context and Rive render context (whose shaders compile
// once). Created on first use, kept for the life of the process; every scene renders through it.
struct SharedGpu
{
    ComPtr<ID3D11Device> gpu;
    ComPtr<ID3D11DeviceContext> gpuContext;
    std::unique_ptr<RenderContext> renderContext;
    std::string adapterName;
};

// A parsed .riv, shared by every scene loaded from the same bytes: parsing is per file, instancing
// an artboard from it is cheap. Keyed by content hash and length; the mascot is one file, so this
// stays tiny.
struct CachedFile
{
    uint64_t hash;
    int length;
    rcp<File> file;
};

struct RiveBridge
{
    SharedGpu* shared = nullptr;
    rcp<RenderTargetD3D> renderTarget;
    ComPtr<ID3D11Texture2D> drawTexture;
    ComPtr<ID3D11Texture2D> readbackTexture;
    uint32_t width = 0;
    uint32_t height = 0;

    rcp<File> file;
    std::unique_ptr<ArtboardInstance> artboard;
    std::unique_ptr<StateMachineInstance> stateMachine;
    rcp<ViewModelInstance> viewModel;
    Mat2D viewTransform;
};

// --- Shared GPU + file cache ---------------------------------------------------------------------

static SharedGpu* g_sharedGpu = nullptr;
static std::vector<CachedFile> g_files;

static SharedGpu* shared_gpu()
{
    if (g_sharedGpu)
        return g_sharedGpu;
    ComPtr<IDXGIFactory2> factory;
    if (FAILED(CreateDXGIFactory(__uuidof(IDXGIFactory2),
                                 reinterpret_cast<void**>(factory.ReleaseAndGetAddressOf()))))
        return nullptr;

    ComPtr<IDXGIAdapter> adapter;
    DXGI_ADAPTER_DESC desc{};
    D3DContextOptions options;
    if (factory->EnumAdapters(0, adapter.ReleaseAndGetAddressOf()) != DXGI_ERROR_NOT_FOUND)
    {
        adapter->GetDesc(&desc);
        options.isIntel = desc.VendorId == 0x163C || desc.VendorId == 0x8086 ||
                          desc.VendorId == 0x8087;
    }

    auto shared = std::make_unique<SharedGpu>();
    char name[256] = {0};
    WideCharToMultiByte(CP_UTF8, 0, desc.Description, -1, name, sizeof(name), nullptr, nullptr);
    shared->adapterName = name;

    D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1};
    if (FAILED(D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, levels, 1,
                                 D3D11_SDK_VERSION, shared->gpu.ReleaseAndGetAddressOf(), nullptr,
                                 shared->gpuContext.ReleaseAndGetAddressOf())))
        return nullptr;

    shared->renderContext = RenderContextD3DImpl::MakeContext(shared->gpu, shared->gpuContext, options);
    if (!shared->renderContext)
        return nullptr;
    g_sharedGpu = shared.release();
    return g_sharedGpu;
}

static uint64_t fnv1a(const uint8_t* bytes, int length)
{
    uint64_t hash = 1469598103934665603ull;
    for (int i = 0; i < length; ++i)
    {
        hash ^= bytes[i];
        hash *= 1099511628211ull;
    }
    return hash;
}

// The parsed file for these bytes: cached from the first load, so the second mascot (and the
// fortieth) costs no parse. A parse failure is not cached.
static rcp<File> cached_file(SharedGpu* shared, const uint8_t* bytes, int length)
{
    const uint64_t hash = fnv1a(bytes, length);
    for (const auto& entry : g_files)
    {
        if (entry.hash == hash && entry.length == length)
            return entry.file;
    }
    ImportResult result;
    rcp<File> file = File::import(Span<const uint8_t>(bytes, length), shared->renderContext.get(), &result);
    if (file)
        g_files.push_back({hash, length, file});
    return file;
}

// --- Load helpers --------------------------------------------------------------------------------

// Picks the artboard: `artboardName` null/empty takes the file's default. Returns 0, 4 when a named
// artboard does not exist, or 2 when the file has no default artboard.
static int pick_artboard(RiveBridge* bridge, const char* artboardName)
{
    const bool named = artboardName && *artboardName;
    bridge->artboard = named ? bridge->file->artboardNamed(std::string(artboardName)) : bridge->file->artboardDefault();
    if (bridge->artboard)
        return 0;
    return named ? 4 : 2;
}

static std::unique_ptr<StateMachineInstance> state_machine_named(ArtboardInstance* artboard, const char* name)
{
    if (!name || !*name)
        return nullptr;
    for (size_t i = 0; i < artboard->stateMachineCount(); ++i)
    {
        auto candidate = artboard->stateMachineAt(i);
        if (candidate && candidate->name() == name)
            return candidate;
    }
    return nullptr;
}

// The named state machine, else the artboard's default, else its first. Returns 0, or 3 when the
// artboard has none.
static int pick_state_machine(RiveBridge* bridge, const char* stateMachineName)
{
    ArtboardInstance* artboard = bridge->artboard.get();
    bridge->stateMachine = state_machine_named(artboard, stateMachineName);
    if (!bridge->stateMachine)
        bridge->stateMachine = artboard->defaultStateMachine();
    if (!bridge->stateMachine && artboard->stateMachineCount() > 0)
        bridge->stateMachine = artboard->stateMachineAt(0);
    return bridge->stateMachine ? 0 : 3;
}

// The artboard's view model, as its authored DEFAULT instance: that is what carries the authored
// colour and enum values. `createViewModelInstance(artboard)` hands back a blank instance, which is
// why the body drew black until the host wrote a colour. A file with no view model still drives
// through state machine inputs.
static void bind_view_model(RiveBridge* bridge)
{
    bridge->viewModel = bridge->file->createDefaultViewModelInstance(bridge->artboard.get());
    if (!bridge->viewModel)
        bridge->viewModel = bridge->file->createViewModelInstance(bridge->artboard.get());
    if (!bridge->viewModel)
        return;
    bridge->artboard->bindViewModelInstance(bridge->viewModel);
    bridge->stateMachine->bindViewModelInstance(bridge->viewModel);
}

// --- View model helpers --------------------------------------------------------------------------

// The named view-model property as a T, or null when there is no view model, no such property, or
// the property is of another type.
template <typename T> static T* vm_property(RiveBridge* bridge, const char* name)
{
    auto* property = bridge->viewModel ? bridge->viewModel->propertyValue(std::string(name)) : nullptr;
    return property && property->is<T>() ? property->as<T>() : nullptr;
}

// Runs `apply` on the named property when it exists as a T and returns its result; 1 otherwise.
template <typename T, typename Apply> static int with_vm_property(RiveBridge* bridge, const char* name, Apply apply)
{
    T* property = vm_property<T>(bridge, name);
    return property ? apply(property) : 1;
}

// Newline-joined names of every number property on the bound view model; returns how many.
static int join_number_names(RiveBridge* bridge, std::string& joined)
{
    if (!bridge->viewModel)
        return 0;
    int count = 0;
    for (const auto& value : bridge->viewModel->propertyValues())
    {
        if (!value || !value->is<ViewModelInstanceNumber>())
            continue;
        if (!joined.empty())
            joined.push_back('\n');
        joined += value->name();
        ++count;
    }
    return count;
}

extern "C" {

// A scene: cheap, because the device and render context are the process's shared ones (brought
// up by the first call) and the file comes from the parse cache at load.
__declspec(dllexport) RiveBridge* rive_bridge_create(char* adapterNameOut, int adapterNameCap)
{
    SharedGpu* shared = shared_gpu();
    if (!shared)
        return nullptr;
    if (adapterNameOut && adapterNameCap > 0)
    {
        std::strncpy(adapterNameOut, shared->adapterName.c_str(), (size_t)adapterNameCap - 1);
        adapterNameOut[adapterNameCap - 1] = 0;
    }
    auto bridge = std::make_unique<RiveBridge>();
    bridge->shared = shared;
    return bridge.release();
}

__declspec(dllexport) void rive_bridge_destroy(RiveBridge* bridge)
{
    delete bridge;
}

// Returns 0 on success. `artboardName` null/empty picks the file's default artboard; a name that
// no artboard carries is an error (4) rather than a silent fall back to the default, so the bench
// cannot believe it is looking at `Harness` while showing `Mascot`.
__declspec(dllexport) int rive_bridge_load_artboard(RiveBridge* bridge, const uint8_t* bytes, int length,
                                                    const char* stateMachineName,
                                                    const char* artboardName)
{
    bridge->stateMachine.reset();
    bridge->artboard.reset();
    bridge->viewModel = nullptr;
    bridge->file = cached_file(bridge->shared, bytes, length);
    if (!bridge->file)
        return 1;
    if (int code = pick_artboard(bridge, artboardName))
        return code;
    if (int code = pick_state_machine(bridge, stateMachineName))
        return code;
    bind_view_model(bridge);
    bridge->stateMachine->advanceAndApply(0);
    return 0;
}

// The original entry point: the file's default artboard.
__declspec(dllexport) int rive_bridge_load(RiveBridge* bridge, const uint8_t* bytes, int length,
                                           const char* stateMachineName)
{
    return rive_bridge_load_artboard(bridge, bytes, length, stateMachineName, nullptr);
}

__declspec(dllexport) float rive_bridge_artboard_width(RiveBridge* bridge)
{
    return bridge->artboard ? bridge->artboard->bounds().width() : 0;
}

__declspec(dllexport) float rive_bridge_artboard_height(RiveBridge* bridge)
{
    return bridge->artboard ? bridge->artboard->bounds().height() : 0;
}

__declspec(dllexport) void rive_bridge_advance(RiveBridge* bridge, float seconds)
{
    if (bridge->stateMachine)
        bridge->stateMachine->advanceAndApply(seconds);
}

static bool create_offscreen_textures(RiveBridge* bridge, D3D11_TEXTURE2D_DESC desc)
{
    ID3D11Device* gpu = bridge->shared->gpu.Get();
    if (FAILED(gpu->CreateTexture2D(&desc, nullptr, bridge->drawTexture.ReleaseAndGetAddressOf())))
        return false;
    desc.Usage = D3D11_USAGE_STAGING;
    desc.BindFlags = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    if (FAILED(gpu->CreateTexture2D(&desc, nullptr, bridge->readbackTexture.ReleaseAndGetAddressOf())))
        return false;
    return bridge->drawTexture && bridge->readbackTexture;
}

static bool target_size_matches(RiveBridge* bridge, uint32_t width, uint32_t height)
{
    return bridge->width == width && bridge->height == height;
}

static bool target_textures_ready(RiveBridge* bridge)
{
    return bridge->drawTexture && bridge->readbackTexture;
}

static bool target_ready(RiveBridge* bridge, uint32_t width, uint32_t height)
{
    return target_size_matches(bridge, width, height) && target_textures_ready(bridge);
}

static bool ensure_target(RiveBridge* bridge, uint32_t width, uint32_t height)
{
    if (target_ready(bridge, width, height))
        return true;
    D3D11_TEXTURE2D_DESC desc{};
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Width = width;
    desc.Height = height;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET;
    if (!create_offscreen_textures(bridge, desc))
        return false;

    bridge->renderTarget =
        bridge->shared->renderContext->static_impl_cast<RenderContextD3DImpl>()->makeRenderTarget(width, height);
    bridge->width = width;
    bridge->height = height;
    return true;
}

// Draws the current scene and copies it into `rgbaOut` (width * height * 4 bytes, premultiplied
// RGBA, top row first). `clearArgb` 0 keeps the background transparent. Returns 0 on success.
__declspec(dllexport) int rive_bridge_render(RiveBridge* bridge, int width, int height, uint32_t clearArgb,
                                             uint8_t* rgbaOut)
{
    if (!bridge->artboard || width <= 0 || height <= 0)
        return 1;
    if (!ensure_target(bridge, (uint32_t)width, (uint32_t)height))
        return 3;

    RenderContext* renderContext = bridge->shared->renderContext.get();
    ID3D11DeviceContext* gpuContext = bridge->shared->gpuContext.Get();
    renderContext->beginFrame({
        .renderTargetWidth = (uint32_t)width,
        .renderTargetHeight = (uint32_t)height,
        .loadAction = LoadAction::clear,
        .clearColor = clearArgb,
    });

    RiveRenderer renderer(renderContext);
    bridge->viewTransform = computeAlignment(Fit::contain, Alignment::center,
                                             AABB(0, 0, (float)width, (float)height),
                                             bridge->artboard->bounds());
    renderer.save();
    renderer.transform(bridge->viewTransform);
    if (bridge->stateMachine)
        bridge->stateMachine->draw(&renderer);
    else
        bridge->artboard->draw(&renderer);
    renderer.restore();

    bridge->renderTarget->setTargetTexture(bridge->drawTexture);
    renderContext->flush({.renderTarget = bridge->renderTarget.get()});
    bridge->renderTarget->setTargetTexture(nullptr);

    gpuContext->CopyResource(bridge->readbackTexture.Get(), bridge->drawTexture.Get());
    D3D11_MAPPED_SUBRESOURCE map;
    if (FAILED(gpuContext->Map(bridge->readbackTexture.Get(), 0, D3D11_MAP_READ, 0, &map)))
        return 2;
    for (int y = 0; y < height; ++y)
    {
        std::memcpy(rgbaOut + (size_t)y * width * 4,
                    static_cast<const uint8_t*>(map.pData) + (size_t)y * map.RowPitch, (size_t)width * 4);
    }
    gpuContext->Unmap(bridge->readbackTexture.Get(), 0);
    return 0;
}

// Pointer events arrive in surface pixels; the state machine hit-tests in artboard space.
static Vec2D to_artboard(RiveBridge* bridge, float x, float y)
{
    Mat2D inverse;
    if (!bridge->viewTransform.invert(&inverse))
        return Vec2D(x, y);
    return inverse * Vec2D(x, y);
}

__declspec(dllexport) void rive_bridge_pointer(RiveBridge* bridge, int kind, float x, float y)
{
    if (!bridge->stateMachine)
        return;
    Vec2D p = to_artboard(bridge, x, y);
    switch (kind)
    {
        case 0: bridge->stateMachine->pointerMove(p); break;
        case 1: bridge->stateMachine->pointerDown(p); break;
        case 2: bridge->stateMachine->pointerUp(p); break;
        default: bridge->stateMachine->pointerExit(p); break;
    }
}

// --- State machine inputs. Return 0 when the named input exists. -------------------------------

__declspec(dllexport) int rive_bridge_fire_trigger(RiveBridge* bridge, const char* name)
{
    auto* input = bridge->stateMachine ? bridge->stateMachine->getTrigger(name) : nullptr;
    if (!input)
        return 1;
    input->fire();
    return 0;
}

__declspec(dllexport) int rive_bridge_set_number(RiveBridge* bridge, const char* name, float value)
{
    auto* input = bridge->stateMachine ? bridge->stateMachine->getNumber(name) : nullptr;
    if (!input)
        return 1;
    input->value(value);
    return 0;
}

// --- View model properties: the RiveAvatarContract path. ---------------------------------------

__declspec(dllexport) int rive_bridge_vm_set_number(RiveBridge* bridge, const char* name, float value)
{
    return with_vm_property<ViewModelInstanceNumber>(bridge, name, [&](ViewModelInstanceNumber* number) {
        number->propertyValue(value);
        return 0;
    });
}

// Reads a view-model number back. This is the probe path: with `--probe`'s two-way data binds in
// the file, a node property's real post-state-machine value arrives here every frame. Returns 0 and
// writes `*out` when the property exists and is a number.
__declspec(dllexport) int rive_bridge_vm_get_number(RiveBridge* bridge, const char* name, float* out)
{
    if (!out)
        return 3;
    return with_vm_property<ViewModelInstanceNumber>(bridge, name, [&](ViewModelInstanceNumber* number) {
        *out = number->propertyValue();
        return 0;
    });
}

// Newline-separated names of every number property on the bound view model, so the bench can find
// the `telemetry*` probes without being told what they are. Returns the number of names written, or
// -1 when the buffer is too small (nothing is written then).
__declspec(dllexport) int rive_bridge_vm_number_names(RiveBridge* bridge, char* out, int cap)
{
    if (!out || cap <= 0)
        return -1;
    std::string joined;
    const int count = join_number_names(bridge, joined);
    if ((int)joined.size() + 1 > cap)
        return -1;
    std::memcpy(out, joined.c_str(), joined.size() + 1);
    return count;
}

__declspec(dllexport) int rive_bridge_vm_set_enum(RiveBridge* bridge, const char* name, const char* key)
{
    return with_vm_property<ViewModelInstanceEnum>(bridge, name, [&](ViewModelInstanceEnum* enumeration) {
        return enumeration->value(std::string(key)) ? 0 : 2;
    });
}

__declspec(dllexport) int rive_bridge_vm_set_color(RiveBridge* bridge, const char* name, int argb)
{
    return with_vm_property<ViewModelInstanceColor>(bridge, name, [&](ViewModelInstanceColor* color) {
        color->propertyValue(argb);
        return 0;
    });
}

__declspec(dllexport) int rive_bridge_vm_fire(RiveBridge* bridge, const char* name)
{
    return with_vm_property<ViewModelInstanceTrigger>(bridge, name, [](ViewModelInstanceTrigger* trigger) {
        trigger->trigger();
        return 0;
    });
}

__declspec(dllexport) int rive_bridge_vm_set_boolean(RiveBridge* bridge, const char* name, int value)
{
    return with_vm_property<ViewModelInstanceBoolean>(bridge, name, [&](ViewModelInstanceBoolean* boolean) {
        boolean->propertyValue(value != 0);
        return 0;
    });
}

} // extern "C"
