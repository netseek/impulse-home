# Wait for Haval H6 to come online on LAN or Tailscale, then deploy both:
# 1. haval-h6-3d (com.havalh6.viewer)
# 2. Haval Impulse (br.com.redesurftank.havalshisuku)
param(
  [int]$TimeoutMinutes = 180,
  [int]$IntervalSeconds = 8
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'car-adb-common.ps1')

$ViewerApk = Join-Path $CarRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $ViewerApk)) { throw "Viewer APK missing: $ViewerApk" }

# Checkout of the sibling Impulse repository (set IMPULSE_DIR to override).
$ImpulseRoot = if ($env:IMPULSE_DIR) { $env:IMPULSE_DIR } else { Join-Path $CarRoot '..\haval-app-tool-multimidia' }
$ImpulseScript = Join-Path $ImpulseRoot 'scripts\Deploy-To-Car.ps1'
$ImpulseApk = Join-Path $ImpulseRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $ImpulseApk)) { throw "Impulse APK missing: $ImpulseApk" }

$Adb = Get-Adb

function Test-TcpPort([string]$HostName, [int]$Port = 5555, [int]$TimeoutMs = 800) {
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $ar = $client.BeginConnect($HostName, $Port, $null, $null)
    return ($ar.AsyncWaitHandle.WaitOne($TimeoutMs, $false) -and $client.Connected)
  } catch {
    return $false
  } finally {
    $client.Dispose()
  }
}

function Wait-ForCar([string]$Adb, [int]$TimeoutSec = 7200, [int]$PollSec = 8) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  $tailscaleIp = '100.121.41.52'
  $attempt = 0

  Write-Host "Waiting for Haval H6 on LAN or Tailscale ($tailscaleIp) ..."
  Write-Host "Deadline: $deadline (timeout in $([math]::Round($TimeoutSec / 60)) min)"

  while ((Get-Date) -lt $deadline) {
    $attempt++
    
    # 1. Check existing active non-emulator devices in ADB
    $live = @(Get-AdbRows $Adb | Where-Object { $_.Status -eq 'device' -and -not (Test-IsEmulator $_.Serial) })
    foreach ($row in $live) {
      if (Test-Mmi $Adb $row.Serial) {
        Write-Host "Found active car device: $($row.Serial)"
        return $row.Serial
      }
    }

    # 2. Check Tailscale host (100.121.41.52)
    if (Test-TcpPort $tailscaleIp 5555 900) {
      Write-Host "Tailscale port 5555 open on $tailscaleIp! Connecting ADB ..."
      Connect-Adb $Adb "${tailscaleIp}:5555"
      if ((Wait-AdbDevice $Adb "${tailscaleIp}:5555" 6) -and (Test-Mmi $Adb "${tailscaleIp}:5555")) {
        return "${tailscaleIp}:5555"
      }
    }

    # 3. Check hinted serial
    if (Test-Path $CarHintFile) {
      $hint = ([string](Get-Content $CarHintFile -Raw)).Trim()
      if ($hint -and $hint -ne "${tailscaleIp}:5555") {
        $hostOnly = ($hint -replace ':\d+$', '')
        if (Test-TcpPort $hostOnly 5555 500) {
          Write-Host "Hint port 5555 open on $hint! Connecting ADB ..."
          Connect-Adb $Adb $hint
          if ((Wait-AdbDevice $Adb $hint 6) -and (Test-Mmi $Adb $hint)) {
            return $hint
          }
        }
      }
    }

    # 4. Periodically scan LAN (every 4th iteration ~32s)
    if ($attempt % 4 -eq 0) {
      $lan = Get-CarLan
      $hosts = Scan-AdbHosts $lan.ScanOrder
      foreach ($ip in $hosts) {
        $serial = "${ip}:5555"
        Write-Host "Discovered LAN host: $serial - connecting ADB ..."
        Connect-Adb $Adb $serial
        if ((Wait-AdbDevice $Adb $serial 6) -and (Test-Mmi $Adb $serial)) {
          return $serial
        }
      }
    }

    $elapsed = [math]::Round(((Get-Date) - $deadline.AddSeconds(-$TimeoutSec)).TotalSeconds)
    if ($attempt % 4 -eq 0) {
      Write-Host "Still waiting for car... (${elapsed}s elapsed, checking Tailscale and LAN)"
    }

    Start-Sleep -Seconds $PollSec
  }

  throw "Timed out waiting for Haval H6 after $([math]::Round($TimeoutSec / 60)) minutes."
}

$timeoutTotalSec = [math]::Max(60, $TimeoutMinutes * 60)
$serial = Wait-ForCar -Adb $Adb -TimeoutSec $timeoutTotalSec -PollSec $IntervalSeconds
Save-CarSerial $serial

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Car found at $serial! Proceeding with dual deployment..." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

# 1. Deploy Haval H6 3D
Write-Host "Deploying Haval H6 3D ($ViewerApk) to $serial ..." -ForegroundColor Cyan
Install-CarApk $Adb $serial $ViewerApk
Grant-CarMediaAccess $Adb $serial
Grant-CarHitAccessibility $Adb $serial

# 2. Deploy Haval Impulse
if (Test-Path $ImpulseScript) {
  Write-Host "Deploying Haval Impulse to $serial ..." -ForegroundColor Cyan
  $hostOnly = ($serial -replace ':\d+$', '')
  & powershell -NoProfile -ExecutionPolicy Bypass -File $ImpulseScript -Target $hostOnly -SkipBuild
} else {
  Write-Host "Deploying Haval Impulse APK directly ..." -ForegroundColor Cyan
  & $Adb -s $serial install -r $ImpulseApk
}

# 3. Launch Viewer
Write-Host 'Launching Haval H6 3D viewer ...' -ForegroundColor Cyan
& $Adb -s $serial shell am start -n $CarActivity

# 4. Verification screenshot
Write-Host "Capturing verification screenshot ..." -ForegroundColor Cyan
Start-Sleep -Seconds 5
$screenLocal = Join-Path $CarRoot 'car-deployed-live.png'
& $Adb -s $serial shell screencap -p /sdcard/screen.png
& $Adb -s $serial pull /sdcard/screen.png $screenLocal

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "SUCCESS: Both apps deployed to $serial at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
