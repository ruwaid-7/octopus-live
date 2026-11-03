# OctopusFetch.ps1 - fetches live demand and today's usage from your Octopus Home Mini
# via the Octopus Energy GraphQL API, and writes the results to ..\data.txt for Rainmeter.
#
# Run it by hand to test:  powershell -ExecutionPolicy Bypass -File OctopusFetch.ps1 -Manual
# Works with Windows PowerShell 5.1 (built into Windows 10/11) and PowerShell 7.
#
# Every Octopus Live skin runs this script on its own timer, but only one run at a time does any
# work, and not more often than the Interval in Settings.inc, so adding skins never adds Octopus
# requests. -Manual (a click or "Refresh now") allows a refresh after 10 seconds.
param([switch]$Manual)

$ErrorActionPreference = 'Stop'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$Res          = Split-Path -Parent $PSScriptRoot          # ...\OctopusLive\@Resources
$SettingsFile = Join-Path $Res 'Settings.inc'
$DataFile     = Join-Path $Res 'data.txt'
$CacheFile    = Join-Path $Res 'cache.json'
$ErrorFile    = Join-Path $Res 'last-error.txt'
$LastRunFile  = Join-Path $Res 'last-run.txt'
$Api          = if ($env:OCTOPUS_API_URL) { $env:OCTOPUS_API_URL } else { 'https://api.octopus.energy/v1/graphql/' }
$Rest         = if ($env:OCTOPUS_REST_URL) { $env:OCTOPUS_REST_URL } else { 'https://api.octopus.energy/v1/' }
# The first eight are what the original skin reads (in this order); the rest are for the newer skins
$DataKeys     = 'Status', 'Demand', 'TodayKWh', 'ReadAt', 'Updated', 'GasKWh', 'GasAt', 'GasKW',
                'ElecNum', 'ElecUnit', 'PerHour', 'GasOn', 'GasSub',
                'PriceNow', 'PriceNote', 'PriceCheaper', 'CostTotal', 'CostElec', 'CostGas',
                'TodayLine', 'Peak', 'Slots'
$GasEverySecs = 30 * 60      # gas meters only report every 30 minutes
$GasOnWh      = 100.0        # a half-hour with at least this much gas (0.2 kW average) counts as "on"
$SlotsEvery   = 35 * 60      # today's half-hours, when nothing else has fetched them
$Inv          = [Globalization.CultureInfo]::InvariantCulture

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
    # UTF-8 for the £, · and arrows the newer skins show (they read it with CodePage=65001)
    Set-Content -Path $tmp -Value $lines -Encoding UTF8
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
    ($c | ConvertTo-Json -Compress -Depth 6) | Set-Content -Path $CacheFile -Encoding ASCII
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

# Usually asks only for the live reading (one telemetry request). Today's half-hourly total is
# added when $withToday is set (every 30 minutes and at midnight); in between, "today" is kept up
# to date from the meter's running total. Octopus counts each telemetry series against its limit.
function Get-Telemetry([string]$token, [string]$deviceId, [bool]$withToday) {
    $now      = Get-Date
    $start    = $now.Date.ToString('yyyy-MM-ddTHH:mm:sszzz')      # local midnight, with UTC offset
    $end      = $now.ToString('yyyy-MM-ddTHH:mm:sszzz')
    $id       = Clean $deviceId
    $q = 'query { live: smartMeterTelemetry(deviceId: "' + $id + '") { readAt demand consumption } '
    if ($withToday) {
        $q += 'today: smartMeterTelemetry(deviceId: "' + $id + '", grouping: HALF_HOURLY, start: "' + $start + '", end: "' + $end + '") { readAt consumptionDelta } '
    }
    return Invoke-Gql ($q + '}') $token
}

# Sorts a list of @(time, ...) rows by time and always returns an array of rows (even 0 or 1)
function Sort-Rows($rows) {
    $list = New-Object 'System.Collections.Generic.List[object]'
    foreach ($r in $rows) { if ($null -ne $r) { $list.Add($r) } }
    $list.Sort([Comparison[object]] { param($a, $b) ([long]$a[0]).CompareTo([long]$b[0]) })
    return ,$list.ToArray()
}

