<#
.SYNOPSIS
    Stop the System 1 (Laya) service started by scripts\start-system1.ps1.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$pidFile = Join-Path (Split-Path -Parent $PSScriptRoot) "reports\system1-laya.pid"

if (-not (Test-Path $pidFile)) {
    Write-Host "No System 1 pid file; nothing to stop."
    exit 0
}

$servicePid = Get-Content $pidFile | Select-Object -First 1
if ($servicePid -and (Get-Process -Id $servicePid -ErrorAction SilentlyContinue)) {
    Stop-Process -Id $servicePid -Force
    Write-Host "Stopped System 1 (pid $servicePid)."
} else {
    Write-Host "System 1 (pid $servicePid) was not running."
}
Remove-Item $pidFile -Force
