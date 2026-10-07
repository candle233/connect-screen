param([string]$Serial, [string]$AdbPath)
. "$PSScriptRoot/Common.ps1"
Assert-Bundle
Initialize-Adb -AdbPath $AdbPath -Serial $Serial
$null = Invoke-DeviceAdb @('shell', 'am', 'force-stop', $script:TouchFixPackage)
# Exact process name; pkill code 1 means no such process remains.
$savedPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try { $result = @(& $script:AdbExecutable -s $script:DeviceSerial shell pkill -x touch_relay 2>&1); $exitCode = $LASTEXITCODE }
finally { $ErrorActionPreference = $savedPreference }
if ($exitCode -gt 1) { throw "Cannot terminate relay: $($result -join "`n")" }
$processes = Invoke-DeviceAdb @('shell', 'ps', '-A')
if ($processes -match '(?m)\btouch_relay\s*$') { throw 'Relay is still present. Touch release is unconfirmed.' }
Write-Host 'Touchfix force-stopped; no touch_relay process remains. Physically confirm original touch works.'
Write-Host 'Saved calibration is retained. Open the enable shortcut only when you want to resume.'