# Half-hourly rows -> @( @(unix seconds, Wh), ... ), oldest first
function Convert-Rows($rows) {
    $out = New-Object 'System.Collections.Generic.List[object]'
    foreach ($row in @($rows)) {
        if (-not $row -or $null -eq $row.consumptionDelta) { continue }
        $t = [DateTimeOffset]::MinValue
        if ([DateTimeOffset]::TryParse("$($row.readAt)", $Inv, [Globalization.DateTimeStyles]::None, [ref]$t)) {
            $out.Add(@($t.ToUnixTimeSeconds(), [double]$row.consumptionDelta))
        }
    }
    return ,(Sort-Rows $out)
}

function Save-ElecSlots([hashtable]$cache, $rows) {
    $cache.elecSlots = Convert-Rows $rows
    $cache.elecSlotsDay = (Get-Date).ToString('yyyy-MM-dd')
    $cache.elecSlotsAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
}

# Today's gas usage (kWh). Gas meters only send a reading every 30 minutes, so this
# is fetched at most every $GasEverySecs seconds and never affects the electricity status.
function Update-Gas([hashtable]$cache, [hashtable]$data, [string]$token) {
    if (-not $cache.gasDeviceId -or $cache.gasDeviceId -eq 'none') { $data.GasKWh = ''; $data.GasAt = ''; $data.GasKW = ''; $cache.gasRunMin = 0; return }
    $now   = Get-Date
    $today = $now.ToString('yyyy-MM-dd')
    $nowS  = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($cache.gasDay -eq $today -and $cache.gasFetched -and ($nowS - [long]$cache.gasFetched) -lt $GasEverySecs) { return }

    # From 3 hours before midnight, so a run that started yesterday evening is counted
    $start = $now.Date.AddHours(-3).ToString('yyyy-MM-ddTHH:mm:sszzz')
    $end   = $now.ToString('yyyy-MM-ddTHH:mm:sszzz')
    $q = 'query { gas: smartMeterTelemetry(deviceId: "' + (Clean $cache.gasDeviceId) + '", grouping: HALF_HOURLY, start: "' + $start + '", end: "' + $end + '") { readAt consumptionDelta } }'
    try {
        $d = Invoke-Gql $q $token
        $rows = Convert-Rows $d.gas
        $midnight = ([DateTimeOffset]$now.Date).ToUnixTimeSeconds()
        $todayRows = New-Object 'System.Collections.Generic.List[object]'
        foreach ($r in $rows) { if ([long]$r[0] -ge $midnight) { $todayRows.Add($r) } }
        $todayRows = $todayRows.ToArray()
        $wh = 0.0; $last = $null
        foreach ($r in $todayRows) { $wh += $r[1]; if ($r[1] -gt 0) { $last = $r[0] } }
        # Latest half-hour in Wh -> average kW over that half-hour
        $kw = 0.0; if ($rows.Count -gt 0) { $kw = $rows[$rows.Count - 1][1] / 500.0 }
        $data.GasKW = $kw.ToString('0.00', $Inv)
        $data.GasKWh = ([Math]::Round($wh / 1000.0, 2)).ToString('0.00', $Inv)
        $data.GasAt = if ($last) { [DateTimeOffset]::FromUnixTimeSeconds($last).ToLocalTime().ToString('HH:mm') } else { '--:--' }
        # How long the gas has been on: the latest half-hours in a row with real use
        $n = 0
        for ($i = $rows.Count - 1; $i -ge 0; $i--) { if ($rows[$i][1] -lt $GasOnWh) { break }; $n++ }
        $cache.gasRunMin = $n * 30
        $cache.gasKwAt = if ($rows.Count -gt 0) { $rows[$rows.Count - 1][0] } else { $null }
        $cache.gasSlots = $todayRows
        $cache.gasSlotsDay = $today
        $cache.gasSlotsAt = $nowS
        $cache.gasDay = $today
        $cache.gasFetched = $nowS
    } catch {
        # Keep the last gas figure; try again on the next refresh
        Add-Content -Path $ErrorFile -Value ("{0}  Gas: {1}" -f (Get-Date -Format 's'), $_.Exception.Message) -Encoding ASCII
        if ($cache.gasDay -ne $today) { $data.GasKWh = '0.00'; $data.GasAt = '--:--'; $data.GasKW = '0.00'; $cache.gasRunMin = 0 }
    }
}

