# Ensures the Shizuku server is running on a connected Android phone via adb.
#
# Intended for a Windows scheduled task (register with Register-ShizukuAutoStart.ps1)
# and for manual runs. Safe to run repeatedly: the official starter kills the old
# shizuku_server process before launching a new one, so this script first checks for
# a running server and only invokes the starter when none is found.
#
# The starter used is libshizuku.so inside the installed Shizuku manager APK
# (Shizuku 13+), the same binary the app offers for "start via wireless debugging
# by connecting to a computer". It runs as the adb shell user (uid 2000).
param(
    [string]$AdbPath,
    [string]$Serial,
    [int]$WaitSeconds = 240,
    [int]$PollSeconds = 6
)

$ErrorActionPreference = 'Stop'
$Package = 'moe.shizuku.privileged.api'
$ServerProcess = 'shizuku_server'
$LogDir = Join-Path $env:LOCALAPPDATA 'ShizukuAutoStart'
$LogFile = Join-Path $LogDir 'autostart.log'

function Write-Log([string]$Message) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Write-Host $line
    try {
        if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
        if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt 1MB)) {
            Move-Item -Force $LogFile ($LogFile + '.old')
        }
        Add-Content -Path $LogFile -Value $line -Encoding UTF8
    } catch { }
}

if (-not $AdbPath) {
    $found = Get-Command adb.exe -ErrorAction SilentlyContinue
    if (-not $found) {
        Write-Log 'ERROR: adb.exe not found in PATH. Pass -AdbPath explicitly.'
        exit 2
    }
    $AdbPath = $found.Source
} elseif (-not (Test-Path $AdbPath)) {
    $candidate = Join-Path $AdbPath 'adb.exe'
    if (Test-Path $candidate) { $AdbPath = $candidate }
}
if (-not (Test-Path $AdbPath)) {
    Write-Log "ERROR: adb not found at '$AdbPath'."
    exit 2
}

function Get-DeviceList {
    $list = @()
    foreach ($line in (& $AdbPath devices)) {
        if ($line -match '^(\S+)\s+(\S+)\s*$') {
            $list += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    }
    return $list
}

# Wait for the phone to show up (mDNS auto-connect for paired wireless debugging,
# or USB). Missing phone is a normal outcome, not an error.
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$device = $null
do {
    $devices = Get-DeviceList
    $candidates = @($devices | Where-Object { $_.State -eq 'device' })
    if ($Serial) {
        $device = $candidates | Where-Object { $_.Serial -eq $Serial -or $_.Serial -like "*$Serial*" } | Select-Object -First 1
    } else {
        $device = $candidates | Select-Object -First 1
    }
    if ($device) { break }
    $blocked = $devices | Where-Object { $_.State -ne 'device' } | Select-Object -First 1
    if ($blocked) {
        Write-Log "Device $($blocked.Serial) is in state '$($blocked.State)'; waiting for authorization."
    }
    Start-Sleep -Seconds $PollSeconds
} while ((Get-Date) -lt $deadline)

if (-not $device) {
    $target = ''
    if ($Serial) { $target = " matching '$Serial'" }
    Write-Log "No adb device in 'device' state$target after ${WaitSeconds}s. Nothing to do."
    exit 0
}
if (@($devices | Where-Object { $_.State -eq 'device' }).Count -gt 1 -and -not $Serial) {
    Write-Log 'WARNING: multiple adb devices connected and no -Serial given; using the first one.'
}
Write-Log "Using device $($device.Serial)."

function Invoke-DeviceShell([string]$CommandLine) {
    return @(& $AdbPath -s $device.Serial shell $CommandLine)
}

# Shizuku must be installed; the starter lives inside its APK directory.
$pmPath = Invoke-DeviceShell "pm path $Package" | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if (-not $pmPath) {
    Write-Log "ERROR: Shizuku ($Package) is not installed on the phone."
    exit 1
}
$apkPath = $pmPath.Substring(8).Trim()
$starter = $apkPath -replace 'base\.apk$', 'lib/arm64/libshizuku.so'
if ($starter -eq $apkPath) {
    Write-Log "ERROR: unexpected package path '$apkPath'; cannot locate the starter."
    exit 1
}
$starterCheck = (Invoke-DeviceShell "ls $starter") -join ''
if ($starterCheck -notmatch 'libshizuku\.so') {
    Write-Log "ERROR: starter not found at '$starter'. Is this Shizuku 13+?"
    exit 1
}

# Already running -> do nothing. Running the starter anyway would kill and restart
# the live server, dropping any Shizuku UserService session (e.g. active touch fix).
$running = Invoke-DeviceShell "ps -A | grep -w $ServerProcess" | Select-Object -First 1
if ($running -match $ServerProcess) {
    Write-Log "Shizuku server already running (pid $(($running -split '\s+')[1])). Nothing to do."
    exit 0
}

Write-Log 'Shizuku server not running; starting via official starter...'
$startOutput = Invoke-DeviceShell $starter
Write-Log (($startOutput | Where-Object { $_ }) -join ' | ')

$deadline = (Get-Date).AddSeconds(20)
do {
    Start-Sleep -Seconds 2
    $running = Invoke-DeviceShell "ps -A | grep -w $ServerProcess" | Select-Object -First 1
    if ($running -match $ServerProcess) {
        Write-Log "Shizuku server started (pid $(($running -split '\s+')[1]))."
        exit 0
    }
} while ((Get-Date) -lt $deadline)

Write-Log 'ERROR: shizuku_server did not appear within 20s. On the phone check: wireless debugging on, ColorOS permission monitoring off, Shizuku not force-stopped.'
exit 1
