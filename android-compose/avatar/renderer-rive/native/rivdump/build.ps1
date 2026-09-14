# Builds rivdump.exe against a rive-runtime checkout built with `build_rive.sh --toolset=msc release`.
# Same defines/CRT as the desktop bridge (see ../desktop/build.ps1) - a mismatch corrupts memory.
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

$defines = @('RIVE_DESKTOP_GL', 'WITH_RIVE_TEXT', 'WITH_RIVE_LAYOUT', 'RELEASE', 'NDEBUG', '_USE_MATH_DEFINES',
    'NOMINMAX', '_CRT_SECURE_NO_WARNINGS', '_SILENCE_CXX20_IS_POD_DEPRECATION_WARNING',
    '_SILENCE_ALL_CXX20_DEPRECATION_WARNINGS', 'RIVE_WINDOWS', 'YOGA_EXPORT=', '_HAS_EXCEPTIONS=0',
    # core_registry.hpp is an internal header; rive.lib itself is built with this define.
    '_RIVE_INTERNAL_') | ForEach-Object { "/D$_" }
$includes = @(
    (Join-Path $RiveRuntime 'include'),
    (Join-Path $RiveRuntime 'renderer\include'),
    (Join-Path $RiveRuntime 'dependencies'),
    (Join-Path $RiveRuntime 'renderer\dependencies\rive-app_yoga_rive_changes_v2_0_1_3_grid')
) | ForEach-Object { "/I`"$_`"" }
$libs = @('rive.lib', 'rive_harfbuzz.lib', 'rive_sheenbidi.lib', 'rive_yoga.lib', 'user32.lib')

$src = Join-Path $PSScriptRoot 'rivdump.cpp'
# NoOpFactory is a test utility, not part of rive.lib; compile it in.
$noop = Join-Path $RiveRuntime 'utils\no_op_factory.cpp'
$cl = "cl /nologo /MT /O2 /std:c++latest /EHs-c- /bigobj $($defines -join ' ') $($includes -join ' ') `"$src`" `"$noop`" /Fo`"$OutDir\\`" /Fe`"$OutDir\rivdump.exe`" /link /LIBPATH:`"$lib`" $($libs -join ' ')"
$env:PATH = "$(Split-Path $vswhere);$env:PATH"
cmd /c "`"$vcvars`" >nul && $cl"
if ($LASTEXITCODE -ne 0) { throw "cl failed with exit code $LASTEXITCODE" }
Write-Host "Built $OutDir\rivdump.exe"