# Reads the latest values from your own Octopus Live relay server (see /server in the repo).
function Update-FromRelay([string]$relayUrl, [hashtable]$data, [hashtable]$cache) {
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
            $cache.gasRunMin = if ($null -ne $r.gasRunMinutes) { [int]$r.gasRunMinutes } else { 0 }
            $cache.gasKwAt = $null
            $t = [DateTimeOffset]::MinValue
            if ($r.gasKwAt -and [DateTimeOffset]::TryParse("$($r.gasKwAt)", $inv, [Globalization.DateTimeStyles]::None, [ref]$t)) { $cache.gasKwAt = $t.ToUnixTimeSeconds() }
        } else { $data.GasKWh = ''; $data.GasAt = ''; $data.GasKW = ''; $cache.gasRunMin = 0 }
    }
    $data.Status = if ($r.ok) { 'OK' } else { "Relay: $($r.status)" }
}

# ---------- prices, costs and today's half-hours (for the newer skins) ----------
# These need the API key: the tariff comes from your account (once a day) and the prices from
# Octopus's public price list. Today's half-hours come from the meter every 30 minutes or so.

function Get-Tariffs([hashtable]$cache, [string]$token) {
    $nowS = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($cache.elecTariff -and $cache.tariffAt -and ($nowS - [long]$cache.tariffAt) -lt 86400) { return }
    $q = 'query { account(accountNumber: "' + (Clean $cache.account) + '") { ' +
         'electricityAgreements(active: true) { meterPoint { direction agreements(includeInactive: true) { validFrom validTo tariff { ... on TariffType { productCode tariffCode } } } } } ' +
         'gasAgreements(active: true) { meterPoint { agreements(includeInactive: true) { validFrom validTo tariff { tariffCode productCode } } } } } }'
    $d = Invoke-Gql $q $token
    function Current($points) {
        $best = $null; $bestFrom = $null
        $now = [DateTimeOffset]::UtcNow
        foreach ($mp in @($points)) {
            foreach ($a in @($mp.meterPoint.agreements)) {
                if (-not $a -or -not $a.tariff) { continue }
                $from = [DateTimeOffset]::MinValue; $to = [DateTimeOffset]::MaxValue
                if ($a.validFrom) { [void][DateTimeOffset]::TryParse("$($a.validFrom)", $Inv, [Globalization.DateTimeStyles]::None, [ref]$from) }
                if ($a.validTo) { [void][DateTimeOffset]::TryParse("$($a.validTo)", $Inv, [Globalization.DateTimeStyles]::None, [ref]$to) }
                if ($from -le $now -and $now -lt $to -and (-not $bestFrom -or $from -gt $bestFrom)) { $best = $a.tariff; $bestFrom = $from }
            }
        }
        return $best
    }
    $elec = Current (@($d.account.electricityAgreements) | Where-Object { $_.meterPoint.direction -ne 'EXPORT' })
    $gas = Current $d.account.gasAgreements
    $cache.elecProduct = if ($elec) { "$($elec.productCode)" } else { $null }
    $cache.elecTariff  = if ($elec) { "$($elec.tariffCode)" } else { $null }
    $cache.gasProduct  = if ($gas) { "$($gas.productCode)" } else { $null }
    $cache.gasTariff   = if ($gas) { "$($gas.tariffCode)" } else { $null }
    $cache.tariffAt = $nowS
    $cache.ratesAt = 0      # the tariff may have changed: fetch prices again
}

