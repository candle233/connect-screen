# Registers a per-user scheduled task that runs Start-Shizuku.ps1 at logon and
# then every N minutes while the user is logged on. The task runs hidden and
# targets the phone optionally given by -Serial (a serial fragment such as the
# USB serial works; it also matches wireless mDNS serials).
#
# The task runs in this user's session on purpose: adb pairing credentials and
# the adb server live in the user profile, so a SYSTEM/boot task could not
# reconnect to a paired wireless-debugging phone.
#
# Remove with Unregister-ShizukuAutoStart.ps1.
param(
    [string]$AdbPath,
    [string]$Serial,
    [int]$RepeatMinutes = 15,
    [int]$WaitSeconds = 240,
    [string]$TaskName = 'ShizukuAutoStart'
)

$ErrorActionPreference = 'Stop'

$starter = Join-Path $PSScriptRoot 'Start-Shizuku.ps1'
if (-not (Test-Path $starter)) { throw "Start-Shizuku.ps1 not found next to this script: $starter" }

if (-not $AdbPath) {
    $found = Get-Command adb.exe -ErrorAction SilentlyContinue
    if (-not $found) { throw 'adb.exe not found in PATH; pass -AdbPath explicitly.' }
    $AdbPath = $found.Source
}
if (-not (Test-Path $AdbPath)) {
    $candidate = Join-Path $AdbPath 'adb.exe'
    if (-not (Test-Path $candidate)) { throw "adb.exe not found at '$AdbPath'." }
    $AdbPath = $candidate
}

$wscript = Join-Path $env:SystemRoot 'System32\wscript.exe'
$launcher = Join-Path $PSScriptRoot 'run-hidden.vbs'
if (-not (Test-Path $launcher)) { throw "run-hidden.vbs not found next to this script: $launcher" }

$arguments = '"' + $launcher + '"'
$arguments += ' -AdbPath "' + $AdbPath + '"'
$arguments += ' -WaitSeconds ' + $WaitSeconds
if ($Serial) { $arguments += ' -Serial "' + $Serial + '"' }

$action = New-ScheduledTaskAction -Execute $wscript -Argument $arguments -WorkingDirectory $PSScriptRoot

# Logon trigger plus an indefinite repetition while the user stays logged on.
$logonTrigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$repeat = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes $RepeatMinutes) `
    -RepetitionDuration (New-TimeSpan -Days 3650)
$logonTrigger.Repetition = $repeat.Repetition

$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 15) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $logonTrigger `
    -Settings $settings -Principal $principal `
    -Description 'Starts the Shizuku server on the connected phone via adb. Runs hidden at logon and every N minutes; does nothing if the server is already running or the phone is not reachable.' -Force | Out-Null

Write-Host "Scheduled task '$TaskName' registered."
Write-Host "  Trigger : at logon, repeated every $RepeatMinutes minute(s), hidden window"
Write-Host "  Script  : $starter"
Write-Host "  adb     : $AdbPath"
if ($Serial) { Write-Host "  Serial  : $Serial" }
Write-Host '  Log     : %LOCALAPPDATA%\ShizukuAutoStart\autostart.log'
Write-Host "Remove it with: powershell -File `"$PSScriptRoot\Unregister-ShizukuAutoStart.ps1`""
