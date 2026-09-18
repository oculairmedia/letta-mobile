<#
.SYNOPSIS
    Start the System 1 (Laya) service in the background.

.DESCRIPTION
    Launches tools/system1-laya/start_server.py detached, writing logs to
    reports/system1-laya.log and the PID to reports/system1-laya.pid.

.EXAMPLE
    scripts\start-system1.ps1
    scripts\start-system1.ps1 -Port 9000 -Device cpu
#>
[CmdletBinding()]
param(
    [int]$Port = 8771,
    [string]$HostAddress = "127.0.0.1",
    [ValidateSet("cuda", "cpu", "mps")]
    [string]$Device,
    [string]$PythonBin = "python"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$serviceDir = Join-Path $repoRoot "tools\system1-laya"
$logDir = Join-Path $repoRoot "reports"
$logFile = Join-Path $logDir "system1-laya.log"
$errFile = Join-Path $logDir "system1-laya.err.log"
$pidFile = Join-Path $logDir "system1-laya.pid"

if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

if (Test-Path $pidFile) {
    $existing = Get-Content $pidFile | Select-Object -First 1
    if ($existing -and (Get-Process -Id $existing -ErrorAction SilentlyContinue)) {
        Write-Host "System 1 already running (pid $existing)."
        exit 0
    }
}

$serverArgs = @((Join-Path $serviceDir "start_server.py"), "--port", $Port, "--host", $HostAddress)
if ($Device) { $serverArgs += @("--device", $Device) }

$process = Start-Process -FilePath $PythonBin -ArgumentList $serverArgs `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile `
    -WorkingDirectory $serviceDir -PassThru -WindowStyle Hidden

Set-Content -Path $pidFile -Value $process.Id -Encoding utf8

Write-Host "System 1 starting (pid $($process.Id)); logs: $logFile"
Write-Host "Model load takes ~30s on a cold Hugging Face cache. Poll /health until it reports ok:"
Write-Host "  curl.exe -s http://${HostAddress}:${Port}/health"
