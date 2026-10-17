# OctopusFetch.ps1 - fetches live demand and today's usage from your Octopus Home Mini
# via the Octopus Energy GraphQL API, and writes the results to ..\data.txt for Rainmeter.
#
# Run it by hand to test:  powershell -ExecutionPolicy Bypass -File OctopusFetch.ps1
# Works with Windows PowerShell 5.1 (built into Windows 10/11) and PowerShell 7.

$ErrorActionPreference = 'Stop'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$Res          = Split-Path -Parent $PSScriptRoot          # ...\OctopusLive\@Resources
$SettingsFile = Join-Path $Res 'Settings.inc'
$DataFile     = Join-Path $Res 'data.txt'
$CacheFile    = Join-Path $Res 'cache.json'
$ErrorFile    = Join-Path $Res 'last-error.txt'
$Api          = if ($env:OCTOPUS_API_URL) { $env:OCTOPUS_API_URL } else { 'https://api.octopus.energy/v1/graphql/' }
$DataKeys     = 'Status', 'Demand', 'TodayKWh', 'ReadAt', 'Updated', 'GasKWh', 'GasAt', 'GasKW'
$GasEverySecs = 30 * 60      # gas meters only report every 30 minutes

# ---------- helpers ----------

function Read-Settings {
    $s = @{}
    if (Test-Path $SettingsFile) {
        foreach ($line in Get-Content $SettingsFile) {
            if ($line -match '^\s*([A-Za-z0-9_]+)\s*=(.*)$') { $s[$Matches[1]] = $Matches[2].Trim() }
        }
    }
    return $s
}

function Read-KeyValueFile([string]$path) {
    $d = @{}
    if (Test-Path $path) {
        foreach ($line in Get-Content $path) {
            if ($line -match '^([A-Za-z0-9_]+)=(.*)$') { $d[$Matches[1]] = $Matches[2] }
        }
    }
    return $d
}

function Write-Data([hashtable]$values) {
    $lines = foreach ($k in $DataKeys) {
        $v = if ($values.ContainsKey($k) -and $null -ne $values[$k]) { "$($values[$k])" } else { '' }
        "$k=" + ($v -replace '[\r\n]+', ' ')
    }
    $tmp = "$DataFile.tmp"
    Set-Content -Path $tmp -Value $lines -Encoding ASCII
    Move-Item -Path $tmp -Destination $DataFile -Force
}

function Read-Cache {
    $c = @{}
    if (Test-Path $CacheFile) {
        try {
            $o = Get-Content $CacheFile -Raw | ConvertFrom-Json
            foreach ($p in $o.PSObject.Properties) { $c[$p.Name] = $p.Value }
        } catch {}
    }
    return $c
}

function Save-Cache([hashtable]$c) {
    ($c | ConvertTo-Json -Compress) | Set-Content -Path $CacheFile -Encoding ASCII
}

function Clean([string]$s) { return ($s -replace '["\\\s]', '') }   # keep values safe inside GraphQL strings

