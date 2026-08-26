# Deploy the viewer to the live Haval MMI.
# Discovers ADB each run — DHCP, so last IP is only a hint.
param(
  [switch]$SkipBuild,
  [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
$Package = 'com.havalh6.viewer'
$Activity = 'com.havalh6.viewer/.MainActivity'
$Subnets = @('192.168.33', '192.168.1')
$Root = Split-Path -Parent $PSScriptRoot
$HintFile = Join-Path $Root '.car-adb-serial'
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'

function Get-Adb {
  $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
  if (Test-Path $sdk) { return $sdk }
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  throw 'adb not found (install Android platform-tools).'
}

function Get-AdbRows([string]$Adb) {
  $rows = @()
  & $Adb devices | Select-Object -Skip 1 | ForEach-Object {
    if ($_ -match '^(\S+)\s+(\S+)') {
      $rows += [pscustomobject]@{ Serial = $Matches[1]; Status = $Matches[2] }
    }
  }
  return $rows
}

function Test-Viewer([string]$Adb, [string]$Serial) {
  $out = & $Adb -s $Serial shell pm path $Package 2>$null
  return [string]$out -match [regex]::Escape($Package)
}

function Connect-Adb([string]$Adb, [string]$Target) {
  if ($Target -notmatch ':\d+$') { $Target = "$Target:5555" }
  $null = & $Adb connect $Target 2>&1
}

function Wait-AdbDevice([string]$Adb, [string]$Serial, [int]$Seconds = 8) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    $row = Get-AdbRows $Adb | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
    if ($row -and $row.Status -eq 'device') { return $true }
    Start-Sleep -Milliseconds 400
  } while ((Get-Date) -lt $deadline)
  return $false
}

function Scan-AdbHosts([string[]]$Prefixes) {
  $open = New-Object System.Collections.Concurrent.ConcurrentBag[string]
  $pool = [runspacefactory]::CreateRunspacePool(1, 64)
  $pool.Open()
  $workers = @()
  foreach ($prefix in $Prefixes) {
    1..254 | ForEach-Object {
      $ip = "$prefix.$_"
      $ps = [powershell]::Create().AddScript({
        param($Ip)
        $tcp = New-Object System.Net.Sockets.TcpClient
        try {
          $ar = $tcp.BeginConnect($Ip, 5555, $null, $null)
          if ($ar.AsyncWaitHandle.WaitOne(200, $false) -and $tcp.Connected) { $Ip }
        } catch {
        } finally { $tcp.Dispose() }
      }).AddArgument($ip)
      $ps.RunspacePool = $pool
      $workers += @{ Shell = $ps; Handle = $ps.BeginInvoke() }
    }
  }
  foreach ($w in $workers) {
    $out = $w.Shell.EndInvoke($w.Handle)
    if ($out) { $open.Add([string]$out) }
    $w.Shell.Dispose()
  }
  $pool.Close()
  return @($open)
}

function Test-IsEmulator([string]$Serial) {
  return $Serial -match '^(emulator-|127\.0\.0\.1:|localhost:)'
}

function Find-Car([string]$Adb) {
  # Never treat a local emulator as the MMI — even if the viewer APK is installed.
  $live = @(Get-AdbRows $Adb | Where-Object { $_.Status -eq 'device' -and -not (Test-IsEmulator $_.Serial) })
  foreach ($row in $live) {
    if (Test-Viewer $Adb $row.Serial) { return $row.Serial }
  }

  $hints = @()
  if (Test-Path $HintFile) {
    $hints += ([string](Get-Content $HintFile -Raw)).Trim()
  }

  $offline = @(Get-AdbRows $Adb | Where-Object { $_.Status -ne 'device' })
  foreach ($row in $offline) {
    $null = & $Adb disconnect $row.Serial 2>&1
    $hints = @($row.Serial) + $hints
  }

  foreach ($hint in $hints) {
    if (-not $hint) { continue }
    Write-Host "Trying $hint ..."
    Connect-Adb $Adb $hint
    $serial = $hint
    if ($serial -notmatch ':\d+$') { $serial = "$serial:5555" }
    if ((Wait-AdbDevice $Adb $serial 6) -and (Test-Viewer $Adb $serial)) {
      return $serial
    }
  }

  Write-Host 'Scanning 192.168.33.0/24 and 192.168.1.0/24 for :5555 ...'
  $hosts = Scan-AdbHosts $Subnets
  foreach ($ip in $hosts) {
    $serial = "${ip}:5555"
    Write-Host "Trying $serial ..."
    Connect-Adb $Adb $serial
    if ((Wait-AdbDevice $Adb $serial 6) -and (Test-Viewer $Adb $serial)) {
      return $serial
    }
  }

  throw 'No MMI with com.havalh6.viewer. Connect to HAVAL_SEEK (or the car LAN) and retry.'
}

$Adb = Get-Adb
if (-not $SkipBuild) {
  Write-Host 'Building debug APK ...'
  Push-Location $Root
  try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed ($LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
}
if (-not (Test-Path $Apk)) { throw "APK missing: $Apk" }

$serial = Find-Car $Adb
Set-Content -Path $HintFile -Value $serial -NoNewline
Write-Host "Installing on $serial ..."
& $Adb -s $serial install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed ($LASTEXITCODE)" }

if (-not $NoLaunch) {
  Write-Host 'Launching viewer ...'
  & $Adb -s $serial shell am start -n $Activity
}

Write-Host "Done. serial=$serial"
