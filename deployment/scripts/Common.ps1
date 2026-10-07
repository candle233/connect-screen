Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:TouchFixPackage = 'com.gitee.connect_screen.touchfix'
$script:BundleRoot = Split-Path $PSScriptRoot -Parent

function Initialize-Adb {
    param([string]$AdbPath, [string]$Serial)
    if (!$AdbPath) {
        $bundled = Join-Path $script:BundleRoot 'platform-tools/adb.exe'
        if (Test-Path -LiteralPath $bundled) { $AdbPath = $bundled }
        else {
            $command = Get-Command adb -ErrorAction SilentlyContinue
            if (!$command) { throw 'ADB not found. Supply -AdbPath or extract Google Platform Tools.' }
            $AdbPath = $command.Source
        }
    }
    $script:AdbExecutable = (Get-Command $AdbPath -ErrorAction Stop).Source
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $devices = @(& $script:AdbExecutable devices 2>&1); $exitCode = $LASTEXITCODE }
    finally { $ErrorActionPreference = $savedPreference }
    if ($exitCode -ne 0) { throw "adb devices failed: $($devices -join "`n")" }
    $entries = @($devices | ForEach-Object {
        if ("$_" -match '^([^\s]+)\s+(device|offline|unauthorized)(?:\s|$)') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    })
    if ($Serial) {
        $selected = @($entries | Where-Object Serial -EQ $Serial)
        if ($selected.Count -ne 1 -or $selected[0].State -ne 'device') {
            throw 'Selected phone is unavailable or unauthorized. Unlock it and approve USB debugging.'
        }
    } else {
        if ($entries.Count -ne 1 -or $entries[0].State -ne 'device') {
            throw 'Connect and authorize exactly one phone, or supply -Serial from adb devices.'
        }
        $Serial = $entries[0].Serial
    }
    $script:DeviceSerial = $Serial
    Write-Host "Selected device: $Serial"
}

function Invoke-DeviceAdb {
    param([string[]]$Arguments)
    # Windows PowerShell 5 treats native stderr as ErrorRecord, even on exit 0.
    # ADB writes successful push progress to stderr; use the actual exit code.
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $result = @(& $script:AdbExecutable -s $script:DeviceSerial @Arguments 2>&1); $exitCode = $LASTEXITCODE }
    finally { $ErrorActionPreference = $savedPreference }
    if ($exitCode -ne 0) {
        throw "ADB failed ($exitCode): $($Arguments -join ' ')`n$($result -join "`n")"
    }
    return ($result -join "`n").Trim()
}

function Assert-Bundle {
    $manifestPath = Join-Path $script:BundleRoot 'manifest.json'
    $script:BundleManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($script:BundleManifest.applicationId -ne $script:TouchFixPackage) {
        throw 'Unexpected application ID in manifest.'
    }
    foreach ($file in $script:BundleManifest.files) {
        $path = [IO.Path]::GetFullPath((Join-Path $script:BundleRoot $file.path))
        $prefix = [IO.Path]::GetFullPath($script:BundleRoot).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (!$path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Manifest path escapes bundle directory.'
        }
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne $file.sha256) { throw "Bundle checksum mismatch: $($file.path)" }
    }
    foreach ($required in @('touchfix.apk', 'native/touch_relay', 'scripts/Common.ps1',
            'scripts/Install-TouchFix.ps1', 'scripts/Check-Device.ps1', 'scripts/Stop-TouchFix.ps1')) {
        if (@($script:BundleManifest.files | Where-Object path -EQ $required).Count -ne 1) {
            throw "Required file not covered by manifest: $required"
        }
    }
    Write-Host 'Bundle SHA256 checks passed.'
}

function Get-PhoneInfo {
    $model = Invoke-DeviceAdb @('shell', 'getprop', 'ro.product.model')
    $android = Invoke-DeviceAdb @('shell', 'getprop', 'ro.build.version.release')
    $sdk = Invoke-DeviceAdb @('shell', 'getprop', 'ro.build.version.sdk')
    $abi = Invoke-DeviceAdb @('shell', 'getprop', 'ro.product.cpu.abilist')
    if ($sdk -notmatch '^\d+$') { throw 'Cannot determine Android API level.' }
    Write-Host "Phone: $model / Android $android / API $sdk / ABI $abi"
    return [pscustomobject]@{ Model = $model; Android = $android; Sdk = [int]$sdk; Abi = $abi }
}

function Assert-PhonePlatform {
    param($Phone)
    if ($Phone.Sdk -lt 29) { throw 'This APK requires Android 10 / API 29 or later.' }
    if ('arm64-v8a' -notin ($Phone.Abi -split ',')) { throw 'The native relay requires arm64-v8a.' }
}

function Get-PanelAssessment {
    param([string]$Capabilities, [string]$Devices)
    $blocks = [regex]::Matches($Capabilities, '(?ms)^add device \d+: (?<path>/dev/input/event\d+)\r?\n(?<body>.*?)(?=^add device \d+:|\z)')
    $candidates = @($blocks | Where-Object {
        $_.Groups['body'].Value -match 'name:\s+"ILITEK ILITEK-TP"'
    })
    if ($candidates.Count -ne 1) { return "NOT READY: expected one ILITEK panel, observed $($candidates.Count). Connect the panel and retry." }
    $path = $candidates[0].Groups['path'].Value
    $body = $candidates[0].Groups['body'].Value
    if ($path -eq '/dev/input/event2') { return 'BLOCKED: this baseline deliberately excludes event2. Device-specific adaptation is needed.' }
    foreach ($axis in @('ABS_MT_POSITION_X', 'ABS_MT_POSITION_Y')) {
        if ($body -notmatch ($axis + '\s*:.*?min 0, max 16384(?:,|\s|$)')) {
            return "BLOCKED: $axis is not in the required raw range 0..16384."
        }
    }
    if ($body -notmatch 'INPUT_PROP_DIRECT' -or $body -notmatch 'ABS_MT_SLOT') {
        return 'BLOCKED: panel lacks direct MT slot capabilities required by the relay.'
    }
    $event = [IO.Path]::GetFileName($path)
    $usb = @([regex]::Split($Devices.Trim(), '\r?\n\s*\r?\n') | Where-Object {
        $_ -match '(?m)^I: Bus=0003\b' -and
        $_ -match '(?m)^N: Name="ILITEK ILITEK-TP"' -and
        $_ -match ('(?m)^H: Handlers=.*\b' + [regex]::Escape($event) + '\b')
    })
    if ($usb.Count -ne 1) { return 'UNCONFIRMED: cannot verify the panel USB bus from /proc/bus/input/devices.' }
    return "CANDIDATE OK: $path. No input was grabbed. Native access and physical accuracy still require on-phone testing."
}