# One price list (public, no key) -> @( @(from, to, pence), ... ) in unix seconds, oldest first
function Get-Rates([string]$url, [DateTimeOffset]$from, [DateTimeOffset]$to) {
    $f = $from.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ'); $t = $to.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
    $next = "$url" + '?period_from=' + $f + '&period_to=' + $t + '&page_size=1500'
    $all = @(); $pages = 0
    while ($next -and $pages -lt 5) {
        $r = Invoke-RestMethod -Uri $next -Method Get -TimeoutSec 30 -UseBasicParsing
        $all += @($r.results); $next = $r.next; $pages++
    }
    # Prefer Direct Debit prices where both are listed
    $dd = @($all | Where-Object { -not $_.payment_method -or "$($_.payment_method)" -eq 'DIRECT_DEBIT' })
    if ($dd.Count -eq 0) { $dd = $all }
    $out = New-Object 'System.Collections.Generic.List[object]'
    foreach ($x in $dd) {
        if ($null -eq $x.value_inc_vat) { continue }
        $a = [long]::MinValue; $b = [long]::MaxValue
        $tmp = [DateTimeOffset]::MinValue
        if ($x.valid_from -and [DateTimeOffset]::TryParse("$($x.valid_from)", $Inv, [Globalization.DateTimeStyles]::None, [ref]$tmp)) { $a = $tmp.ToUnixTimeSeconds() }
        if ($x.valid_to -and [DateTimeOffset]::TryParse("$($x.valid_to)", $Inv, [Globalization.DateTimeStyles]::None, [ref]$tmp)) { $b = $tmp.ToUnixTimeSeconds() }
        $out.Add(@($a, $b, [double]$x.value_inc_vat))
    }
    return ,(Sort-Rows $out)
}

function Update-Rates([hashtable]$cache) {
    if (-not $cache.elecTariff -or -not $cache.elecProduct) { return }
    $now = Get-Date
    $today = $now.ToString('yyyy-MM-dd')
    $nowS = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    # Agile and similar publish tomorrow's prices around 16:00: fetch again soon after that
    $publish = ([DateTimeOffset]$now.Date.AddHours(16.25)).ToUnixTimeSeconds()
    $missed = $nowS -ge $publish -and [long]$cache.ratesAt -lt $publish
    if ($cache.ratesDay -eq $today -and ($nowS - [long]$cache.ratesAt) -lt 6 * 3600 -and -not $missed) { return }
    $from = [DateTimeOffset]$now.Date; $to = [DateTimeOffset]$now.Date.AddDays(2)
    $e = $Rest + 'products/' + [Uri]::EscapeDataString($cache.elecProduct) + '/electricity-tariffs/' + [Uri]::EscapeDataString($cache.elecTariff) + '/'
    $cache.elecRates = Get-Rates ($e + 'standard-unit-rates/') $from $to
    $cache.elecStanding = Get-Rates ($e + 'standing-charges/') $from $to
    if ($cache.gasTariff -and $cache.gasProduct) {
        $g = $Rest + 'products/' + [Uri]::EscapeDataString($cache.gasProduct) + '/gas-tariffs/' + [Uri]::EscapeDataString($cache.gasTariff) + '/'
        $cache.gasRates = Get-Rates ($g + 'standard-unit-rates/') $from $to
        $cache.gasStanding = Get-Rates ($g + 'standing-charges/') $from $to
    } else { $cache.gasRates = @(); $cache.gasStanding = @() }
    $cache.ratesDay = $today
    $cache.ratesAt = $nowS
}

