param([Parameter(Mandatory=$true)][string]$BundleDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$BundleDirectory = (Resolve-Path -LiteralPath $BundleDirectory).Path
$testRoot = Join-Path $projectRoot ('diagnostics/deployment-tests-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$null = New-Item -ItemType Directory -Path $testRoot
$mockPath = Join-Path $testRoot 'mock-adb.cmd'
@'
@echo off
echo %*>>"%~dp0trace.txt"
if "%~1"=="devices" (
  echo List of devices attached
  if "%TOUCHFIX_TEST_SCENARIO%"=="none" exit /b 0
  if "%TOUCHFIX_TEST_SCENARIO%"=="unauthorized" (
    echo MOCK_RENO8 unauthorized
    exit /b 0
  )
  echo MOCK_RENO8 device
  if "%TOUCHFIX_TEST_SCENARIO%"=="multiple" echo OTHER_PHONE device
  exit /b 0
)
if not "%~1"=="-s" exit /b 20
if not "%~2"=="MOCK_RENO8" exit /b 21
if "%~3"=="install" (
  if "%TOUCHFIX_TEST_SCENARIO%"=="installFailure" (
    echo Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]
    goto install_failed
  )
  echo Success
  exit /b 0
)
if "%~3"=="push" (
  if "%TOUCHFIX_TEST_SCENARIO%"=="stderrSuccess" echo 1 file pushed 1>&2
  echo 1 file pushed
  exit /b 0
)
if not "%~3"=="shell" exit /b 22
if "%~4"=="getprop" (
  if "%~5"=="ro.product.model" echo Reno8 5G
  if "%~5"=="ro.build.version.release" echo 14
  if "%~5"=="ro.build.version.sdk" (
    if "%TOUCHFIX_TEST_SCENARIO%"=="oldAndroid" (echo 28) else (echo 34)
  )
  if "%~5"=="ro.product.cpu.abilist" (
    if "%TOUCHFIX_TEST_SCENARIO%"=="x86" (echo x86_64) else (echo arm64-v8a,armeabi-v7a)
  )
  exit /b 0
)
if "%~4"=="ps" (
  echo USER PID PPID NAME
  if "%TOUCHFIX_TEST_SCENARIO%"=="active" echo shell 123 100 touch_relay
  exit /b 0
)
if "%~4"=="sha256sum" (
  if "%TOUCHFIX_TEST_SCENARIO%"=="badRemoteHash" (echo BADHASH /data/local/tmp/touch_relay) else (echo %TOUCHFIX_TEST_HASH% /data/local/tmp/touch_relay)
  exit /b 0
)
if "%~4"=="dumpsys" (
  if "%~5"=="package" echo versionCode=42 versionName=1.3.3-touchfix.10
  if "%~5"=="display" echo Display 0 1080x1920
  exit /b 0
)
if "%~4"=="pm" (
  echo package:/data/app/mock-shizuku/base.apk
  exit /b 0
)
if "%~4"=="getevent" (
  type "%~dp0capabilities.txt"
  exit /b 0
)
if "%~4"=="cat" (
  type "%~dp0input-devices.txt"
  exit /b 0
)
if "%~4"=="pkill" exit /b 1
if "%~4"=="am" exit /b 0
if "%~4"=="chmod" exit /b 0
if "%~4"=="test" exit /b 0
if "%~4"=="ls" (
  echo -rwxr-xr-x shell shell touch_relay
  exit /b 0
)
exit /b 23
:install_failed
exit /b 1
'@ | Set-Content -LiteralPath $mockPath -Encoding ASCII
@'
add device 1: /dev/input/event12
  name: "ILITEK ILITEK-TP"
  events:
    ABS_MT_SLOT : value 0, min 0, max 9
    ABS_MT_POSITION_X : value 0, min 0, max 16384, fuzz 0
    ABS_MT_POSITION_Y : value 0, min 0, max 16384, fuzz 0
  input props:
    INPUT_PROP_DIRECT
'@ | Set-Content -LiteralPath (Join-Path $testRoot 'capabilities.txt') -Encoding ASCII
@'
I: Bus=0003 Vendor=222a Product=0001 Version=0110
N: Name="ILITEK ILITEK-TP"
H: Handlers=event12
'@ | Set-Content -LiteralPath (Join-Path $testRoot 'input-devices.txt') -Encoding ASCII
$manifest = Get-Content -LiteralPath (Join-Path $BundleDirectory 'manifest.json') -Raw | ConvertFrom-Json
$env:TOUCHFIX_TEST_HASH = @($manifest.files | Where-Object path -EQ 'native/touch_relay')[0].sha256
$results = @()
function Invoke-MockCase {
    param([string]$Name, [string]$ScriptName, [string]$Scenario, [bool]$Success, [string[]]$Extra = @())
    $env:TOUCHFIX_TEST_SCENARIO = $Scenario
    $tracePath = Join-Path $testRoot 'trace.txt'
    if (Test-Path -LiteralPath $tracePath) { Remove-Item -LiteralPath $tracePath }
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        (Join-Path $BundleDirectory "scripts/$ScriptName"), '-AdbPath', $mockPath) + $Extra
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = @(& powershell.exe @arguments 2>&1); $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $savedPreference }
    $output | Set-Content -LiteralPath (Join-Path $testRoot "$Name.log") -Encoding UTF8
    if (($code -eq 0) -ne $Success) { throw "$Name failed (exit $code). See $testRoot" }
    $trace = if (Test-Path -LiteralPath $tracePath) { Get-Content -LiteralPath $tracePath -Raw } else { '' }
    if ($Name -in @('none', 'unauthorized', 'multiple', 'oldAndroid', 'x86', 'active')) {
        if ($trace -match '(?m)^-s MOCK_RENO8 (install|push)\b') { throw "$Name unexpectedly modified the phone." }
    }
    if ($Name -eq 'installFailure' -and $trace -match '(?m)^-s MOCK_RENO8 push\b') { throw 'Pushed native after failed APK install.' }
    if ($Name -eq 'install') {
        if ($trace -notmatch '(?m)^-s MOCK_RENO8 push\b' -or $trace -notmatch 'shell sha256sum') { throw 'Successful install did not deploy and verify relay.' }
        if ($trace -match 'shell am start\b|shell /data/local/tmp/touch_relay\b') { throw 'Installer started input ownership.' }
    }
    if ($Name -eq 'readOnlyCheck' -and $trace -match 'shell (am|pkill|chmod)\b| (install|push)\b') { throw 'Device check performed a mutation.' }
    if ($Name -eq 'stop' -and $trace -notmatch 'shell am force-stop com.gitee.connect_screen.touchfix') { throw 'Stop did not target test package.' }
    Write-Host "PASS: $Name"
    return [ordered]@{ name=$Name; passed=$true; exitCode=$code }
}
try {
    foreach ($scenario in @('none', 'unauthorized', 'multiple', 'oldAndroid', 'x86', 'active', 'installFailure', 'badRemoteHash')) {
        $results += Invoke-MockCase $scenario 'Install-TouchFix.ps1' $scenario $false
    }
    $results += Invoke-MockCase 'install' 'Install-TouchFix.ps1' 'good' $true
    $results += Invoke-MockCase 'stderrSuccess' 'Install-TouchFix.ps1' 'stderrSuccess' $true
    $results += Invoke-MockCase 'explicitSerial' 'Install-TouchFix.ps1' 'multiple' $true @('-Serial', 'MOCK_RENO8')
    $results += Invoke-MockCase 'stop' 'Stop-TouchFix.ps1' 'good' $true
    $results += Invoke-MockCase 'readOnlyCheck' 'Check-Device.ps1' 'good' $true @('-OutputDirectory', (Join-Path $testRoot 'read only report'))
    $assessment = Get-Content -LiteralPath (Join-Path $testRoot 'read only report/assessment.txt') -Raw
    if (!$assessment.StartsWith('CANDIDATE OK')) { throw 'Eligible panel was not recognized by the shipped device checker.' }
    . (Join-Path $BundleDirectory 'scripts/Common.ps1')
    $caps = Get-Content -LiteralPath (Join-Path $testRoot 'capabilities.txt') -Raw
    $devices = Get-Content -LiteralPath (Join-Path $testRoot 'input-devices.txt') -Raw
    $panelCases = @(
        @{name='panelUsb'; result=(Get-PanelAssessment $caps $devices); prefix='CANDIDATE OK'},
        @{name='panelEvent2'; result=(Get-PanelAssessment ($caps.Replace('event12','event2')) ($devices.Replace('event12','event2'))); prefix='BLOCKED'},
        @{name='panelRawRange'; result=(Get-PanelAssessment ($caps.Replace('16384','32767')) $devices); prefix='BLOCKED'},
        @{name='panelMultiple'; result=(Get-PanelAssessment ($caps+"`n"+$caps.Replace('device 1','device 2').Replace('event12','event13')) $devices); prefix='NOT READY'},
        @{name='panelInternalBus'; result=(Get-PanelAssessment $caps ($devices.Replace('0003','0018'))); prefix='UNCONFIRMED'},
        @{name='panelMissing'; result=(Get-PanelAssessment '' ''); prefix='NOT READY'}
    )
    foreach ($case in $panelCases) {
        if (!$case.result.StartsWith($case.prefix)) { throw "Panel assessment failed: $($case.name)" }
        Write-Host "PASS: $($case.name)"
        $results += [ordered]@{name=$case.name; passed=$true; result=$case.result}
    }
    $scriptHashes = @(Get-ChildItem -LiteralPath (Join-Path $BundleDirectory 'scripts') -Filter '*.ps1' | ForEach-Object {
        [ordered]@{ name=$_.Name; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    [ordered]@{ method='Mock ADB; no real phone commands'; scriptHashes=$scriptHashes; cases=$results } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $testRoot 'deployment-tests.json') -Encoding UTF8
    Write-Host "All $($results.Count) deployment cases passed. Report: $testRoot"
} finally {
    Remove-Item Env:\TOUCHFIX_TEST_HASH -ErrorAction SilentlyContinue
    Remove-Item Env:\TOUCHFIX_TEST_SCENARIO -ErrorAction SilentlyContinue
}
