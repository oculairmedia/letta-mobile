param(
  [Parameter(Mandatory = $true)][string]$ImageRoot,
  [Parameter(Mandatory = $true)][string]$EvidenceRoot
)

$ErrorActionPreference = 'Stop'

# Process.MainWindowHandle is a .NET heuristic. Keep the raw Win32 facts in the
# failure artifact so an AWT HWND can be distinguished from an actual frame.
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public sealed class SmokeWindow {
    public long Handle { get; set; }
    public int Pid { get; set; }
    public string Class { get; set; }
    public string Title { get; set; }
    public bool Exists { get; set; }
    public bool Visible { get; set; }
    public bool Minimized { get; set; }
    public long Parent { get; set; }
    public long Owner { get; set; }
    public long Root { get; set; }
    public long Style { get; set; }
    public long ExStyle { get; set; }
    public int Left { get; set; }
    public int Top { get; set; }
    public int Right { get; set; }
    public int Bottom { get; set; }
    public int Cloaked { get; set; }
}

public static class SmokeWin32 {
    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr data);
    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr data);
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr GetParent(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr hwnd, uint command);
    [DllImport("user32.dll")] private static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
    [DllImport("user32.dll")] private static extern int GetWindowThreadProcessId(IntPtr hwnd, out int pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hwnd, StringBuilder name, int max);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hwnd, StringBuilder name, int max);
    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")] private static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attribute, out int value, int size);

    public static SmokeWindow Inspect(long handle) {
        IntPtr hwnd = new IntPtr(handle);
        int pid;
        GetWindowThreadProcessId(hwnd, out pid);
        var cls = new StringBuilder(256);
        var title = new StringBuilder(512);
        GetClassName(hwnd, cls, cls.Capacity);
        GetWindowText(hwnd, title, title.Capacity);
        RECT rect;
        GetWindowRect(hwnd, out rect);
        int cloaked = -1;
        try {
            if (DwmGetWindowAttribute(hwnd, 14, out cloaked, sizeof(int)) != 0) cloaked = -1;
        } catch (DllNotFoundException) { cloaked = -1; }
        return new SmokeWindow {
            Handle = handle, Pid = pid, Class = cls.ToString(), Title = title.ToString(),
            Exists = IsWindow(hwnd), Visible = IsWindowVisible(hwnd), Minimized = IsIconic(hwnd),
            Parent = GetParent(hwnd).ToInt64(), Owner = GetWindow(hwnd, 4).ToInt64(),
            Root = GetAncestor(hwnd, 2).ToInt64(),
            Style = GetWindowLongPtr(hwnd, -16).ToInt64(), ExStyle = GetWindowLongPtr(hwnd, -20).ToInt64(),
            Left = rect.Left, Top = rect.Top, Right = rect.Right, Bottom = rect.Bottom, Cloaked = cloaked
        };
    }

    public static SmokeWindow[] TopLevelWindows() {
        var result = new List<SmokeWindow>();
        EnumWindows((hwnd, data) => { result.Add(Inspect(hwnd.ToInt64())); return true; }, IntPtr.Zero);
        return result.ToArray();
    }

    public static int[] VirtualScreen() {
        return new int[] { GetSystemMetrics(76), GetSystemMetrics(77), GetSystemMetrics(78), GetSystemMetrics(79) };
    }
}
'@

function Test-PackagedChild($Process, [string]$ImagePrefix) {
  if (-not $Process.ExecutablePath) { return $false }
  if (-not $Process.ExecutablePath.StartsWith($ImagePrefix, [System.StringComparison]::OrdinalIgnoreCase)) { return $false }
  return $Process.CreationDate -ge $script:LaunchTime.AddSeconds(-2)
}

function Add-PackagedChildren($Processes, $Ids, [string]$ImagePrefix) {
  foreach ($process in $Processes) {
    if ($Ids.Contains([int]$process.ParentProcessId) -and (Test-PackagedChild $process $ImagePrefix)) {
      [void]$Ids.Add([int]$process.ProcessId)
    }
  }
}

function Get-ProcessEvidence($Process, $Ids, [string]$ImagePrefix) {
  $inImage = $Process.ExecutablePath -and $Process.ExecutablePath.StartsWith($ImagePrefix, [System.StringComparison]::OrdinalIgnoreCase)
  if ($Ids.Contains([int]$Process.ProcessId) -or $inImage) {
    [pscustomobject]@{
      Pid = [int]$Process.ProcessId
      ParentPid = [int]$Process.ParentProcessId
      Name = $Process.Name
      Path = $Process.ExecutablePath
      Created = $Process.CreationDate
      OwnedTree = $Ids.Contains([int]$Process.ProcessId)
      InImage = [bool]$inImage
    }
  }
}