# Today's half-hours, when the live fetch hasn't just got them (always, when using a relay)
function Update-Slots([hashtable]$cache, [string]$token) {
    $today = (Get-Date).ToString('yyyy-MM-dd')
    $nowS = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $hasGas = $cache.gasDeviceId -and $cache.gasDeviceId -ne 'none'
    $needElec = $cache.elecSlotsDay -ne $today -or ($nowS - [long]$cache.elecSlotsAt) -ge $SlotsEvery
    $needGas = $hasGas -and ($cache.gasSlotsDay -ne $today -or ($nowS - [long]$cache.gasSlotsAt) -ge $SlotsEvery)
    if (-not $needElec -and -not $needGas) { return }
    $now = Get-Date
    $start = $now.Date.ToString('yyyy-MM-ddTHH:mm:sszzz'); $end = $now.ToString('yyyy-MM-ddTHH:mm:sszzz')
    $q = 'query { '
    if ($needElec) { $q += 'elec: smartMeterTelemetry(deviceId: "' + (Clean $cache.deviceId) + '", grouping: HALF_HOURLY, start: "' + $start + '", end: "' + $end + '") { readAt consumptionDelta } ' }
    if ($needGas) { $q += 'gas: smartMeterTelemetry(deviceId: "' + (Clean $cache.gasDeviceId) + '", grouping: HALF_HOURLY, start: "' + $start + '", end: "' + $end + '") { readAt consumptionDelta } ' }
    $d = Invoke-Gql ($q + '}') $token
    if ($needElec) { Save-ElecSlots $cache $d.elec }
    if ($needGas) { $cache.gasSlots = Convert-Rows $d.gas; $cache.gasSlotsDay = $today; $cache.gasSlotsAt = $nowS }
}

function Rate-At($list, [long]$t) {
    $best = $null
    foreach ($r in @($list)) { if ($r -and [long]$r[0] -le $t -and $t -lt [long]$r[1]) { $best = [double]$r[2] } }
    return $best
}

# Pence -> "5p" or "£1.12"
function Money([double]$pence) {
    if ($pence -lt 99.5) { return ([Math]::Round($pence)).ToString('0', $Inv) + 'p' }
    return [char]0x00A3 + ($pence / 100).ToString('0.00', $Inv)
}

function Duration([int]$min) {
    if ($min -lt 60) { return "$min min" }
    if ($min % 60 -eq 0) { return "$([int]($min / 60)) h" }
    return "$([Math]::Floor($min / 60)) h $($min % 60) min"
}

# Pence for half-hours (+ that day's standing charge); $null if a used half-hour has no price
function Cost-Of($slots, $unit, $standing, [long]$dayStart) {
    if (-not $unit -or @($unit).Count -eq 0) { return $null }
    $p = 0.0
    foreach ($s in @($slots)) {
        if (-not $s -or [double]$s[1] -le 0) { continue }
        $r = Rate-At $unit ([long]$s[0]); if ($null -eq $r) { return $null }
        $p += [double]$s[1] / 1000.0 * $r
    }
    $sc = Rate-At $standing ($dayStart + 12 * 3600); if ($null -ne $sc) { $p += $sc }
    return $p
}

