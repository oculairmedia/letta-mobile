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
Get-ChildItem -LiteralPath $modulesDest -Recurse -Force | Where-Object {
    $_.Name -like '*.d.ts' -or
        $_.Name -like '*.map' -or
        $_.FullName -match '[\\/]dist-types[\\/]'
} | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
