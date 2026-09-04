# Shared ADB discovery for the live Haval MMI.
# Talks to the car on the LAN. Do not use a Pi / Tailscale gateway.
$CarPackage = 'com.havalh6.viewer'
$CarActivity = 'com.havalh6.viewer/.MainActivity'
$CarMediaListener = 'com.havalh6.viewer/com.havalh6.viewer.MediaNotificationListener'
$CarSubnets = @('192.168.33', '192.168.1')
$CarRoot = Split-Path -Parent $PSScriptRoot
$CarHintFile = Join-Path $CarRoot '.car-adb-serial'
# Head-unit AVD for local deploy — not a phone/tablet profile (e.g. Medium_Phone).
$HavalAvdName = 'Haval'

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
  $out = & $Adb -s $Serial shell pm path $CarPackage 2>$null
  return [string]$out -match [regex]::Escape($CarPackage)
}

# Head unit even if the viewer APK is missing (fresh install / after uninstall).
function Test-Mmi([string]$Adb, [string]$Serial) {
  if (Test-Viewer $Adb $Serial) { return $true }
  $dev = [string](& $Adb -s $Serial shell getprop ro.product.device 2>$null)
  $model = [string](& $Adb -s $Serial shell getprop ro.product.model 2>$null)
  return "$dev $model" -match '(?i)gwm|msmnile_gvmq'
}

function Install-CarApk([string]$Adb, [string]$Serial, [string]$Apk) {
  # 2>&1 on a native command wraps stderr in a NativeCommandError; with the
  # caller's $ErrorActionPreference='Stop' that becomes a terminating error
  # and skips the recovery logic below entirely, before $out is ever
  # inspected. Scope EAP to 'Continue' around every 2>&1 native call here.
  $callerEap = $ErrorActionPreference
  $installer = 'com.autolink.installer'
  Write-Host "Installing on $Serial as $installer ..."
  $ErrorActionPreference = 'Continue'
  $out = & $Adb -s $Serial install -r -i $installer $Apk 2>&1 | Out-String
  $ErrorActionPreference = $callerEap
  Write-Host $out.Trim()
  if ($LASTEXITCODE -eq 0 -and $out -notmatch 'Failure \[') { return }

  if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Write-Host 'Signature mismatch — uninstalling then reinstalling via Autolink ...'
    $ErrorActionPreference = 'Continue'
    $null = & $Adb -s $Serial uninstall $CarPackage 2>&1
    $ErrorActionPreference = $callerEap
  }

  Write-Host 'Pushing APK and pm install -i com.autolink.installer ...'
  & $Adb -s $Serial push $Apk /data/local/tmp/havalh6-viewer.apk
  if ($LASTEXITCODE -ne 0) { throw "adb push failed ($LASTEXITCODE)" }
  $ErrorActionPreference = 'Continue'
  $pm = & $Adb -s $Serial shell "pm install -r -i $installer /data/local/tmp/havalh6-viewer.apk" 2>&1 | Out-String
  $ErrorActionPreference = $callerEap
  Write-Host $pm.Trim()
  if ($pm -notmatch '(?m)^Success' -and $LASTEXITCODE -ne 0) {
    throw "pm install failed: $($pm.Trim())"
  }
  if ($pm -match 'Failure \[') { throw "pm install failed: $($pm.Trim())" }
}

# The notification listener is what MediaSessionManager.getActiveSessions needs;
# without it the media rail only ever sees Android Auto / USB via MediaCenter,
# and anything with a real MediaSession (YouTube, Spotify, a browser) shows
# nothing at all. The grant lives in a secure setting keyed on the component,
# so an uninstall - which Install-CarApk does on a signature mismatch - drops
# it silently. Re-assert it on every deploy rather than leaving it to a
# remembered manual adb step.
function Grant-CarMediaAccess([string]$Adb, [string]$Serial) {
  $callerEap = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $current = & $Adb -s $Serial shell settings get secure enabled_notification_listeners 2>&1 | Out-String
  $ErrorActionPreference = $callerEap
  if ($current -match [regex]::Escape($CarMediaListener)) {
    Write-Host 'Notification listener already granted.'
    return
  }
  Write-Host 'Granting notification listener access (media rail) ...'
  $ErrorActionPreference = 'Continue'
  $null = & $Adb -s $Serial shell cmd notification allow_listener $CarMediaListener 2>&1
  $after = & $Adb -s $Serial shell settings get secure enabled_notification_listeners 2>&1 | Out-String
  $ErrorActionPreference = $callerEap
  if ($after -match [regex]::Escape($CarMediaListener)) {
    Write-Host 'Notification listener granted.'
  } else {
    Write-Warning "Could not grant $CarMediaListener - the media rail will show ENABLE MEDIA ACCESS."
  }
}