function Get-OwnedProcesses([int]$LauncherPid, [string]$ImagePath) {
  $all = @(Get-CimInstance Win32_Process)
  $ids = [System.Collections.Generic.HashSet[int]]::new()
  [void]$ids.Add($LauncherPid)
  $imagePrefix = $ImagePath.TrimEnd('\') + '\'
  do {
    $before = $ids.Count
    Add-PackagedChildren $all $ids $imagePrefix
  } while ($ids.Count -ne $before)
  foreach ($process in $all) {
    Get-ProcessEvidence $process $ids $imagePrefix
  }
}

function Test-AppFrameProcess($Process, [string]$LauncherPath) {
  if (-not $Process) { return $false }
  if (-not $Process.OwnedTree) { return $false }
  if (-not $Process.InImage) { return $false }
  return [string]::Equals($Process.Path, $LauncherPath, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-VisibleTopLevelFrame($Window) {
  if (-not $Window.Exists) { return $false }
  if (-not $Window.Visible) { return $false }
  if ($Window.Minimized) { return $false }
  if ($Window.Cloaked -ne 0) { return $false }
  if ($Window.Parent -ne 0) { return $false }
  if ($Window.Owner -ne 0) { return $false }
  return $Window.Root -eq $Window.Handle
}

function Test-NormalFrameStyle($Window) {
  if ($Window.Class -ne 'SunAwtFrame') { return $false }
  if (($Window.Style -band 0x40000000) -ne 0) { return $false } # WS_CHILD
  if (($Window.Style -band 0x00C00000) -ne 0x00C00000) { return $false } # WS_CAPTION
  if (($Window.Style -band 0x00080000) -eq 0) { return $false } # WS_SYSMENU
  return ($Window.Style -band 0x10000000) -ne 0 # WS_VISIBLE
}

function Test-FrameOnScreen($Window, [int[]]$Screen) {
  if ($Screen[2] -le 0) { return $false }
  if ($Screen[3] -le 0) { return $false }
  if ($Window.Right -le $Window.Left) { return $false }
  if ($Window.Bottom -le $Window.Top) { return $false }
  if ($Window.Right -le $Screen[0]) { return $false }
  if ($Window.Bottom -le $Screen[1]) { return $false }
  if ($Window.Left -ge ($Screen[0] + $Screen[2])) { return $false }
  return $Window.Top -lt ($Screen[1] + $Screen[3])
}

function Test-NormalAppFrame($Window, $Process, [string]$LauncherPath, [int[]]$Screen) {
  if (-not (Test-AppFrameProcess $Process $LauncherPath)) { return $false }
  if (-not (Test-VisibleTopLevelFrame $Window)) { return $false }
  if (-not (Test-NormalFrameStyle $Window)) { return $false }
  return Test-FrameOnScreen $Window $Screen
}

function Get-EligibleFrame([int]$LauncherPid, [string]$ImagePath, [string]$LauncherPath, [long]$TargetHandle = 0) {
  $processes = @(Get-OwnedProcesses $LauncherPid $ImagePath)
  $screen = [SmokeWin32]::VirtualScreen()
  foreach ($window in [SmokeWin32]::TopLevelWindows()) {
    if ($TargetHandle -ne 0 -and $window.Handle -ne $TargetHandle) { continue }
    $process = $processes | Where-Object { $_.Pid -eq $window.Pid } | Select-Object -First 1
    if (Test-NormalAppFrame $window $process $LauncherPath $screen) {
      return [pscustomobject]@{ Window = $window; Process = $process; Processes = $processes }
    }
  }
  return $null
}

function Read-LogMatches([string]$Path, [string]$Pattern) {
  if (-not (Test-Path -LiteralPath $Path)) { return @() }
  $raw = Get-Content -LiteralPath $Path -Raw
  if (-not $raw) { return @() }
  return [regex]::Matches($raw, $Pattern)
}

function Get-LauncherMainHandle([int]$LauncherPid) {
  try { return (Get-Process -Id $LauncherPid -ErrorAction Stop).MainWindowHandle.ToInt64() }
  catch { return 0 }
}

function Write-WindowSnapshot([string]$Phase, [int]$LauncherPid, [string]$ImagePath, [string]$EvidencePath) {
  $processes = @(Get-OwnedProcesses $LauncherPid $ImagePath)
  $windows = @([SmokeWin32]::TopLevelWindows() | Where-Object { @($processes.Pid) -contains $_.Pid })
  $stdout = Join-Path $EvidencePath 'stdout.log'
  $knownHandles = @(Read-LogMatches $stdout 'handle=(\d+)' | ForEach-Object { [long]$_.Groups[1].Value } | Select-Object -Unique)
  $probes = @($knownHandles | ForEach-Object { [SmokeWin32]::Inspect($_) })
  $mainHandle = Get-LauncherMainHandle $LauncherPid
  $snapshot = [pscustomobject]@{
    Phase = $Phase
    TimeUtc = (Get-Date).ToUniversalTime().ToString('o')
    LauncherPid = $LauncherPid
    MainWindowHandle = $mainHandle
    Processes = $processes
    TopLevelWindows = $windows
    NativeHandleProbes = $probes
  }
  $snapshot | ConvertTo-Json -Depth 8 -Compress | Add-Content -LiteralPath (Join-Path $EvidencePath 'windows.jsonl')
  Write-Host "WINDOW SNAPSHOT $Phase launcher=$LauncherPid main=$mainHandle processes=$($processes.Count) topLevel=$($windows.Count) nativeProbes=$($probes.Count)"
  foreach ($window in $windows) {
    Write-Host "WINDOW hwnd=$($window.Handle) pid=$($window.Pid) class=$($window.Class) title='$($window.Title)' visible=$($window.Visible) owner=$($window.Owner) root=$($window.Root) style=$($window.Style) rect=$($window.Left),$($window.Top),$($window.Right),$($window.Bottom)"
  }
  foreach ($probe in $probes) {
    Write-Host "HANDLE hwnd=$($probe.Handle) pid=$($probe.Pid) class=$($probe.Class) exists=$($probe.Exists) visible=$($probe.Visible) parent=$($probe.Parent) owner=$($probe.Owner) root=$($probe.Root)"
  }
  return $processes
}

function Save-FailureEvidence([int]$LauncherPid, [string]$ImagePath, [string]$EvidencePath) {
  $processes = @()
  try { $processes = @(Write-WindowSnapshot 'before-cleanup' $LauncherPid $ImagePath $EvidencePath) }
  catch { "Window snapshot: $($_.Exception.Message)" | Add-Content -LiteralPath (Join-Path $EvidencePath 'diagnostic-errors.log') }
  Save-Screenshot $EvidencePath
  Save-ThreadDumps $LauncherPid $processes $EvidencePath
}

function Save-Screenshot([string]$EvidencePath) {
  try {
    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms
    $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $bitmap = [System.Drawing.Bitmap]::new($bounds.Width, $bounds.Height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
      $graphics.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, $bitmap.Size)
      $bitmap.Save((Join-Path $EvidencePath 'desktop.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
  } catch { "Screenshot: $($_.Exception.Message)" | Add-Content -LiteralPath (Join-Path $EvidencePath 'diagnostic-errors.log') }
}

function Test-ThreadDumpTarget([int]$TargetPid, [int]$LauncherPid, $Processes) {
  if ($TargetPid -eq $LauncherPid) { return $true }
  return @($Processes | Where-Object { $_.Pid -eq $TargetPid -and $_.InImage }).Count -gt 0
}

function Save-ThreadDump([string]$Jcmd, [int]$TargetPid, [string]$EvidencePath) {
  $output = Join-Path $EvidencePath "thread-$TargetPid.log"
  $errorOutput = Join-Path $EvidencePath "thread-$TargetPid.err.log"
  try {
    $dump = Start-Process -FilePath $Jcmd -ArgumentList @($TargetPid, 'Thread.print', '-l') -PassThru -RedirectStandardOutput $output -RedirectStandardError $errorOutput
    if (-not $dump.WaitForExit(8000)) { Stop-Process -Id $dump.Id -Force -ErrorAction SilentlyContinue; "jcmd timed out for pid $TargetPid" | Add-Content -LiteralPath $errorOutput }
  } catch { "jcmd pid $TargetPid`: $($_.Exception.Message)" | Add-Content -LiteralPath $errorOutput }
}

function Get-ThreadDumpTargets([int]$LauncherPid, [string]$EvidencePath) {
  $targetPids = [System.Collections.Generic.HashSet[int]]::new()
  [void]$targetPids.Add($LauncherPid)
  $stderr = Join-Path $EvidencePath 'stderr.log'
  foreach ($match in (Read-LogMatches $stderr 'TABLET: native tablet_input[^\r\n]* pid=(\d+)')) {
    [void]$targetPids.Add([int]$match.Groups[1].Value)
  }
  return $targetPids
}

function Save-ThreadDumps([int]$LauncherPid, $Processes, [string]$EvidencePath) {
  if (-not $env:JAVA_HOME_26_X64) { return }
  $jcmd = Join-Path $env:JAVA_HOME_26_X64 'bin\jcmd.exe'
  if (-not (Test-Path -LiteralPath $jcmd)) { return }
  foreach ($targetPid in (Get-ThreadDumpTargets $LauncherPid $EvidencePath)) {
    if (Test-ThreadDumpTarget $targetPid $LauncherPid $Processes) {
      Save-ThreadDump $jcmd $targetPid $EvidencePath
    }
  }
}

function Wait-ForPackagedFrame($App, [string]$ImagePath, [string]$LauncherPath, [string]$EvidencePath) {
  $timer = [System.Diagnostics.Stopwatch]::StartNew()
  $nextSnapshot = 0
  while ($timer.Elapsed.TotalSeconds -lt 90) {
    $elapsed = $timer.Elapsed.TotalSeconds
    if ($elapsed -ge $nextSnapshot) {
      try { $null = Write-WindowSnapshot "poll-$([int]$elapsed)" $App.Id $ImagePath $EvidencePath }
      catch { "Poll snapshot: $($_.Exception.Message)" | Add-Content -LiteralPath (Join-Path $EvidencePath 'diagnostic-errors.log') }
      $nextSnapshot += 15
    }
    $frame = Get-EligibleFrame $App.Id $ImagePath $LauncherPath
    if ($frame) { return $frame }
    $App.Refresh()
    if ($App.HasExited -and -not @(Get-OwnedProcesses $App.Id $ImagePath | Where-Object { $_.OwnedTree }).Count) {
      throw "Packaged Windows app process tree exited before first frame: $($App.ExitCode)"
    }
    $remainingMs = [math]::Min(1000, [math]::Max(1, [int]((90 - $timer.Elapsed.TotalSeconds) * 1000)))
    Start-Sleep -Milliseconds $remainingMs
  }
  throw 'No visible packaged Windows app frame within 90 seconds'
}

function Test-SameFrameProcess($Frame, $Again) {
  if (-not $Again) { return $false }
  if ($Again.Process.Pid -ne $Frame.Process.Pid) { return $false }
  return $Again.Process.Created -eq $Frame.Process.Created
}

function Confirm-StableFrame($Frame, [int]$LauncherPid, [string]$ImagePath, [string]$LauncherPath) {
  Start-Sleep -Seconds 5
  $again = Get-EligibleFrame $LauncherPid $ImagePath $LauncherPath $Frame.Window.Handle
  if (-not (Test-SameFrameProcess $Frame $again)) {
    throw 'Packaged Windows app frame did not stay visible for five seconds'
  }
  return $again
}

$images = @(Get-ChildItem -LiteralPath $ImageRoot -Directory)
if ($images.Count -ne 1) { throw 'Expected one packaged Windows app image' }
$image = $images[0].FullName
$launchers = @(Get-ChildItem -LiteralPath $image -File -Filter '*.exe')
if ($launchers.Count -ne 1) { throw 'Expected one packaged Windows launcher' }
$runtime = Join-Path $image 'app\resources\letta-code-runtime'
$node = Join-Path $runtime 'node.exe'
$js = Join-Path $runtime 'node_modules\@letta-ai\letta-code\letta.js'
$manifest = Get-Content (Join-Path $runtime 'runtime-manifest.json') -Raw | ConvertFrom-Json
$smokeProfile = Join-Path $EvidenceRoot ('profile-' + [guid]::NewGuid())
$env:HOME = $smokeProfile
$env:USERPROFILE = $smokeProfile
$env:LETTA_HOME = Join-Path $smokeProfile '.letta'
$env:HOMEDRIVE = [System.IO.Path]::GetPathRoot($smokeProfile).TrimEnd('\')
$env:HOMEPATH = $smokeProfile.Substring($env:HOMEDRIVE.Length)
$env:APPDATA = Join-Path $smokeProfile 'config'
$env:LOCALAPPDATA = Join-Path $smokeProfile 'data'
$env:XDG_CONFIG_HOME = $env:APPDATA
$env:XDG_CACHE_HOME = Join-Path $smokeProfile 'cache'
$env:XDG_DATA_HOME = $env:LOCALAPPDATA
$env:XDG_STATE_HOME = Join-Path $smokeProfile 'state'
$env:XDG_RUNTIME_DIR = Join-Path $smokeProfile 'runtime'
$env:XDG_CONFIG_DIRS = $env:APPDATA
$env:XDG_DATA_DIRS = $env:LOCALAPPDATA
New-Item -ItemType Directory -Force -Path $smokeProfile, $env:APPDATA, $env:LOCALAPPDATA, $env:XDG_CACHE_HOME, $env:XDG_STATE_HOME, $env:XDG_RUNTIME_DIR | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Duser.home="' + $smokeProfile + '"'
Remove-Item Env:_JAVA_OPTIONS, Env:JDK_JAVA_OPTIONS, Env:NODE_OPTIONS, Env:NODE_PATH, Env:LETTA_DESKTOP_RUNTIME_NODE, Env:LETTA_DESKTOP_RUNTIME_JS, Env:JAVA_HOME, Env:JDK_HOME -ErrorAction SilentlyContinue
Set-Location -LiteralPath $smokeProfile
$version = & $node --version
if ($LASTEXITCODE -ne 0 -or $version -ne "v$($manifest.node)") { throw 'Bundled Node version mismatch' }
& $node $js --version
if ($LASTEXITCODE -ne 0) { throw 'Bundled AppServer CLI failed' }
& $node $js server --help
if ($LASTEXITCODE -ne 0) { throw 'Bundled AppServer bootstrap failed' }
$app = $null
try {
  $app = Start-Process -FilePath $launchers[0].FullName -WorkingDirectory $EvidenceRoot -PassThru -RedirectStandardOutput (Join-Path $EvidenceRoot 'stdout.log') -RedirectStandardError (Join-Path $EvidenceRoot 'stderr.log')
  $script:LaunchTime = $app.StartTime
  Write-Host "Launched packaged Windows app: pid=$($app.Id) path=$($launchers[0].FullName)"
  $frame = Wait-ForPackagedFrame $app $image $launchers[0].FullName $EvidenceRoot
  $frame = Confirm-StableFrame $frame $app.Id $image $launchers[0].FullName
  $errors = Get-Content (Join-Path $EvidenceRoot 'stdout.log'), (Join-Path $EvidenceRoot 'stderr.log') -Raw
  if ($errors -match 'UnsupportedClassVersionError|NoClassDefFoundError|UnsatisfiedLinkError|Failed to launch JVM|Could not find or load main class|Exception in thread|A fatal error has been detected|FATAL ERROR') { throw "Startup error: $errors" }
  $crashes = @(Get-ChildItem -LiteralPath $EvidenceRoot -Recurse -File -Include 'hs_err_pid*.log', 'java_error_in*.log', '*.dmp')
  if ($crashes.Count -ne 0) { throw "New crash evidence: $($crashes.FullName)" }
  try { $null = Write-WindowSnapshot 'accepted' $app.Id $image $EvidenceRoot }
  catch { "Accepted snapshot: $($_.Exception.Message)" | Add-Content -LiteralPath (Join-Path $EvidenceRoot 'diagnostic-errors.log') }
  Save-Screenshot $EvidenceRoot
  "PASS: launcher=$($app.Id) framePid=$($frame.Process.Pid) hwnd=$($frame.Window.Handle) class=$($frame.Window.Class) title=$($frame.Window.Title)" | Tee-Object -FilePath (Join-Path $EvidenceRoot 'window.txt')
} catch {
  if ($app) {
    try { Save-FailureEvidence $app.Id $image $EvidenceRoot } catch { "Diagnostic: $($_.Exception.Message)" | Add-Content -LiteralPath (Join-Path $EvidenceRoot 'diagnostic-errors.log') }
  }
  throw
} finally {
  if ($app) {
    try {
      $owned = @(Get-OwnedProcesses $app.Id $image | Where-Object { $_.OwnedTree } | Sort-Object Created -Descending)
      foreach ($process in $owned) { Stop-Process -Id $process.Pid -Force -ErrorAction SilentlyContinue }
    } catch { Write-Warning "Packaged process cleanup: $($_.Exception.Message)" }
  }
  Get-Content (Join-Path $EvidenceRoot 'stdout.log'), (Join-Path $EvidenceRoot 'stderr.log') -ErrorAction SilentlyContinue
}
