# Deploy the viewer to the live Haval MMI over TCP ADB (home LAN or HAVAL_SEEK).
param(
  [switch]$SkipBuild,
  [switch]$NoLaunch,
  [switch]$Share
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'car-adb-common.ps1')

$Apk = Join-Path $CarRoot 'app\build\outputs\apk\debug\app-debug.apk'

$Adb = Get-Adb
if (-not $SkipBuild) {
  Write-Host 'Building debug APK ...'
  Push-Location $CarRoot
  try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed ($LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
}
if (-not (Test-Path $Apk)) { throw "APK missing: $Apk" }

$serial = Find-Car $Adb
Save-CarSerial $serial
Install-CarApk $Adb $serial $Apk
Grant-CarMediaAccess $Adb $serial
Grant-CarHitAccessibility $Adb $serial

if (-not $NoLaunch) {
  Write-Host 'Launching viewer ...'
  & $Adb -s $serial shell am start -n $CarActivity
}

Write-Host "Done. serial=$serial"

if ($Share) {
  & (Join-Path $PSScriptRoot 'share-car-displays.ps1')
  if ($LASTEXITCODE -ne 0) { throw "share-car-displays failed ($LASTEXITCODE)" }
}