function Connect-Adb([string]$Adb, [string]$Target) {
  if ($Target -notmatch ':\d+$') { $Target = "$Target:5555" }
  $null = & $Adb connect $Target 2>&1
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

function Get-WifiSsid {
  $lines = @(netsh wlan show interfaces 2>$null)
  foreach ($line in $lines) {
    if ($line -match 'BSSID') { continue }
    if ($line -match 'SSID\s*:\s*(.+)$') {
      $ssid = $Matches[1].Trim()
      if ($ssid) { return $ssid }
    }
  }
  return $null
}

function Get-LocalIpv4Prefixes {
  $found = @()
  foreach ($nic in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
    if ($nic.OperationalStatus -ne 'Up') { continue }
    if ($nic.NetworkInterfaceType -eq 'Loopback') { continue }
    foreach ($addr in $nic.GetIPProperties().UnicastAddresses) {
      if ($addr.Address.AddressFamily -ne 'InterNetwork') { continue }
      $ip = $addr.Address.ToString()
      if ($ip -match '^(192\.168\.(1|33))\.') { $found += $Matches[1] }
    }
  }
  return @($found | Select-Object -Unique)
}

function Get-CarLan {
  $ssid = Get-WifiSsid
  $local = @(Get-LocalIpv4Prefixes)
  $onCarAp = ($ssid -match '(?i)HAVAL[_ ]?SEEK') -or ($local -contains '192.168.33')
  $onHome = ($ssid -match '(?i)<home-ssid>') -or ($local -contains '192.168.1')

  $order = New-Object System.Collections.Generic.List[string]
  if ($onCarAp) { [void]$order.Add('192.168.33') }
  if ($onHome) { [void]$order.Add('192.168.1') }
  foreach ($p in $CarSubnets) {
    if (-not $order.Contains($p)) { [void]$order.Add($p) }
  }

  $route = if ($onCarAp -and $onHome) { 'dual' }
    elseif ($onCarAp) { 'HAVAL_SEEK' }
    elseif ($onHome) { 'home-lan' }
    else { 'unknown' }

  return [pscustomobject]@{
    Ssid          = $ssid
    LocalPrefixes = $local
    ScanOrder     = @($order)
    OnCarAp       = [bool]$onCarAp
    OnHome        = [bool]$onHome
    Route         = $route
  }
}

function Wait-AdbDevice([string]$Adb, [string]$Serial, [int]$Seconds = 8) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  $started = Get-Date
  do {
    $row = Get-AdbRows $Adb | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
    if ($row -and $row.Status -eq 'device') { return $true }
    # Decoy :5555 hosts stay offline forever — don't burn the full timeout.
    if ($row -and $row.Status -eq 'offline' -and ((Get-Date) - $started).TotalSeconds -gt 1.2) {
      return $false
    }
    Start-Sleep -Milliseconds 400
  } while ((Get-Date) -lt $deadline)
  return $false
}

function Find-Car([string]$Adb) {
  $lan = Get-CarLan
  $ssid = if ($lan.Ssid) { $lan.Ssid } else { '(none)' }
  $local = if ($lan.LocalPrefixes) { $lan.LocalPrefixes -join ',' } else { '(none)' }
  Write-Host "Car LAN: route=$($lan.Route) ssid=$ssid local=$local scan=$($lan.ScanOrder -join ',')"

  # Never treat a local emulator as the MMI — even if the viewer APK is installed.
  $live = @(Get-AdbRows $Adb | Where-Object { $_.Status -eq 'device' -and -not (Test-IsEmulator $_.Serial) })
  foreach ($row in $live) {
    if (Test-Mmi $Adb $row.Serial) { return $row.Serial }
  }

  $hints = @()
  if (Test-Path $CarHintFile) {
    $hints += ([string](Get-Content $CarHintFile -Raw)).Trim()
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
    if ((Wait-AdbDevice $Adb $serial 6) -and (Test-Mmi $Adb $serial)) {
      return $serial
    }
  }

  Write-Host "Scanning $($lan.ScanOrder -join ' then ') for :5555 ..."
  $hosts = Scan-AdbHosts $lan.ScanOrder
  # Prefer IPs on the current LAN, keep scan order otherwise.
  $sorted = @($hosts | Sort-Object {
    $prefix = ($_ -replace '\.\d+$', '')
    $idx = [array]::IndexOf($lan.ScanOrder, $prefix)
    if ($idx -lt 0) { 99 } else { $idx }
  }, { [int]($_ -split '\.')[-1] })
  foreach ($ip in $sorted) {
    $serial = "${ip}:5555"
    Write-Host "Trying $serial ..."
    Connect-Adb $Adb $serial
    if ((Wait-AdbDevice $Adb $serial 6) -and (Test-Mmi $Adb $serial)) {
      return $serial
    }
  }

  throw 'No Haval MMI on this LAN. Join home Wi-Fi (or HAVAL_SEEK) with the car awake, then retry.'
}

