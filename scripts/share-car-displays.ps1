# Mirror the live Haval MMI displays 0 (built-in) and 3 (HDMI) via scrcpy.
# Discovers the car on the LAN — same ADB path as deploy-car.ps1.
param(
  [int[]]$DisplayIds = @(0, 3)
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'car-adb-common.ps1')

function Get-Scrcpy {
  $portable = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'scrcpy') -Recurse -Filter scrcpy.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($portable) { return $portable.FullName }
  $cmd = Get-Command scrcpy -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  throw 'scrcpy not found. Expected %LOCALAPPDATA%\scrcpy\...\scrcpy.exe'
}

$Adb = Get-Adb
$Scrcpy = Get-Scrcpy
$env:ADB = $Adb

$serial = Find-Car $Adb
Save-CarSerial $serial
Write-Host "Car ADB $serial"

Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

foreach ($id in $DisplayIds) {
  $log = Join-Path $env:TEMP "scrcpy-d$id.log"
  $title = "Haval-Display$id"
  Write-Host "Launching $title ..."
  Start-Process -FilePath $Scrcpy -ArgumentList @(
    '-s', $serial,
    "--display-id=$id",
    "--window-title=$title",
    '--max-size=1280',
    '--video-bit-rate=4M',
    '--no-audio'
  ) -RedirectStandardError $log | Out-Null
}

Start-Sleep 4
Get-Process scrcpy -ErrorAction SilentlyContinue | Format-Table Id, ProcessName, MainWindowTitle
foreach ($id in $DisplayIds) {
  $log = Join-Path $env:TEMP "scrcpy-d$id.log"
  if (Test-Path $log) {
    Write-Host "=== display $id log ==="
    Get-Content $log
  }
}
