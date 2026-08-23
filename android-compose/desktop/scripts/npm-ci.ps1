param(
    [Parameter(Mandatory = $true)][string]$NpmCmd,
    [Parameter(Mandatory = $true)][string]$WorkDir
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $WorkDir
& $NpmCmd ci --omit=dev --no-audit --no-fund
