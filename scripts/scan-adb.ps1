param(
  [string[]]$Subnets = @('192.168.1', '192.168.33')
)

function Test-AdbPort([string]$Ip) {
  $tcp = New-Object System.Net.Sockets.TcpClient
  try {
    $ar = $tcp.BeginConnect($Ip, 5555, $null, $null)
    if ($ar.AsyncWaitHandle.WaitOne(250, $false) -and $tcp.Connected) { return $true }
  } catch {
  } finally {
    $tcp.Dispose()
  }
  return $false
}

foreach ($prefix in $Subnets) {
  Write-Host "Scanning ${prefix}.0/24 :5555 ..."
  $open = @()
  1..254 | ForEach-Object -Parallel {
    param($Prefix)
    $ip = "$Prefix.$using:_"
    if (Test-AdbPort $ip) { $ip }
  } -ThrottleLimit 64 -ArgumentList $prefix 2>$null
  if (-not $?) {
    1..254 | ForEach-Object {
      $ip = "$prefix.$_"
      if (Test-AdbPort $ip) { $open += $ip }
    }
  }
}

# Fallback sequential if ForEach-Object -Parallel unavailable
if (-not $open) {
  foreach ($prefix in $Subnets) {
    Write-Host "Sequential scan ${prefix}.0/24 ..."
    1..254 | ForEach-Object {
      $ip = "$prefix.$_"
      if (Test-AdbPort $ip) {
        Write-Host "  OPEN $ip:5555"
      }
    }
  }
}
