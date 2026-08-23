param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [Parameter(Mandatory = $true)][string]$ExtractTo,
    [Parameter(Mandatory = $true)][string]$Target
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName 'System.IO.Compression.FileSystem' -ErrorAction SilentlyContinue
if (-not (Test-Path -LiteralPath $Archive)) {
    throw "Archive missing on disk: $Archive"
}
if ((Test-Path -LiteralPath $Target) -and (Get-ChildItem -LiteralPath $Target -ErrorAction SilentlyContinue | Select-Object -First 1)) {
    exit 0
}
$parent = Split-Path -Parent $Target
$leaf = Split-Path -Leaf $Target
$stageRoot = Join-Path $parent (".{0}.extract-{1}" -f $leaf, [Guid]::NewGuid().ToString("N"))
$backup = Join-Path $parent (".{0}.previous-{1}" -f $leaf, [Guid]::NewGuid().ToString("N"))

New-Item -ItemType Directory -Force -Path $parent | Out-Null
try {
    New-Item -ItemType Directory -Path $stageRoot | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Archive, $stageRoot)
    $stagedEntries = @(Get-ChildItem -LiteralPath $stageRoot)
    if ($stagedEntries.Count -eq 0) {
        throw "Archive extracted no entries: $Archive"
    }

    if (Test-Path -LiteralPath $Target) {
        Move-Item -LiteralPath $Target -Destination $backup
    }
    Move-Item -LiteralPath $stageRoot -Destination $Target
    if (Test-Path -LiteralPath $backup) {
        Remove-Item -LiteralPath $backup -Recurse -Force
    }
} catch {
    if ((Test-Path -LiteralPath $backup) -and -not (Test-Path -LiteralPath $Target)) {
        Move-Item -LiteralPath $backup -Destination $Target
    }
    throw
} finally {
    if (Test-Path -LiteralPath $stageRoot) {
        Remove-Item -LiteralPath $stageRoot -Recurse -Force
    }
}
