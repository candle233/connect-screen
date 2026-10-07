param([string]$Serial, [string]$AdbPath, [switch]$ValidateOnly)
. "$PSScriptRoot/Common.ps1"
Assert-Bundle
if ($ValidateOnly) { Write-Host 'Local package validated; no phone commands were executed.'; return }
Initialize-Adb -AdbPath $AdbPath -Serial $Serial
$phone = Get-PhoneInfo
Assert-PhonePlatform $phone
# Do not overwrite an executable while the current proxy may own an input device.
$processes = Invoke-DeviceAdb @('shell', 'ps', '-A')
if ($processes -match '(?m)\btouch_relay\s*$') {
    throw 'A touch relay is running. Use stop-touchfix.cmd before updating this installation.'
}
$apk = Join-Path $script:BundleRoot 'touchfix.apk'
$relay = Join-Path $script:BundleRoot 'native/touch_relay'
Write-Host (Invoke-DeviceAdb @('install', '-r', $apk))
# Package replacement can notify a saved boot receiver; stop it before deployment.
$null = Invoke-DeviceAdb @('shell', 'am', 'force-stop', $script:TouchFixPackage)
$processes = Invoke-DeviceAdb @('shell', 'ps', '-A')
if ($processes -match '(?m)\btouch_relay\s*$') { throw 'Relay release after package replacement is unconfirmed.' }
Write-Host (Invoke-DeviceAdb @('push', $relay, '/data/local/tmp/touch_relay'))
$null = Invoke-DeviceAdb @('shell', 'chmod', '755', '/data/local/tmp/touch_relay')
$remoteHash = Invoke-DeviceAdb @('shell', 'sha256sum', '/data/local/tmp/touch_relay')
$expected = @($script:BundleManifest.files | Where-Object path -EQ 'native/touch_relay')[0].sha256
if (($remoteHash -split '\s+')[0].ToLowerInvariant() -ne $expected) {
    throw 'Installed relay checksum mismatch. Do not enable touch correction.'
}
$null = Invoke-DeviceAdb @('shell', 'test', '-x', '/data/local/tmp/touch_relay')
$installed = Invoke-DeviceAdb @('shell', 'dumpsys', 'package', $script:TouchFixPackage)
if ($installed -notmatch ('\bversionCode=' + $script:BundleManifest.versionCode + '\b')) {
    throw 'Installed APK version could not be verified.'
}
$shizuku = Invoke-DeviceAdb @('shell', 'pm', 'path', 'moe.shizuku.privileged.api')
if ($shizuku -notmatch '^package:') { Write-Warning 'Install Shizuku from its official site before enabling touch correction.' }
Write-Host 'APK and ARM64 relay installed and verified. No touch correction was started.'
Write-Host 'Next: start Shizuku, authorize this app, connect the panel, and perform five-point calibration. Read INSTALL_ZH.md.'
