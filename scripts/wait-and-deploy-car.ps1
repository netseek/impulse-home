# Wait for Haval H6 to come online on LAN or Tailscale, then deploy the latest APK.
param(
  [switch]$SkipBuild,
  [switch]$NoLaunch,
  [int]$TimeoutMinutes = 120,
  [int]$IntervalSeconds = 8
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'car-adb-common.ps1')

$Apk = Join-Path $CarRoot 'app\build\outputs\apk\debug\app-debug.apk'

$Adb = Get-Adb
if (-not $SkipBuild) {
  Write-Host 'Building debug APK from local main ...'
  Push-Location $CarRoot
  try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed ($LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
}
if (-not (Test-Path $Apk)) { throw "APK missing: $Apk" }

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
      Connect-Adb $Adb "$tailscaleIp:5555"
      if ((Wait-AdbDevice $Adb "$tailscaleIp:5555" 6) -and (Test-Mmi $Adb "$tailscaleIp:5555")) {
        return "$tailscaleIp:5555"
      }
    }

    # 3. Check hinted serial
    if (Test-Path $CarHintFile) {
      $hint = ([string](Get-Content $CarHintFile -Raw)).Trim()
      if ($hint -and $hint -ne "$tailscaleIp:5555") {
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

Write-Host "Car found! Installing APK on $serial ..."
Install-CarApk $Adb $serial $Apk
Grant-CarMediaAccess $Adb $serial
Grant-CarHitAccessibility $Adb $serial

if (-not $NoLaunch) {
  Write-Host 'Launching viewer ...'
  & $Adb -s $serial shell am start -n $CarActivity
}

Write-Host "Capturing verification screenshot ..."
Start-Sleep -Seconds 4
$screenLocal = Join-Path $CarRoot 'car-deployed-live.png'
& $Adb -s $serial shell screencap -p /sdcard/screen.png
& $Adb -s $serial pull /sdcard/screen.png $screenLocal

Write-Host "SUCCESS: Deployed to $serial"