function Invoke-Gql([string]$query, [string]$token) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = $token }
    $body = @{ query = $query } | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Uri $Api -Method Post -ContentType 'application/json' -Headers $headers `
                               -Body $body -TimeoutSec 30 -UseBasicParsing
    } catch {
        throw "Network/HTTP error: $($_.Exception.Message)"
    }
    if ($r.errors) {
        $e = @($r.errors)[0]
        $code = $null
        if ($e.extensions) { $code = $e.extensions.errorCode }
        $ex = New-Object System.Exception ("{0} [{1}]" -f $e.message, $code)
        $ex.Data['code'] = "$code"
        throw $ex
    }
    return $r.data
}

function Get-Token([hashtable]$cache, [string]$apiKey) {
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($cache.token -and $cache.tokenExpires -and [long]$cache.tokenExpires -gt $now) { return $cache.token }
    $d = Invoke-Gql ('mutation { obtainKrakenToken(input: { APIKey: "' + (Clean $apiKey) + '" }) { token } }') $null
    $cache.token = $d.obtainKrakenToken.token
    $cache.tokenExpires = $now + 50 * 60          # tokens last 60 min; renew a bit early
    return $cache.token
}

function Get-DeviceId([hashtable]$cache, [string]$token, [string]$accountSetting) {
    if ($cache.deviceId -and $null -ne $cache.gasDeviceId) { return $cache.deviceId }

    $account = $accountSetting
    if (-not $account) {
        $d = Invoke-Gql 'query { viewer { accounts { number } } }' $token
        $account = @($d.viewer.accounts)[0].number
        if (-not $account) { throw 'Could not find an account for this API key. Set AccountNumber in Settings.inc.' }
    }

    $q = 'query { account(accountNumber: "' + (Clean $account) + '") { electricityAgreements(active: true) { ' +
         'meterPoint { direction meters(includeInactive: false) { smartImportElectricityMeter { deviceId } } } } ' +
         'gasAgreements(active: true) { meterPoint { meters(includeInactive: false) { smartGasMeter { deviceId } } } } } }'
    $d = Invoke-Gql $q $token

    # Gas meter is optional: 'none' means the account has no smart gas meter
    $cache.gasDeviceId = 'none'
    foreach ($ag in @($d.account.gasAgreements)) {
        foreach ($m in @($ag.meterPoint.meters)) {
            if ($m.smartGasMeter -and $m.smartGasMeter.deviceId) { $cache.gasDeviceId = $m.smartGasMeter.deviceId; break }
        }
        if ($cache.gasDeviceId -ne 'none') { break }
    }
    foreach ($ag in @($d.account.electricityAgreements)) {
        if ($ag.meterPoint.direction -eq 'EXPORT') { continue }
        foreach ($m in @($ag.meterPoint.meters)) {
            if ($m.smartImportElectricityMeter -and $m.smartImportElectricityMeter.deviceId) {
                $cache.account  = $account
                $cache.deviceId = $m.smartImportElectricityMeter.deviceId
                return $cache.deviceId
            }
        }
    }
    throw 'No smart electricity meter found on this account. Is your Home Mini set up?'
}

function Get-Telemetry([string]$token, [string]$deviceId) {
    $now      = Get-Date
    $start    = $now.Date.ToString('yyyy-MM-ddTHH:mm:sszzz')      # local midnight, with UTC offset
    $end      = $now.ToString('yyyy-MM-ddTHH:mm:sszzz')
    $id       = Clean $deviceId
    # One request: the latest reading (live) plus today's half-hourly totals (today)
    $q = 'query { ' +
         'live: smartMeterTelemetry(deviceId: "' + $id + '") { readAt demand consumption } ' +
         'today: smartMeterTelemetry(deviceId: "' + $id + '", grouping: HALF_HOURLY, start: "' + $start + '", end: "' + $end + '") { readAt consumptionDelta } }'
    return Invoke-Gql $q $token
}

# Today's gas usage (kWh). Gas meters only send a reading every 30 minutes, so this
# is fetched at most every $GasEverySecs seconds and never affects the electricity status.
function Update-Gas([hashtable]$cache, [hashtable]$data, [string]$token) {
    if (-not $cache.gasDeviceId -or $cache.gasDeviceId -eq 'none') { $data.GasKWh = ''; $data.GasAt = ''; $data.GasKW = ''; return }
    $now   = Get-Date
    $today = $now.ToString('yyyy-MM-dd')
    $nowS  = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($cache.gasDay -eq $today -and $cache.gasFetched -and ($nowS - [long]$cache.gasFetched) -lt $GasEverySecs) { return }

    $start = $now.Date.ToString('yyyy-MM-ddTHH:mm:sszzz')
    $end   = $now.ToString('yyyy-MM-ddTHH:mm:sszzz')
    $q = 'query { gas: smartMeterTelemetry(deviceId: "' + (Clean $cache.gasDeviceId) + '", grouping: HALF_HOURLY, start: "' + $start + '", end: "' + $end + '") { readAt consumptionDelta } }'
    try {
        $d = Invoke-Gql $q $token
        $wh = 0.0; $last = $null; $latest = $null
        foreach ($row in @($d.gas)) {
            if ($row -and $null -ne $row.consumptionDelta) {
                $wh += [double]$row.consumptionDelta
                if ([double]$row.consumptionDelta -gt 0) { $last = "$($row.readAt)" }
                if (-not $latest -or "$($row.readAt)" -ge "$($latest.readAt)") { $latest = $row }
            }
        }
        # Latest half-hour in Wh -> average kW over that half-hour
        $kw = 0.0; if ($latest) { $kw = [double]$latest.consumptionDelta / 500.0 }
        $data.GasKW = $kw.ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
        $data.GasKWh = ([Math]::Round($wh / 1000.0, 2)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
        $at = [DateTimeOffset]::MinValue
        if ($last -and [DateTimeOffset]::TryParse($last, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$at)) {
            $data.GasAt = $at.ToLocalTime().ToString('HH:mm')
        } else {
            $data.GasAt = '--:--'
        }
        $cache.gasDay = $today
        $cache.gasFetched = $nowS
    } catch {
        # Keep the last gas figure; try again on the next refresh
        Add-Content -Path $ErrorFile -Value ("{0}  Gas: {1}" -f (Get-Date -Format 's'), $_.Exception.Message) -Encoding ASCII
        if ($cache.gasDay -ne $today) { $data.GasKWh = '0.00'; $data.GasAt = '--:--'; $data.GasKW = '0.00' }
    }
}

# Reads the latest values from your own Octopus Live relay server (see /server in the repo).
function Update-FromRelay([string]$relayUrl, [hashtable]$data) {
    $ub = New-Object System.UriBuilder($relayUrl.Trim())
    if ($ub.Path -eq '' -or $ub.Path -eq '/') { $ub.Path = '/api/live' }
    try {
        $r = Invoke-RestMethod -Uri $ub.Uri -Method Get -TimeoutSec 10 -UseBasicParsing
    } catch {
        throw "Server offline ($($ub.Host)) - $($_.Exception.Message)"
    }
    function LocalHHmm($s) {
        $t = [DateTimeOffset]::MinValue
        if ($s -and [DateTimeOffset]::TryParse("$s", [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$t)) { return $t.ToLocalTime().ToString('HH:mm') }
        return '--:--'
    }
    $inv = [Globalization.CultureInfo]::InvariantCulture
    if ($null -ne $r.demandW)  { $data.Demand = [Math]::Round([double]$r.demandW) }
    if ($null -ne $r.todayKwh) { $data.TodayKWh = ([double]$r.todayKwh).ToString('0.00', $inv) }
    if ($r.readAt) { $data.ReadAt = LocalHHmm $r.readAt }
    if ($r.PSObject.Properties.Name -contains 'hasGas') {
        if ($r.hasGas) {
            $g = 0.0; if ($null -ne $r.gasKwh) { $g = [double]$r.gasKwh }
            $data.GasKWh = $g.ToString('0.00', $inv)
            $data.GasAt  = LocalHHmm $r.gasAt
            $k = 0.0; if ($null -ne $r.gasKw) { $k = [double]$r.gasKw }
            $data.GasKW  = $k.ToString('0.00', $inv)
        } else { $data.GasKWh = ''; $data.GasAt = ''; $data.GasKW = '' }
    }
    $data.Status = if ($r.ok) { 'OK' } else { "Relay: $($r.status)" }
}

# ---------- main ----------

$data = Read-KeyValueFile $DataFile
foreach ($k in $DataKeys) { if (-not $data.ContainsKey($k)) { $data[$k] = '' } }

try {
    $settings = Read-Settings
    $apiKey = "$($settings.ApiKey)"
    $relayUrl = "$($settings.RelayUrl)"
    $done = $false
    if ($relayUrl) {
        try {
            Update-FromRelay $relayUrl $data
            $done = $true
            if (Test-Path $ErrorFile) { Remove-Item $ErrorFile -Force -ErrorAction SilentlyContinue }
        } catch {
            # No API key to fall back on: report the relay problem
            if (-not $apiKey) { throw }
        }
    }
    if (-not $done) {
    if (-not $apiKey) { throw 'Add your Octopus API key (or RelayUrl) to Settings.inc (right-click skin > Edit settings).' }

    $cache = Read-Cache
    # When the relay is down and the skin refreshes quickly, call Octopus at most once a minute
    $nowS = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($relayUrl -and $cache.lastDirect -and ($nowS - [long]$cache.lastDirect) -lt 60) {
        throw 'Server offline - using Octopus once a minute'
    }
    $cache.lastDirect = $nowS
    # Forget cached token/device if the key or account number changed
    $fingerprint = $apiKey.Substring([Math]::Max(0, $apiKey.Length - 6)) + '|' + "$($settings.AccountNumber)"
    if ($cache.fingerprint -ne $fingerprint) { $cache = @{ fingerprint = $fingerprint; lastDirect = $nowS } }

    $result = $null
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        try {
            $token    = Get-Token $cache $apiKey
            $deviceId = Get-DeviceId $cache $token "$($settings.AccountNumber)"
            $result   = Get-Telemetry $token $deviceId
            break
        } catch {
            $code = "$($_.Exception.Data['code'])"
            if ($attempt -eq 1 -and $code -like 'KT-CT-11*') {   # auth/token problem: get a fresh token and retry once
                $cache.token = $null; $cache.tokenExpires = $null
                continue
            }
            throw
        }
    }
    if (Test-Path $ErrorFile) { Remove-Item $ErrorFile -Force -ErrorAction SilentlyContinue }
    Update-Gas $cache $data $token
    Save-Cache $cache

    $live = @($result.live) | Where-Object { $_ } | Sort-Object { "$($_.readAt)" } | Select-Object -Last 1
    if (-not $live) { throw 'No live reading returned. Check the Home Mini is online.' }

    $todayWh = 0.0
    foreach ($row in @($result.today)) {
        if ($row -and $null -ne $row.consumptionDelta) { $todayWh += [double]$row.consumptionDelta }
    }

    $data.Demand   = if ($null -ne $live.demand) { [Math]::Round([double]$live.demand) } else { $data.Demand }
    $data.TodayKWh = ([Math]::Round($todayWh / 1000.0, 2)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
    $readAt = [DateTimeOffset]::MinValue
    if ([DateTimeOffset]::TryParse("$($live.readAt)", [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$readAt)) {
        $data.ReadAt = $readAt.ToLocalTime().ToString('HH:mm')
    } else {
        $data.ReadAt = "$($live.readAt)"
    }
    $data.Status   = 'OK'
    }
} catch {
    $msg = "$($_.Exception.Message)"
    $data.Status = $msg
    if ("$($_.Exception.Data['code'])" -in 'KT-CT-1139', 'KT-CT-1111', 'KT-CT-1143') {
        $data.Status = 'API key rejected - check Settings.inc'
    } elseif ($msg -match '429|Too many|rate') {
        $data.Status = 'Rate limited - try Interval=90'
    }
    Set-Content -Path $ErrorFile -Value ("{0}  {1}" -f (Get-Date -Format 's'), $msg) -Encoding ASCII
}

$data.Updated = (Get-Date).ToString('HH:mm:ss')
Write-Data $data
foreach ($k in $DataKeys) { Write-Output "$k=$($data[$k])" }