# Fills in everything the newer skins show, from data.txt's live values and the cache
function Set-Display([hashtable]$cache, [hashtable]$data, [bool]$canCost) {
    $sep = ' ' + [char]0x00B7 + ' '
    $num = 0.0
    $watts = $null; if ([double]::TryParse("$($data.Demand)", [Globalization.NumberStyles]::Float, $Inv, [ref]$num)) { $watts = $num }
    $elecKwh = $null; if ([double]::TryParse("$($data.TodayKWh)", [Globalization.NumberStyles]::Float, $Inv, [ref]$num)) { $elecKwh = $num }
    $gasKwh = $null; if ([double]::TryParse("$($data.GasKWh)", [Globalization.NumberStyles]::Float, $Inv, [ref]$num)) { $gasKwh = $num }
    $hasGas = "$($data.GasKWh)" -ne ''
    $ok = "$($data.Status)" -eq 'OK'

    # Live electricity, split like Home: "420" + "W" or "3.42" + "kW"
    if ($null -eq $watts) { $data.ElecNum = [char]0x2014; $data.ElecUnit = '' }
    elseif ($watts -lt 1000) { $data.ElecNum = ([Math]::Round($watts)).ToString('0', $Inv); $data.ElecUnit = 'W' }
    else { $data.ElecNum = ($watts / 1000).ToString('0.00', $Inv); $data.ElecUnit = 'kW' }

    $nowS = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $dayStart = ([DateTimeOffset](Get-Date).Date).ToUnixTimeSeconds()
    $today = (Get-Date).ToString('yyyy-MM-dd')
    $priceNow = if ($canCost) { Rate-At $cache.elecRates $nowS } else { $null }

    if (-not $ok) { $data.PerHour = "$($data.Status)" }
    elseif ($null -ne $watts -and $null -ne $priceNow) { $data.PerHour = 'About ' + (Money ($watts / 1000.0 * $priceNow)) + ' an hour' }
    else { $data.PerHour = "Meter reading at $($data.ReadAt)" }

    # Gas: on for how long, or the half-hour the average is for
    $run = [int]$cache.gasRunMin
    $data.GasOn = if ($hasGas -and $run -gt 0) { '1' } else { '0' }
    if (-not $hasGas) { $data.GasSub = '' }
    elseif ($run -gt 0) { $data.GasSub = 'On for ' + (Duration $run) }
    elseif ($cache.gasKwAt) { $data.GasSub = 'Average to ' + [DateTimeOffset]::FromUnixTimeSeconds([long]$cache.gasKwAt + 1800).ToLocalTime().ToString('HH:mm') }
    else { $data.GasSub = '30-min average' }

    # Price now and the next different price
    $data.PriceNow = if ($null -ne $priceNow) { $priceNow.ToString('0.0', $Inv) + 'p' } else { [char]0x2014 }
    $data.PriceCheaper = '0'; $data.PriceNote = ''
    if (-not $canCost) { $data.PriceNote = 'Add your API key for costs' }
    elseif ($null -ne $priceNow) {
        foreach ($r in @($cache.elecRates)) {
            if ($r -and [long]$r[0] -gt $nowS -and [Math]::Abs([double]$r[2] - $priceNow) -gt 0.005) {
                $at = [DateTimeOffset]::FromUnixTimeSeconds([long]$r[0]).ToLocalTime().ToString('HH:mm')
                $data.PriceNote = ([double]$r[2]).ToString('0.0', $Inv) + 'p from ' + $at
                if ([double]$r[2] -lt $priceNow) { $data.PriceCheaper = '1'; $data.PriceNote = [char]0x2193 + ' ' + $data.PriceNote }
                break
            }
        }
    }

    # Today's costs: half-hours we have, plus anything newer at the current price
    $eSlots = if ($cache.elecSlotsDay -eq $today) { @($cache.elecSlots) } else { @() }
    $gSlots = if ($cache.gasSlotsDay -eq $today) { @($cache.gasSlots) } else { @() }
    $elecCost = $null; $gasCost = $null
    if ($canCost) {
        $elecCost = Cost-Of $eSlots $cache.elecRates $cache.elecStanding $dayStart
        if ($null -ne $elecCost) {
            $slotKwh = 0.0; foreach ($x in $eSlots) { if ($x) { $slotKwh += [double]$x[1] / 1000.0 } }
            if ($null -ne $elecKwh -and $null -ne $priceNow) { $elecCost += [Math]::Max(0, $elecKwh - $slotKwh) * $priceNow }
        }
        if ($hasGas) {
            $gasCost = Cost-Of $gSlots $cache.gasRates $cache.gasStanding $dayStart
            if ($null -ne $gasCost) {
                $gs = 0.0; foreach ($x in $gSlots) { if ($x) { $gs += [double]$x[1] / 1000.0 } }
                $gp = Rate-At $cache.gasRates $nowS
                if ($null -ne $gasKwh -and $null -ne $gp) { $gasCost += [Math]::Max(0, $gasKwh - $gs) * $gp }
            }
        }
    }
    $kwhText = { param($v, $f) if ($null -eq $v) { [char]0x2014 } else { $v.ToString($f, $Inv) + ' kWh' } }
    if ($null -ne $elecCost) { $data.CostTotal = Money ($elecCost + $(if ($null -ne $gasCost) { $gasCost } else { 0 })) }
    elseif ($null -ne $elecKwh) { $data.CostTotal = & $kwhText ($elecKwh + $(if ($null -ne $gasKwh) { $gasKwh } else { 0 })) '0.00' }
    else { $data.CostTotal = [char]0x2014 }
    $data.CostElec = if ($null -ne $elecCost) { Money $elecCost } else { & $kwhText $elecKwh '0.00' }
    $data.CostGas = if (-not $hasGas) { '' } elseif ($null -ne $gasCost) { Money $gasCost } else { & $kwhText $gasKwh '0.0' }

    # Chart: kWh per half-hour from midnight up to the current one
    $nowSlot = [Math]::Min(47, [Math]::Max(0, [int][Math]::Floor(($nowS - $dayStart) / 1800)))
    $kwh = New-Object double[] ($nowSlot + 1)
    $pence = New-Object 'object[]' ($nowSlot + 1)
    foreach ($x in $eSlots) {
        if (-not $x) { continue }
        $i = [int][Math]::Floor(([long]$x[0] - $dayStart) / 1800)
        if ($i -ge 0 -and $i -le $nowSlot) {
            $kwh[$i] = [double]$x[1] / 1000.0
            if ($canCost) { $r = Rate-At $cache.elecRates ([long]$x[0]); if ($null -ne $r) { $pence[$i] = $kwh[$i] * $r } }
        }
    }
    $data.Slots = ($kwh | ForEach-Object { $_.ToString('0.###', $Inv) }) -join ','
    $parts = @(); if ($null -ne $elecKwh) { $parts += $elecKwh.ToString('0.00', $Inv) + ' kWh' }
    if ($null -ne $elecCost) { $parts += Money $elecCost }
    $data.TodayLine = if ($parts.Count) { $parts -join $sep } else { [char]0x2014 }
    $peak = -1; $max = 0.0
    for ($i = 0; $i -le $nowSlot; $i++) { if ($kwh[$i] -gt $max) { $max = $kwh[$i]; $peak = $i } }
    $slotTime = { param($i) '{0:00}:{1:00}' -f [Math]::Floor($i / 2), $(if ($i % 2) { 30 } else { 0 }) }
    if (-not $canCost) { $data.Peak = 'Add your API key to see half-hours' }
    elseif ($peak -lt 0) { $data.Peak = 'No half-hours yet today' }
    else {
        $range = (& $slotTime $peak) + [char]0x2013 + (& $slotTime ($peak + 1)) + ' peak'
        $p = @($range, (($max * 2).ToString('0.00', $Inv) + ' kW avg'))
        if ($null -ne $pence[$peak]) { $p += Money $pence[$peak] }
        $data.Peak = $p -join $sep
    }
}

