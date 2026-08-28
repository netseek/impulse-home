# Build, install, and launch the viewer on the local Haval head-unit AVD.
# Starts the "Haval" emulator if it is not already running — never a phone AVD.
param(
  [switch]$SkipBuild,
  [switch]$NoLaunch,
  [string]$Serial
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

$serial = Find-Emulator $Adb $Serial
Install-EmulatorApk $Adb $serial $Apk

if (-not $NoLaunch) {
  Write-Host 'Launching viewer ...'
  & $Adb -s $serial shell am start -n $CarActivity
}

Write-Host "Done. serial=$serial avd=$($HavalAvdName)"
