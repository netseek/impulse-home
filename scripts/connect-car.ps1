# Connect TCP ADB to the live Haval MMI on the current LAN.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'car-adb-common.ps1')

$Adb = Get-Adb
$serial = Find-Car $Adb
Save-CarSerial $serial
Write-Host "Connected $serial"
& $Adb -s $serial shell "echo SHELL_OK; getprop ro.product.model; pm path $CarPackage"
if ($LASTEXITCODE -ne 0) { throw "ADB shell failed on $serial" }