# ---------- main ----------

# One run at a time across all the skins; a second one just leaves
$mutex = $null
try {
    $mutex = New-Object System.Threading.Mutex($false, 'Local\OctopusLiveFetch')
    $got = $false
    try { $got = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $got = $true }
    if (-not $got) { exit 0 }
} catch { $mutex = $null }

$data = Read-KeyValueFile $DataFile
foreach ($k in $DataKeys) { if (-not $data.ContainsKey($k)) { $data[$k] = '' } }

try {
    $settings = Read-Settings
    $apiKey = "$($settings.ApiKey)"
    $relayUrl = "$($settings.RelayUrl)"

    # Not more often than the Interval, however many skins ask (10 s for a click / Refresh now)
    $interval = 60; [void][int]::TryParse("$($settings.Interval)", [ref]$interval)
    $gap = if ($Manual) { 10 } else { [Math]::Max(5, $interval - 3) }
    $nowRun = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $lastRun = 0L
    if (Test-Path $LastRunFile) { [void][long]::TryParse(((Get-Content $LastRunFile -Raw) -replace '\s', ''), [ref]$lastRun) }
    if ($nowRun - $lastRun -lt $gap -and $nowRun -ge $lastRun) { if ($mutex) { $mutex.ReleaseMutex() }; exit 0 }
    Set-Content -Path $LastRunFile -Value "$nowRun" -Encoding ASCII

    $cache = Read-Cache
    # Forget cached token/device/tariff if the key or account number changed
    $fingerprint = if ($apiKey) { $apiKey.Substring([Math]::Max(0, $apiKey.Length - 6)) + '|' + "$($settings.AccountNumber)" } else { '' }
    if ($cache.fingerprint -ne $fingerprint) { $cache = @{ fingerprint = $fingerprint } }

    $done = $false
    if ($relayUrl) {
        try {
            Update-FromRelay $relayUrl $data $cache
            $done = $true
            if (Test-Path $ErrorFile) { Remove-Item $ErrorFile -Force -ErrorAction SilentlyContinue }
        } catch {
            # No API key to fall back on: report the relay problem
            if (-not $apiKey) { throw }
        }
    }
    if (-not $done) {
    if (-not $apiKey) { throw 'Add your Octopus API key (or RelayUrl) to Settings.inc (right-click skin > Edit settings).' }

    # When the relay is down and the skin refreshes quickly, call Octopus at most once a minute
    $nowS = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($relayUrl -and $cache.lastDirect -and ($nowS - [long]$cache.lastDirect) -lt 60) {
        throw 'Server offline - using Octopus once a minute'
    }
    $cache.lastDirect = $nowS

    $result = $null
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        try {
            $token    = Get-Token $cache $apiKey
            $deviceId = Get-DeviceId $cache $token "$($settings.AccountNumber)"
            $todayKey = (Get-Date).ToString('yyyy-MM-dd')
            $nowSync  = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
            $needSync = ($cache.syncDay -ne $todayKey) -or ($null -eq $cache.syncMeterWh) -or
                        (-not $cache.syncTime) -or (($nowSync - [long]$cache.syncTime) -ge 1800)
            $result   = Get-Telemetry $token $deviceId $needSync
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

    $meterWh = $null
    if ($null -ne $live.consumption -and "$($live.consumption)" -ne '') { $meterWh = [double]$live.consumption }
    if ($needSync) {
        $todayWh = 0.0
        foreach ($row in @($result.today)) {
            if ($row -and $null -ne $row.consumptionDelta) { $todayWh += [double]$row.consumptionDelta }
        }
        Save-ElecSlots $cache $result.today
        $cache.syncDay = $todayKey; $cache.syncTime = $nowSync
        $cache.syncTodayWh = $todayWh; $cache.syncMeterWh = $meterWh
    } elseif ($null -ne $meterWh -and $meterWh -ge [double]$cache.syncMeterWh) {
        $todayWh = [double]$cache.syncTodayWh + ($meterWh - [double]$cache.syncMeterWh)
    } else {
        # Meter total missing or went backwards: keep the last figure, re-check next time
        $todayWh = [double]$cache.lastTodayWh
        $cache.syncMeterWh = $null
    }
    $cache.lastTodayWh = $todayWh

    $data.Demand   = if ($null -ne $live.demand) { [Math]::Round([double]$live.demand) } else { $data.Demand }
    $data.TodayKWh = ([Math]::Round($todayWh / 1000.0, 2)).ToString('0.00', $Inv)
    $readAt = [DateTimeOffset]::MinValue
    if ([DateTimeOffset]::TryParse("$($live.readAt)", $Inv, [Globalization.DateTimeStyles]::None, [ref]$readAt)) {
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

# Prices, costs and half-hours: never stop the live figures if these fail
$canCost = $false
if ($cache -and $apiKey) {
    try {
        $token = Get-Token $cache $apiKey
        [void](Get-DeviceId $cache $token "$($settings.AccountNumber)")
        Get-Tariffs $cache $token
        Update-Rates $cache
        Update-Slots $cache $token
        $canCost = [bool]$cache.elecTariff
    } catch {
        $canCost = [bool]$cache.elecTariff
        Add-Content -Path $ErrorFile -Value ("{0}  Prices: {1}" -f (Get-Date -Format 's'), $_.Exception.Message) -Encoding ASCII
    }
}
try { if ($cache) { Set-Display $cache $data $canCost; Save-Cache $cache } } catch {
    Add-Content -Path $ErrorFile -Value ("{0}  Display: {1}" -f (Get-Date -Format 's'), $_.Exception.Message) -Encoding ASCII
}

$data.Updated = (Get-Date).ToString('HH:mm:ss')
Write-Data $data
foreach ($k in $DataKeys) { Write-Output "$k=$($data[$k])" }
if ($mutex) { try { $mutex.ReleaseMutex() } catch {} }

exit 0
