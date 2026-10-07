param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkDirectory,
    [string]$AdbDirectory,
    [string]$DeploymentTestReport,
    [switch]$SkipBuild,
    [switch]$Offline
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Supply -JavaHome with a JDK 17 directory.'
}
$env:JAVA_HOME = $JavaHome
if (!$SdkDirectory) {
    $local = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $local) {
        $line = Get-Content -LiteralPath $local | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $SdkDirectory = ($line -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\') }
    }
}
if (!$SdkDirectory) { throw 'Supply -SdkDirectory or configure local.properties sdk.dir.' }
$signer = Join-Path $SdkDirectory 'build-tools/36.1.0/apksigner.bat'
$aapt = Join-Path $SdkDirectory 'build-tools/36.1.0/aapt.exe'
if (!(Test-Path -LiteralPath $signer) -or !(Test-Path -LiteralPath $aapt)) { throw 'Android build-tools 36.1.0 not found.' }
if (!$AdbDirectory) {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $AdbDirectory = Split-Path $command.Source -Parent }
    else { $AdbDirectory = Join-Path $SdkDirectory 'platform-tools' }
}
foreach ($name in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt', 'source.properties')) {
    if (!(Test-Path -LiteralPath (Join-Path $AdbDirectory $name))) { throw "Platform Tools file missing: $name" }
}
Push-Location $projectRoot
try {
    if (!$SkipBuild) {
        $buildArgs = @(':app:assembleDebug', ':app:testDebugUnitTest')
        if ($Offline) { $buildArgs = @('--offline') + $buildArgs }
        & ./gradlew.bat @buildArgs
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build or unit tests failed.' }
    }
    $apkPath = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
    $metadata = Get-Content 'app/build/outputs/apk/debug/output-metadata.json' -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.gitee.connect_screen.touchfix') { throw 'Unexpected APK package ID.' }
    $version = $metadata.elements[0]
    $badging = @(& $aapt dump badging $apkPath 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
    $packageLine = @($badging | Where-Object { "$_" -match '^package:' })[0]
    if ($packageLine -notmatch "name='com.gitee.connect_screen.touchfix'" -or
            $packageLine -notmatch ("versionCode='" + $version.versionCode + "'") -or
            $packageLine -notmatch ("versionName='" + [regex]::Escape($version.versionName) + "'")) {
        throw 'APK contents do not match Gradle output metadata.'
    }
    $signature = @(& $signer verify --verbose --print-certs $apkPath 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $relayPath = Join-Path $projectRoot 'native/bin/touch_relay'
    $elf = [IO.File]::ReadAllBytes($relayPath)
    if ($elf.Length -lt 64 -or $elf[0] -ne 127 -or $elf[1] -ne 69 -or $elf[2] -ne 76 -or
            $elf[3] -ne 70 -or $elf[4] -ne 2 -or $elf[5] -ne 1 -or
            [BitConverter]::ToUInt16($elf, 18) -ne 183) { throw 'Relay must be little-endian ELF64 AArch64.' }
    $phoff = [BitConverter]::ToUInt64($elf, 32)
    $phsize = [BitConverter]::ToUInt16($elf, 54)
    $phnum = [BitConverter]::ToUInt16($elf, 56)
    if ($phsize -lt 56 -or $phoff + $phsize * $phnum -gt $elf.Length) { throw 'Invalid ELF program header table.' }
    for ($i = 0; $i -lt $phnum; $i++) {
        if ([BitConverter]::ToUInt32($elf, [int]($phoff + $i * $phsize)) -eq 3) {
            throw 'Relay has a dynamic interpreter; a static executable is required.'
        }
    }
    $testFiles = @(Get-ChildItem 'app/build/test-results/testDebugUnitTest' -Filter 'TEST-*.xml')
    if ($testFiles.Count -ne 3) { throw 'Expected three touch coordinate test suites.' }
    $tests = 0
    foreach ($testFile in $testFiles) {
        [xml]$suite = Get-Content -LiteralPath $testFile.FullName -Raw
        if ([int]$suite.testsuite.failures -ne 0 -or [int]$suite.testsuite.errors -ne 0) { throw 'Unit test failure in saved report.' }
        $tests += [int]$suite.testsuite.tests
    }
    if ($tests -lt 9) { throw 'Required touch unit tests are missing.' }
    $deploymentCount = 0
    if ($DeploymentTestReport) {
        $deploymentTests = Get-Content -LiteralPath $DeploymentTestReport -Raw | ConvertFrom-Json
        if (@($deploymentTests.cases | Where-Object { !$_.passed }).Count -gt 0) { throw 'Deployment test report contains failures.' }
        foreach ($file in Get-ChildItem 'deployment/scripts' -Filter '*.ps1') {
            $record = @($deploymentTests.scriptHashes | Where-Object name -EQ $file.Name)
            if ($record.Count -ne 1 -or $record[0].sha256 -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) {
                throw 'Deployment tests do not match the current scripts.'
            }
        }
        $deploymentCount = @($deploymentTests.cases).Count
    }
    $bundleName = 'touchfix-' + $version.versionName + '-oppo-reno8-android14-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    $dist = Join-Path $projectRoot 'dist'
    $bundle = Join-Path $dist $bundleName
    if (Test-Path -LiteralPath $bundle) { throw 'Output directory already exists; wait a second and retry.' }
    $null = New-Item -ItemType Directory -Path $bundle
    foreach ($folder in @('native', 'scripts', 'platform-tools', 'validation')) {
        $null = New-Item -ItemType Directory -Path (Join-Path $bundle $folder)
    }
    Copy-Item -LiteralPath $apkPath -Destination (Join-Path $bundle 'touchfix.apk')
    Copy-Item -LiteralPath $relayPath -Destination (Join-Path $bundle 'native/touch_relay')
    Copy-Item -Path 'deployment/*.cmd' -Destination $bundle
    Copy-Item -Path 'deployment/scripts/*.ps1' -Destination (Join-Path $bundle 'scripts')
    Copy-Item -LiteralPath 'doc/TOUCHFIX_INSTALL_ZH.md' -Destination (Join-Path $bundle 'INSTALL_ZH.md')
    Copy-Item -LiteralPath 'doc/TOUCHFIX_PROJECT.md' -Destination (Join-Path $bundle 'PROJECT_ZH.md')
    Copy-Item -LiteralPath 'LICENSE' -Destination $bundle
    Copy-Item -LiteralPath 'native/VALIDATION.md' -Destination (Join-Path $bundle 'validation/DEVICE_HISTORY.md')
    foreach ($testFile in $testFiles) { Copy-Item -LiteralPath $testFile.FullName -Destination (Join-Path $bundle 'validation') }
    $signature | Set-Content -LiteralPath (Join-Path $bundle 'validation/apk-signature.txt') -Encoding UTF8
    $badging | Set-Content -LiteralPath (Join-Path $bundle 'validation/apk-manifest.txt') -Encoding UTF8
    if ($DeploymentTestReport) {
        Copy-Item -LiteralPath $DeploymentTestReport -Destination (Join-Path $bundle 'validation/deployment-tests.json')
    }
    foreach ($name in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt', 'source.properties', 'libwinpthread-1.dll')) {
        $file = Join-Path $AdbDirectory $name
        if (Test-Path -LiteralPath $file) { Copy-Item -LiteralPath $file -Destination (Join-Path $bundle 'platform-tools') }
    }
    # Use a source allowlist instead of copying a checkout containing device diagnostics.
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sourceZip = Join-Path $bundle 'project-source.zip'
    $sourceFiles = @()
    foreach ($folder in @('app/src', 'hidden-api-stub/src', 'termux-x11-app/src',
            'termux-x11-shell-loader/src', 'gradle', 'deployment', 'tools', 'doc')) {
        $sourceFiles += @(Get-ChildItem -LiteralPath $folder -File -Recurse)
    }
    foreach ($folder in @('app', 'hidden-api-stub', 'termux-x11-app', 'termux-x11-shell-loader')) {
        $sourceFiles += @(Get-ChildItem -LiteralPath $folder -File | Where-Object { $_.Name -in @('build.gradle', 'proguard-rules.pro', '.gitignore', 'build.sh') })
    }
    foreach ($name in @('touch_relay.c', 'build.sh', 'README.md', 'VALIDATION.md', 'calibration-mate30.json')) {
        $sourceFiles += Get-Item -LiteralPath (Join-Path 'native' $name)
    }
    foreach ($name in @('build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat',
            'README.md', 'LICENSE', '.gitignore', '.gitmodules')) {
        $sourceFiles += Get-Item -LiteralPath $name
    }
    $archive = [IO.Compression.ZipFile]::Open($sourceZip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in ($sourceFiles | Sort-Object FullName -Unique)) {
            $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
            $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $relative)
        }
    } finally { $archive.Dispose() }
    $commit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot record source commit.' }
    $status = @(& git status --short)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot record working-tree status.' }
    @(
        "APK signature verified; ELF64 AArch64 static relay verified.",
        "Unit tests: $tests passed, 0 failures, 0 errors (Gradle may reuse matching task outputs).",
        "Deployment tests: $deploymentCount cases passed using mock ADB; no physical phone was used.",
        "Source base commit: $commit; current working-tree source included in project-source.zip.",
        "Target: user-specified OPPO Reno8 5G / Android 14 / same ILITEK panel / 1080x1920.",
        'Device installation evidence: see validation/DEVICE_HISTORY.md. Physical touch is not validated by packaging checks.',
        'Installation does not start touch correction. Mate 30 calibration is not imported.',
        'Native binary reused from native/bin/touch_relay; no native source changes in packaging work.',
        'Working-tree status:', ($status -join "`n")
    ) | Set-Content -LiteralPath (Join-Path $bundle 'validation/package-checks.txt') -Encoding UTF8
    $records = @(Get-ChildItem -LiteralPath $bundle -File -Recurse | Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = $_.FullName.Substring($bundle.Length + 1).Replace('\', '/')
            size = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
    [ordered]@{
        applicationId = $metadata.applicationId
        versionCode = $version.versionCode
        versionName = $version.versionName
        builtAt = (Get-Date -Format 'o')
        sourceBaseCommit = $commit
        sourceIncludesWorkingTree = $true
        apkSigning = 'existing Android debug certificate'
        targetPhone = 'OPPO Reno8 5G / user-reported Android 14'
        targetPhysicallyVerified = $false
        tests = $tests
        deploymentMockTests = $deploymentCount
        native = 'ELF64 AArch64 static, independently deployed'
        files = $records
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $bundle 'manifest.json') -Encoding UTF8
    @($records | ForEach-Object { "$($_.sha256)  $($_.path)" }) +
        @((Get-FileHash -LiteralPath (Join-Path $bundle 'manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant() + '  manifest.json') |
        Set-Content -LiteralPath (Join-Path $bundle 'SHA256SUMS.txt') -Encoding ASCII
    # Run the shipped local validation before archiving it.
    & (Join-Path $bundle 'scripts/Install-TouchFix.ps1') -ValidateOnly
    $zipPath = $bundle + '.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($bundle, $zipPath, [IO.Compression.CompressionLevel]::Optimal, $true)
    $zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$zipHash  $([IO.Path]::GetFileName($zipPath))" | Set-Content -LiteralPath ($zipPath + '.sha256') -Encoding ASCII
    Write-Host "Bundle: $bundle"
    Write-Host "ZIP: $zipPath"
    Write-Host "SHA256: $zipHash"
} finally { Pop-Location }
