param(
    [Parameter(Mandatory = $true)][string]$NodeExe,
    [Parameter(Mandatory = $true)][string]$InstallDir,
    [Parameter(Mandatory = $true)][string]$DestDir
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $NodeExe)) {
    throw "node.exe missing: $NodeExe"
}
New-Item -ItemType Directory -Force -Path $DestDir | Out-Null
Copy-Item -LiteralPath $NodeExe -Destination (Join-Path $DestDir 'node.exe') -Force
foreach ($name in @('package.json', 'package-lock.json', 'runtime-manifest.json')) {
    $source = Join-Path $InstallDir $name
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Runtime file missing: $source"
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $DestDir $name) -Force
}

$modulesSource = Join-Path $InstallDir 'node_modules'
$modulesDest = Join-Path $DestDir 'node_modules'
if (Test-Path -LiteralPath $modulesDest) {
    Remove-Item -LiteralPath $modulesDest -Recurse -Force
}
if (-not (Test-Path -LiteralPath $modulesSource)) {
    throw "node_modules missing: $modulesSource"
}
Copy-Item -LiteralPath $modulesSource -Destination $modulesDest -Recurse -Force

# letta-code 0.34.8's shrinkwrap nested @letta-ai/letta-agent-sdk with its own older letta-code
# copies (0.33.7, and 0.33.3 below that) plus their dependencies; 0.34.14's SDK nests none, but the
# removal stays for a release that brings them back. Those paths exceed the 260-character
# limit WiX enforces in the MSI. The SDK resolves @letta-ai/letta-code with require.resolve, so
# without the nested copies it uses the bundled runtime itself; its own dependencies (letta-client,
# ajv) live one level up. The shrinkwrap also leaves an orphan letta-code/node_modules tree with
# no package.json under letta-code/node_modules/@letta-ai/letta-code, which nothing resolves.
$lettaCodeModules = Join-Path $modulesDest '@letta-ai\letta-code\node_modules\@letta-ai'
$nestedSdkModules = Join-Path $lettaCodeModules 'letta-agent-sdk\node_modules'
if (Test-Path -LiteralPath $nestedSdkModules) {
    Remove-Item -LiteralPath $nestedSdkModules -Recurse -Force
}
$orphanLettaCode = Join-Path $lettaCodeModules 'letta-code'
if ((Test-Path -LiteralPath $orphanLettaCode) -and -not (Test-Path -LiteralPath (Join-Path $orphanLettaCode 'package.json'))) {
    Remove-Item -LiteralPath $orphanLettaCode -Recurse -Force
}

# letta-mobile-szx7v: npm 11 (bundled with the desktop's Node 24) ignores the os/cpu fields of
# optional packages nested inside a dependency, and letta-code 0.34's shrinkwrap nests sharp's
# prebuilt binaries exactly that way. So every platform's libvips gets installed (macOS, Linux on
# six CPUs, musl, Windows ia32/arm64, wasm): about 450 MB this Windows x64 runtime can never load,
# which grew the v0.23.0 MSI from 292 to 462 MB. npm 10 installs only win32-x64 from the same
# lockfile. Keep just the win32-x64 builds. sharp-electron's linux-x64 copy is only loaded on Linux;
# on Windows it re-exports the real sharp.
$keptSharpPlatforms = @('sharp-win32-x64', 'sharp-libvips-win32-x64')
$foreignSharp = @(Get-ChildItem -LiteralPath $modulesDest -Recurse -Directory -Force -Filter 'sharp-*' | Where-Object {
    $_.Parent.Name -eq '@img' -and $keptSharpPlatforms -notcontains $_.Name
})
foreach ($dir in $foreignSharp) {
    # A foreign package can nest another one (sharp-freebsd-wasm32 carries sharp-wasm32), which is
    # already gone once its parent is removed.
    if (Test-Path -LiteralPath $dir.FullName) {
        Remove-Item -LiteralPath $dir.FullName -Recurse -Force
    }
}
$sharpElectronLinux = Join-Path $modulesDest '@letta-ai\letta-code\node_modules\@janhapke\sharp-electron\linux-x64'
if (Test-Path -LiteralPath $sharpElectronLinux) {
    Remove-Item -LiteralPath $sharpElectronLinux -Recurse -Force
}
# The runtime still has to carry the sharp build it does load.
if (-not (Get-ChildItem -LiteralPath $modulesDest -Recurse -Directory -Force -Filter 'sharp-win32-x64' | Select-Object -First 1)) {
    throw "sharp-win32-x64 is missing from the staged runtime: $modulesDest"
}
Write-Host "Pruned $($foreignSharp.Count) sharp packages for other platforms from the staged runtime"

Get-ChildItem -LiteralPath $modulesDest -Recurse -Force | Where-Object {
    $_.Name -like '*.d.ts' -or
        $_.Name -like '*.d.mts' -or
        $_.Name -like '*.d.cts' -or
        $_.Name -like '*.map' -or
        $_.FullName -match '[\\/]dist-types[\\/]'
} | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
