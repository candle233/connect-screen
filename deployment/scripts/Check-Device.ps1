param([string]$Serial, [string]$AdbPath, [string]$OutputDirectory)
. "$PSScriptRoot/Common.ps1"
Assert-Bundle
Initialize-Adb -AdbPath $AdbPath -Serial $Serial
$phone = Get-PhoneInfo
Assert-PhonePlatform $phone
if (!$OutputDirectory) { $OutputDirectory = Join-Path $script:BundleRoot ('reports/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$phone | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'phone.json') -Encoding UTF8
$checks = @(
    @{ Name='input-capabilities.txt'; Args=@('shell','getevent','-lp') },
    @{ Name='input-devices.txt'; Args=@('shell','cat','/proc/bus/input/devices') },
    @{ Name='display.txt'; Args=@('shell','dumpsys','display') },
    @{ Name='touchfix-package.txt'; Args=@('shell','dumpsys','package',$script:TouchFixPackage) },
    @{ Name='shizuku-package.txt'; Args=@('shell','pm','path','moe.shizuku.privileged.api') },
    @{ Name='relay-file.txt'; Args=@('shell','ls','-l','/data/local/tmp/touch_relay') },
    @{ Name='relay-sha256.txt'; Args=@('shell','sha256sum','/data/local/tmp/touch_relay') },
    @{ Name='processes.txt'; Args=@('shell','ps','-A') }
)
$outputs = @{}
foreach ($check in $checks) {
    try { $content = Invoke-DeviceAdb $check.Args }
    catch { $content = "UNAVAILABLE: $($_.Exception.Message)"; Write-Warning "$($check.Name): unavailable" }
    $outputs[$check.Name] = $content
    $content | Set-Content -LiteralPath (Join-Path $OutputDirectory $check.Name) -Encoding UTF8
}
$assessment = Get-PanelAssessment $outputs['input-capabilities.txt'] $outputs['input-devices.txt']
$assessment | Set-Content -LiteralPath (Join-Path $OutputDirectory 'assessment.txt') -Encoding UTF8
Write-Host $assessment
Write-Host "Report: $([IO.Path]::GetFullPath($OutputDirectory))"
Write-Host 'Read-only inspection complete. No relay was executed or input grabbed.'