function Save-CarSerial([string]$Serial) {
  Set-Content -Path $CarHintFile -Value $Serial -NoNewline
}

function Get-EmulatorExe {
  $exe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
  if (-not (Test-Path $exe)) { throw 'Android emulator not found (install via SDK Manager).' }
  return $exe
}

function Get-EmulatorAvdName([string]$Adb, [string]$Serial) {
  $out = @(& $Adb -s $Serial emu avd name 2>$null)
  if ($out.Count -ge 1) {
    $name = ([string]$out[0]).Trim()
    if ($name) { return $name }
  }
  $prop = (& $Adb -s $Serial shell getprop qemu.avd_name 2>$null).Trim()
  return $prop
}

function Wait-EmulatorBoot([string]$Adb, [string]$Serial, [int]$TimeoutSec = 180) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  do {
    $row = Get-AdbRows $Adb | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
    if ($row -and $row.Status -eq 'device') {
      $boot = (& $Adb -s $Serial shell getprop sys.boot_completed 2>$null).Trim()
      if ($boot -eq '1') { return $true }
    }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  return $false
}

function Start-HavalEmulator([string]$Adb) {
  $avds = @(& (Get-EmulatorExe) -list-avds 2>$null)
  if ($avds -notcontains $HavalAvdName) {
    throw "Haval AVD '$HavalAvdName' not found. Create it in Android Studio (AVD Manager), then retry."
  }
  Write-Host "Starting $HavalAvdName emulator ..."
  Start-Process -FilePath (Get-EmulatorExe) -ArgumentList @('-avd', $HavalAvdName, '-no-snapshot-load')
  $deadline = (Get-Date).AddSeconds(120)
  do {
    Start-Sleep -Seconds 3
    $rows = @(Get-AdbRows $Adb | Where-Object { (Test-IsEmulator $_.Serial) })
    foreach ($row in $rows) {
      if ($row.Status -ne 'device') { continue }
      $name = Get-EmulatorAvdName $Adb $row.Serial
      if ($name -eq $HavalAvdName -and (Wait-EmulatorBoot $Adb $row.Serial)) {
        return $row.Serial
      }
    }
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $HavalAvdName emulator to boot."
}

function Ensure-HavalEmulator([string]$Adb) {
  $rows = @(Get-AdbRows $Adb | Where-Object { (Test-IsEmulator $_.Serial) })
  foreach ($row in $rows) {
    if ($row.Status -ne 'device') { continue }
    $name = Get-EmulatorAvdName $Adb $row.Serial
    if ($name -eq $HavalAvdName) {
      if (Wait-EmulatorBoot $Adb $row.Serial 30) { return $row.Serial }
    }
  }
  return Start-HavalEmulator $Adb
}

function Find-Emulator([string]$Adb, [string]$PreferredSerial) {
  if ($PreferredSerial) {
    $row = Get-AdbRows $Adb | Where-Object { $_.Serial -eq $PreferredSerial } | Select-Object -First 1
    if (-not $row -or $row.Status -ne 'device') {
      throw "Emulator $PreferredSerial not ready. Attached: $((Get-AdbRows $Adb | ForEach-Object { $_.Serial + ':' + $_.Status }) -join ', ')"
    }
    if (-not (Test-IsEmulator $PreferredSerial)) {
      throw "Serial $PreferredSerial is not an emulator."
    }
    if (-not (Wait-EmulatorBoot $Adb $PreferredSerial 30)) {
      throw "Emulator $PreferredSerial is still booting."
    }
    return $PreferredSerial
  }
  return Ensure-HavalEmulator $Adb
}

function Install-EmulatorApk([string]$Adb, [string]$Serial, [string]$Apk) {
  Write-Host "Installing on $Serial ..."
  $out = & $Adb -s $Serial install -r $Apk 2>&1 | Out-String
  Write-Host $out.Trim()
  if ($LASTEXITCODE -ne 0 -or $out -match 'Failure \[') {
    throw "adb install failed: $($out.Trim())"
  }
}
