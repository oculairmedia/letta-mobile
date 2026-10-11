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

# letta-code 0.34's shrinkwrap nests @letta-ai/letta-agent-sdk with its own older letta-code copies
# (0.33.7, and 0.33.3 below that) plus their dependencies. Those paths exceed the 260-character
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

Get-ChildItem -LiteralPath $modulesDest -Recurse -Force | Where-Object {
    $_.Name -like '*.d.ts' -or
        $_.Name -like '*.d.mts' -or
        $_.Name -like '*.d.cts' -or
        $_.Name -like '*.map' -or
        $_.FullName -match '[\\/]dist-types[\\/]'
} | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
