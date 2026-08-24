param(
    [Parameter(Mandatory = $true)][string]$Url,
    [Parameter(Mandatory = $true)][string]$OutFile,
    [Parameter(Mandatory = $true)][string]$Algorithm,
    [Parameter(Mandatory = $true)][string]$Expected
)

$ErrorActionPreference = 'Stop'

function Get-FileHashHex([string]$Path, [string]$Name) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $hasher = [System.Security.Cryptography.HashAlgorithm]::Create($Name)
        try {
            return ([System.BitConverter]::ToString($hasher.ComputeHash($stream)) -replace '-', '').ToLowerInvariant()
        } finally {
            $hasher.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($OutFile)) | Out-Null
if ((Test-Path -LiteralPath $OutFile) -and ((Get-FileHashHex $OutFile $Algorithm) -eq $Expected)) {
    exit 0
}

$temporary = "$OutFile.part"
Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
Invoke-WebRequest -UseBasicParsing $Url -OutFile $temporary
$actual = Get-FileHashHex $temporary $Algorithm
if ($actual -ne $Expected) {
    throw "Checksum mismatch for $OutFile using $Algorithm"
}
Move-Item -LiteralPath $temporary -Destination $OutFile -Force
