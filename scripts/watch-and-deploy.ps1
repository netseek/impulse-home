# Poll for the live Haval MMI on the LAN and deploy the already-built debug APK
# the moment it's reachable. Intended for background use after a manual build
# (does NOT rebuild — pass a fresh APK in beforehand).
param(
  [int]$PollSeconds = 5,
  [int]$TimeoutMinutes = 120
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'car-adb-common.ps1')

$Apk = Join-Path $CarRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $Apk)) { throw "APK missing: $Apk" }

$Adb = Get-Adb
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$attempt = 0

Write-Host "Watching for car ... (poll every ${PollSeconds}s, timeout ${TimeoutMinutes}m)"

while ((Get-Date) -lt $deadline) {
  $attempt++
  try {
    $serial = Find-Car $Adb
    Write-Host "Car found: $serial"
    Save-CarSerial $serial
    Install-CarApk $Adb $serial $Apk

    Write-Host 'Granting READ_EXTERNAL_STORAGE (icon override registry) ...'
    & $Adb -s $serial shell "pm grant $CarPackage android.permission.READ_EXTERNAL_STORAGE"

    Write-Host 'Launching viewer ...'
    & $Adb -s $serial shell am start -n $CarActivity

    Write-Host "DEPLOY_OK serial=$serial attempts=$attempt"
    exit 0
  } catch {
    Write-Host "[attempt $attempt] not found yet: $($_.Exception.Message)"
    Start-Sleep -Seconds $PollSeconds
  }
}

Write-Host "DEPLOY_TIMEOUT after $TimeoutMinutes minutes ($attempt attempts)"
exit 1
