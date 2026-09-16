# letta-mobile-0s5bi spike: builds rive_desktop_bridge.dll against a rive-runtime checkout that has
# already been built with `build_rive.sh --toolset=msc release` (see README.md).
#
# The compile flags mirror rive-runtime's generated projects on purpose: static CRT (/MT),
# exceptions off, and the same preprocessor defines. A mismatch in any define that changes a class
# layout compiles cleanly and then corrupts memory at runtime.
param(
    [Parameter(Mandatory = $true)][string]$RiveRuntime,
    [string]$OutDir = "$PSScriptRoot\build"
)
$ErrorActionPreference = 'Stop'

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { $vs = "C:\Program Files\Microsoft Visual Studio\2022\Community" }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'

$lib = Join-Path $RiveRuntime 'renderer\out\release'
New-Item -ItemType Directory -Force $OutDir | Out-Null

$defines = @(
    'RIVE_DESKTOP_GL', 'WITH_RIVE_TEXT', 'WITH_RIVE_LAYOUT', 'RELEASE', 'NDEBUG', '_USE_MATH_DEFINES',
    'NOMINMAX', '_CRT_SECURE_NO_WARNINGS', '_SILENCE_CXX20_IS_POD_DEPRECATION_WARNING',
    '_SILENCE_ALL_CXX20_DEPRECATION_WARNINGS', 'RIVE_WINDOWS', 'YOGA_EXPORT=', '_HAS_EXCEPTIONS=0'
) | ForEach-Object { "/D$_" }

$includes = @(
    (Join-Path $RiveRuntime 'include'),
    (Join-Path $RiveRuntime 'renderer\include')
) | ForEach-Object { "/I`"$_`"" }

$libs = @(
    'rive_pls_renderer.lib', 'rive.lib', 'rive_decoders.lib', 'rive_harfbuzz.lib', 'rive_sheenbidi.lib',
    'rive_yoga.lib', 'libpng.lib', 'zlib.lib', 'libjpeg.lib', 'libwebp.lib',
    'd3d11.lib', 'd3d12.lib', 'dxgi.lib', 'dxguid.lib', 'd3dcompiler.lib', 'opengl32.lib', 'user32.lib', 'gdi32.lib'
)

$src = Join-Path $PSScriptRoot 'rive_desktop_bridge.cpp'
$cl = "cl /nologo /LD /MT /O2 /std:c++latest /EHs-c- /Zc:__cplusplus $($defines -join ' ') $($includes -join ' ') `"$src`" /Fo`"$OutDir\\`" /Fe`"$OutDir\rive_desktop_bridge.dll`" /link /LIBPATH:`"$lib`" $($libs -join ' ')"

# vcvars64.bat locates the toolset through vswhere, which is not on PATH by default.
$env:PATH = "$(Split-Path $vswhere);$env:PATH"
cmd /c "`"$vcvars`" >nul && $cl"
if ($LASTEXITCODE -ne 0) { throw "cl failed with exit code $LASTEXITCODE" }
Write-Host "Built $OutDir\rive_desktop_bridge.dll"
