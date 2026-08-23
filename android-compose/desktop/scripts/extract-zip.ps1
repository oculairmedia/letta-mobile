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
New-Item -ItemType Directory -Force -Path $ExtractTo | Out-Null
[System.IO.Compression.ZipFile]::ExtractToDirectory($Archive, $ExtractTo)
